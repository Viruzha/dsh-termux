package site.viruzha.hub;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 打卡悬浮窗的前台服务。
 *
 * 为什么必须是前台服务：Android 会回收普通后台进程，悬浮窗随之消失。
 * 代价是通知栏有一条常驻通知（这是系统强制要求，无法去掉）。
 */
public class PunchService extends Service implements PunchBubble.Listener, PunchBubble.DragHandler {

    private static final String CHANNEL = "punch";
    private static final int NOTI_ID = 1001;
    private static final int REQ_RESET = 2001;   // 闹钟的 PendingIntent 请求码

    /**
     * 界面可见时注册，用于打卡后即时刷新（避免为此引入广播接收器）。
     *
     * 用 WeakReference 而非强引用：SetuiListener 由页面的 onShow 注册、
     * onHide 注销，但「直接退出 App」不会走 onHide —— 强引用会让服务长期
     * 持有一个已销毁的 Activity（连同整棵视图树）造成泄漏。
     */
    public interface UiListener { void onPunchChanged(); }

    private static WeakReference<UiListener> uiRef;
    public static void setUiListener(UiListener l) {
        uiRef = (l == null) ? null : new WeakReference<UiListener>(l);
    }
    private static void notifyUi() {
        UiListener l = (uiRef != null) ? uiRef.get() : null;
        if (l != null) l.onPunchChanged();
    }
    public static boolean isRunning() { return running; }
    private static boolean running = false;

    /** 服务实例引用：界面删完记录后要立刻刷新悬浮框。onDestroy 会清掉。 */
    private static PunchService instance;

    /** 让悬浮框按最新数据重绘（界面删记录、跨天、亮屏时调用）。 */
    public static void refreshNow() {
        if (instance != null) instance.refreshBubble();
    }

    /**
     * 排下一次「打卡日切换」（凌晨 5 点）的闹钟。
     *
     * 用 RTC（**不是** RTC_WAKEUP）：不为改一个颜色把设备从休眠里叫醒。
     * RTC 闹钟会在设备自然醒来时投递 —— 那正好是用户要看手机的时机。
     * 用 set() 而非 setExact*：晚几分钟无所谓，且不需要任何特殊权限。
     */
    public static void scheduleReset(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent i = new Intent(c, PunchTickReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(c, REQ_RESET, i, flags);

        long now = System.currentTimeMillis();
        long at = now + PunchStore.msUntilReset(now);
        am.set(AlarmManager.RTC, at, pi);

        Log.i("HUB", "已排下次刷新: " + new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
                .format(new Date(at)));
    }

    public static void cancelReset(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(c, PunchTickReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(c, REQ_RESET, i, flags);
        am.cancel(pi);
        pi.cancel();
    }

    private final PunchTickReceiver tickReceiver = new PunchTickReceiver();

    private WindowManager wm;
    private PunchBubble bubble;
    private WindowManager.LayoutParams lp;
    private PunchStore store;

    public static void start(Context c) {
        // 记下「用户希望它开着」，开机广播据此决定是否自动拉起
        new PunchStore(c).setEnabled(true);
        Intent i = new Intent(c, PunchService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void stop(Context c) {
        // 只有用户主动关闭才清标志；被系统回收不算
        new PunchStore(c).setEnabled(false);
        c.stopService(new Intent(c, PunchService.class));
    }

    @Override public void onCreate() {
        super.onCreate();
        store = new PunchStore(this);
        startForeground(NOTI_ID, buildNotification());
        showBubble();
        running = true;
        instance = this;

        // 亮屏/解锁/改时间时重算状态：设备醒来正好是用户要看手机的时候。
        // 这几个是隐式广播，只能动态注册（前台服务里注册很稳）。
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        try { registerReceiver(tickReceiver, f); } catch (Exception e) {
            Log.w("HUB", "注册亮屏接收器失败: " + e.getMessage());
        }

        scheduleReset(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // 被系统重启后也要把悬浮窗重新挂上
        if (bubble == null) {
            if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY; }
            showBubble();
        }
        refreshBubble();
        return START_STICKY;
    }

    private void showBubble() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "缺少「显示在其他应用上层」权限", Toast.LENGTH_LONG).show();
            stopSelf();
            return;
        }
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        bubble = new PunchBubble(this, store, this);

        int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;

        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        int x = store.bubbleX(), y = store.bubbleY();
        boolean bad = x < 0 || y < 0 || x > sw - dp(40) || y > sh - dp(40);
        if (bad) {
            lp.x = sw - dp(120);   // 默认停在右侧偏上
            lp.y = dp(220);
        } else {
            lp.x = x; lp.y = y;
        }

        bubble.setDragHandler(this);
        try {
            wm.addView(bubble, lp);
        } catch (Exception e) {
            Toast.makeText(this, "悬浮窗添加失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            stopSelf();
        }
    }

    private void refreshBubble() {
        if (bubble != null) bubble.refresh();
        notifyUi();
    }

    private int dp(int v) { return (int) (getResources().getDisplayMetrics().density * v); }

    // ---- PunchBubble.Listener ------------------------------------------------
    @Override public void onPunch() {
        long now = System.currentTimeMillis();
        if (store.punch(now)) {
            Toast.makeText(this, "已打卡 " + PunchStore.hhmmss(now), Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "今天已打卡 " + PunchStore.hhmm(store.today(now)), Toast.LENGTH_SHORT).show();
        }
        refreshBubble();
    }

    @Override public void onLongPress() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try { startActivity(i); } catch (Exception ignored) { }
    }

    // ---- PunchBubble.DragHandler --------------------------------------------
    @Override public int[] startPos() {
        return (lp != null) ? new int[] { lp.x, lp.y } : new int[] { 0, 0 };
    }

    @Override public void moveTo(int x, int y) {
        if (bubble == null || lp == null) return;
        // 钳制在屏幕内，避免被拖出可视区域（否则会存下负坐标）
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        android.util.Log.i("HUB", "moveTo 请求=" + x + "," + y);
        lp.x = Math.max(0, Math.min(x, w - dp(48)));
        lp.y = Math.max(0, Math.min(y, h - dp(48)));
        try { wm.updateViewLayout(bubble, lp); } catch (Exception ignored) { }
    }

    // ---- 通知 ---------------------------------------------------------------
    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "打卡悬浮窗",
                    NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setContentTitle("打卡悬浮窗已开启")
         .setContentText("点悬浮按钮打卡 · 每天凌晨 5 点刷新")
         .setSmallIcon(R.drawable.ic_punch)
         .setContentIntent(pi)
         .setOngoing(true);
        return b.build();
    }

    @Override public void onDestroy() {
        running = false;
        instance = null;
        try { unregisterReceiver(tickReceiver); } catch (Exception ignored) { }
        cancelReset(this);
        if (bubble != null && wm != null) {
            store.saveBubblePos(lp.x, lp.y);
            try { wm.removeView(bubble); } catch (Exception ignored) { }
            bubble = null;
        }
        notifyUi();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
