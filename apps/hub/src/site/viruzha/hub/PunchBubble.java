package site.viruzha.hub;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

/**
 * 悬浮打卡按钮：一个圆角胶囊。
 *   未打卡 = 深灰底 +「未打卡」
 *   已打卡 = 绿底 + 打卡时间
 *
 * 手势：轻点=打卡，拖动=移位，长按=打开 App。
 * onTouch 返回 true 会吞掉系统手势检测，所以长按必须自己计时。
 * 注意：不用匿名内部类（d8 3.3.20 处理匿名类会 NPE）。
 */
public class PunchBubble extends TextView implements View.OnTouchListener {

    public interface Listener {
        void onPunch();
        void onLongPress();
    }

    /**
     * 拖动支持。由 Service 实现。
     *
     * 起点必须由 Service 给出（即 WindowManager.LayoutParams 的 x/y），
     * **不能**用 View.getLeft()/getTop() —— 对 WindowManager 添加的窗口，
     * 那两个值不是窗口在屏幕上的位置，会让拖动基准错乱、位置跳变。
     */
    public interface DragHandler {
        int[] startPos();
        void moveTo(int x, int y);
    }

    private static final long LONG_PRESS_MS = 600;

    private final PunchStore store;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final int slop;

    private float downX, downY;
    private int startX, startY;
    private boolean dragging;
    private boolean longFired;
    private DragHandler onDrag;

    private final Runnable longPressTask = new LongPress(this);

    public PunchBubble(Context c, PunchStore store, Listener listener) {
        super(c);
        this.store = store;
        this.listener = listener;
        this.slop = dp(20);   // 阈值取大些：手指轻触时的微小抖动不应被判成拖动

        setGravity(Gravity.CENTER);
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        setPadding(dp(14), dp(8), dp(14), dp(8));
        setClickable(true);
        setOnTouchListener(this);
        refresh();
    }

    private int dp(int v) { return (int) (getResources().getDisplayMetrics().density * v); }

    public void setDragHandler(DragHandler h) { onDrag = h; }

    /** 按当前打卡状态刷新外观。 */
    public void refresh() {
        long t = store.today(System.currentTimeMillis());
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(22));
        if (t > 0) {
            bg.setColor(Color.parseColor("#FF3DDC84"));
            bg.setStroke(dp(1), Color.parseColor("#FF6BF0AA"));
            setText(PunchStore.hhmm(t));
            setTextColor(Color.parseColor("#FF06150D"));
        } else {
            bg.setColor(Color.parseColor("#FF1C2732"));
            bg.setStroke(dp(1), Color.parseColor("#FF35506B"));
            setText("未打卡");
            setTextColor(Color.parseColor("#FFEDF3F7"));
        }
        setBackground(bg);
    }

    @Override public boolean onTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX();
                downY = e.getRawY();
                int[] p = (onDrag != null) ? onDrag.startPos() : null;
                startX = (p != null) ? p[0] : 0;
                startY = (p != null) ? p[1] : 0;
                dragging = false;
                longFired = false;
                android.util.Log.i("HUB", "DOWN raw=" + downX + "," + downY
                        + " start=" + startX + "," + startY);
                ui.postDelayed(longPressTask, LONG_PRESS_MS);
                return true;

            case MotionEvent.ACTION_MOVE: {
                int dx = (int) (e.getRawX() - downX);
                int dy = (int) (e.getRawY() - downY);
                if (!dragging && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                    dragging = true;
                    ui.removeCallbacks(longPressTask);   // 开始拖动就不再算长按
                }
                if (dragging && onDrag != null) onDrag.moveTo(startX + dx, startY + dy);
                return true;
            }

            case MotionEvent.ACTION_UP:
                ui.removeCallbacks(longPressTask);
                if (!dragging && !longFired) {
                    performClick();          // 补上：onTouch 返回 true 会吞掉系统的手势检测
                    listener.onPunch();
                } else {
                    android.util.Log.i("HUB", "UP 未触发打卡 dragging=" + dragging
                            + " longFired=" + longFired);
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                ui.removeCallbacks(longPressTask);
                return true;

            default:
                return false;
        }
    }

    static class LongPress implements Runnable {
        private final PunchBubble b;
        LongPress(PunchBubble b) { this.b = b; }
        @Override public void run() {
            if (b.dragging) return;
            b.longFired = true;
            b.listener.onLongPress();
        }
    }
}
