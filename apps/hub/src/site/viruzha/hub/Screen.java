package site.viruzha.hub;

import android.view.View;

/** 一个 Tab 对应一个 Screen。新增功能 = 新增一个子类 + 在 MainActivity 注册一行。 */
public abstract class Screen {

    protected final MainActivity app;
    View root;

    public Screen(MainActivity app) { this.app = app; }

    /** 首次切到该 Tab 时调用一次，返回根视图。 */
    public abstract View build();

    /** 每次切到该 Tab 时调用。 */
    public void onShow() { }

    /** 离开该 Tab 时调用。 */
    public void onHide() { }

    /**
     * Activity 可见/不可见时调用。与 onShow/onHide 不同：
     * 后者只在**切换 Tab** 时触发，本方法在**整个 App 前后台切换**时触发。
     * 需要跑定时刷新（倒计时等）的页面应在这里启停，避免退到后台还在每秒唤醒 CPU。
     */
    public void onResume() { }

    public void onPause() { }

    protected View $(int id) { return root.findViewById(id); }
}
