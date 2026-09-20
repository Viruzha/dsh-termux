package site.viruzha.hub;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/**
 * 打卡 Tab：悬浮按钮的开关、今天的打卡状态、历史记录。
 */
public class PunchScreen extends Screen implements View.OnClickListener, PunchService.UiListener {

    private PunchStore store;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView stateTxt, timeTxt, countdown, permTxt, toggle, hint, batt, battBtn, undo;
    private LinearLayout history;

    public PunchScreen(MainActivity app) { super(app); }

    @Override public View build() {
        store = new PunchStore(app);
        root = LayoutInflater.from(app).inflate(R.layout.screen_punch, null);

        stateTxt = (TextView) $(R.id.p_today_state);
        timeTxt  = (TextView) $(R.id.p_today_time);
        countdown= (TextView) $(R.id.p_countdown);
        permTxt  = (TextView) $(R.id.p_perm);
        toggle   = (TextView) $(R.id.p_toggle);
        hint     = (TextView) $(R.id.p_hint);
        batt     = (TextView) $(R.id.p_batt);
        battBtn  = (TextView) $(R.id.p_batt_btn);
        undo     = (TextView) $(R.id.p_undo);
        history  = (LinearLayout) $(R.id.p_history);

        $(R.id.p_grant).setOnClickListener(this);
        toggle.setOnClickListener(this);
        battBtn.setOnClickListener(this);
        undo.setOnClickListener(this);
        return root;
    }

    @Override public void onShow() {
        PunchService.setUiListener(this);
        refresh();
        startTick();
    }

    @Override public void onHide() {
        PunchService.setUiListener(null);
        stopTick();
    }

    // 前后台切换时也要停/启倒计时，否则退到后台还在每秒唤醒 CPU
    @Override public void onResume() { if (root != null) startTick(); }
    @Override public void onPause()  { stopTick(); }

    private void startTick() {
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    private void stopTick() { ui.removeCallbacks(tick); }

    @Override public void onPunchChanged() { refresh(); }

    @Override public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.p_grant) {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + app.getPackageName()));
            try { app.startActivity(i); }
            catch (Exception e) { app.toast("无法打开悬浮窗权限设置：" + e.getMessage()); }
            return;
        }
        if (id == R.id.p_undo) {
            confirmDelete(PunchStore.punchDay(System.currentTimeMillis()));
            return;
        }
        if (id == R.id.p_batt_btn) {
            // 请求加入电池优化白名单：降低前台服务被系统回收的概率
            try {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + app.getPackageName()));
                app.startActivity(i);
            } catch (Exception e) {
                // 部分系统没有这个 Activity，退回电池优化列表
                try { app.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
                catch (Exception e2) { app.toast("无法打开电池优化设置"); }
            }
            return;
        }
        if (id == R.id.p_toggle) {
            if (PunchService.isRunning()) {
                PunchService.stop(app);
                app.toast("已关闭悬浮按钮");
            } else {
                if (!Settings.canDrawOverlays(app)) {
                    app.toast("请先授予「显示在其他应用上层」权限");
                    return;
                }
                PunchService.start(app);
                app.toast("已开启悬浮按钮");
            }
            ui.postDelayed(refreshTask, 400);   // 等服务起来/停掉后刷新
        }
    }

    // ---- 刷新 ---------------------------------------------------------------
    private void refresh() {
        long now = System.currentTimeMillis();
        long t = store.today(now);
        boolean done = t > 0;
        boolean running = PunchService.isRunning();
        boolean hasPerm = Settings.canDrawOverlays(app);

        stateTxt.setText(done ? app.getString(R.string.punch_state_done)
                              : app.getString(R.string.punch_state_todo));
        timeTxt.setText(done ? PunchStore.hhmmss(t) : "--:--:--");
        timeTxt.setTextColor(app.getColor(done ? R.color.accent : R.color.text_dim));

        long left = PunchStore.msUntilReset(now);
        long h = left / 3600000, m = (left % 3600000) / 60000, s = (left % 60000) / 1000;
        countdown.setText(String.format(Locale.US, "距离刷新（凌晨 5 点）还有 %d 小时 %02d 分 %02d 秒", h, m, s));

        permTxt.setText(app.getString(hasPerm ? R.string.punch_has_perm : R.string.punch_no_perm));
        permTxt.setTextColor(app.getColor(hasPerm ? R.color.accent : R.color.danger));
        ((View) $(R.id.p_grant)).setVisibility(hasPerm ? View.GONE : View.VISIBLE);

        // 电池优化豁免状态
        boolean exempt = false;
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) app.getSystemService(android.content.Context.POWER_SERVICE);
            exempt = pm != null && pm.isIgnoringBatteryOptimizations(app.getPackageName());
        } catch (Exception ignored) { }
        batt.setText(app.getString(exempt ? R.string.punch_batt_ok : R.string.punch_batt_no));
        batt.setTextColor(app.getColor(exempt ? R.color.accent : R.color.text_dim));
        battBtn.setVisibility(exempt ? View.GONE : View.VISIBLE);

        toggle.setText(app.getString(running ? R.string.punch_off : R.string.punch_on));
        toggle.setBackgroundResource(running ? R.drawable.btn_ghost : R.drawable.btn_wake_sel);
        toggle.setTextColor(running ? app.getColor(R.color.text) : Color.parseColor("#FF06150D"));
        hint.setText(running ? "悬浮按钮显示中 · 轻点打卡 · 长按打开 App · 可拖动"
                             : "开启后会在所有界面最上层常驻一个小按钮");

        undo.setVisibility(done ? View.VISIBLE : View.GONE);

        // 状态点
        View d = $(R.id.p_dot);
        d.setBackgroundResource(done ? R.drawable.dot_on : R.drawable.dot_off);

        buildHistory();
    }

    private void buildHistory() {
        history.removeAllViews();
        List<PunchStore.Entry> list = store.all();
        if (list.isEmpty()) {
            history.addView(row(app.getString(R.string.punch_none), "", false, null));
            return;
        }
        int n = 0;
        for (PunchStore.Entry e : list) {
            if (n++ >= 30) break;
            history.addView(row(PunchStore.friendly(e.day), PunchStore.hhmmss(e.time), true, e.day));
        }
        if (list.size() > 30) {
            history.addView(row("… 共 " + list.size() + " 天记录", "", false, null));
        }
    }

    private View row(String left, String right, boolean accent, String day) {
        LinearLayout r = new LinearLayout(app);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setBackgroundResource(R.drawable.card);
        int p = dp(16);
        r.setPadding(p, p, p, p);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(8);
        r.setLayoutParams(rlp);

        TextView a = new TextView(app);
        a.setText(left);
        a.setTextSize(15);
        a.setTextColor(app.getColor(accent ? R.color.text : R.color.text_dim));
        a.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(a);

        TextView b = new TextView(app);
        b.setText(right);
        b.setTextSize(15);
        b.setTextColor(app.getColor(accent ? R.color.accent : R.color.text_dim));
        b.setTypeface(android.graphics.Typeface.MONOSPACE);
        r.addView(b);

        if (day != null) {
            r.setTag(day);
            r.setClickable(true);
            r.setFocusable(true);
            r.setOnClickListener(rowClick);
        }
        return r;
    }

    /** 点历史行 → 确认后删除。 */
    void confirmDelete(String day) {
        new AlertDialog.Builder(app)
            .setTitle(app.getString(R.string.punch_del_title))
            .setMessage(app.getString(R.string.punch_del_msg, PunchStore.friendly(day)))
            .setNegativeButton(app.getString(R.string.punch_del_cancel), (DialogInterface.OnClickListener) null)
            .setPositiveButton(app.getString(R.string.punch_del_ok), new DeleteConfirm(this, day))
            .show();
    }

    void doDelete(String day) {
        boolean ok = store.remove(day);
        PunchService.refreshNow();   // 悬浮框立刻改回「未打卡」
        refresh();
        app.toast(ok ? "已删除 " + PunchStore.friendly(day) + " 的打卡记录" : "该记录已不存在");
    }

    private int dp(int v) { return (int) (app.getResources().getDisplayMetrics().density * v); }

    private final View.OnClickListener rowClick = new RowClick(this);
    private final Runnable tick = new Tick(this);
    private final Runnable refreshTask = new RefreshTask(this);

    static class Tick implements Runnable {
        private final PunchScreen s;
        Tick(PunchScreen s) { this.s = s; }
        @Override public void run() {
            long now = System.currentTimeMillis();

            // 只更新倒计时，避免每秒重建整个历史列表
            long left = PunchStore.msUntilReset(now);
            long h = left / 3600000, m = (left % 3600000) / 60000, sec = (left % 60000) / 1000;
            s.countdown.setText(String.format(Locale.US,
                    "距离刷新（凌晨 5 点）还有 %d 小时 %02d 分 %02d 秒", h, m, sec));

            // 跨过凌晨 5 点后要整体刷新一次（历史里会多出「今天未打卡」的状态）
            String d = PunchStore.punchDay(now);
            if (s.lastDay == null) {
                s.lastDay = d;
            } else if (!d.equals(s.lastDay)) {
                s.lastDay = d;
                PunchService.refreshNow();   // 页面和悬浮框要一起翻篇
                s.refresh();
            }
            s.ui.postDelayed(this, 1000);
        }
    }

    static class DeleteConfirm implements DialogInterface.OnClickListener {
        private final PunchScreen s; private final String day;
        DeleteConfirm(PunchScreen s, String day) { this.s = s; this.day = day; }
        @Override public void onClick(DialogInterface d, int which) { s.doDelete(day); }
    }

    static class RowClick implements View.OnClickListener {
        private final PunchScreen s;
        RowClick(PunchScreen s) { this.s = s; }
        @Override public void onClick(View v) {
            Object t = v.getTag();
            if (t instanceof String) s.confirmDelete((String) t);
        }
    }

    static class RefreshTask implements Runnable {
        private final PunchScreen s;
        RefreshTask(PunchScreen s) { this.s = s; }
        @Override public void run() { s.refresh(); }
    }

    private String lastDay;
}
