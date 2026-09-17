package site.viruzha.wake;

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

    protected View $(int id) { return root.findViewById(id); }
}
