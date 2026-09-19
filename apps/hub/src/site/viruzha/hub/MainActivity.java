package site.viruzha.hub;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 应用壳：底部导航 + 内容容器。
 *
 * 扩展方式：新增一个 Screen 子类，然后在下面的注册区 add 一行即可，
 * 图标/标题/选中态由本类统一处理。
 */
public class MainActivity extends Activity implements View.OnClickListener {

    public Prefs prefs;

    private FrameLayout content;
    private LinearLayout nav;
    private final List<Screen> screens = new ArrayList<Screen>();
    private final List<View> navItems = new ArrayList<View>();
    private int current = -1;

    private static final int[] TAB_ICON  = { R.drawable.ic_wake, R.drawable.ic_devices,
                                             R.drawable.ic_punch, R.drawable.ic_dsh,
                                             R.drawable.ic_settings };
    private static final int[] TAB_LABEL = { R.string.tab_wake, R.string.tab_devices,
                                             R.string.tab_punch, R.string.tab_dsh,
                                             R.string.tab_settings };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs   = new Prefs(this);
        content = (FrameLayout) findViewById(R.id.content);
        nav     = (LinearLayout) findViewById(R.id.nav);

        // ---- 屏幕注册区：新增功能在这里加一行 ----
        screens.add(new WakeScreen(this));
        screens.add(new DevicesScreen(this));
        screens.add(new PunchScreen(this));
        screens.add(new DshScreen(this));
        screens.add(new SettingsScreen(this));

        LayoutInflater inf = LayoutInflater.from(this);
        for (int i = 0; i < screens.size(); i++) {
            View item = inf.inflate(R.layout.nav_item, nav, false);
            ((ImageView) item.findViewById(R.id.nav_icon)).setImageResource(TAB_ICON[i]);
            ((TextView) item.findViewById(R.id.nav_label)).setText(TAB_LABEL[i]);
            item.setTag(Integer.valueOf(i));
            item.setOnClickListener(this);
            nav.addView(item);
            navItems.add(item);
        }
        select(0);
    }

    @Override public void onClick(View v) {
        Object tag = v.getTag();
        if (tag instanceof Integer) select(((Integer) tag).intValue());
    }

    /** 整个 App 前后台切换时，通知当前页面启停定时刷新。 */
    @Override protected void onResume() {
        super.onResume();
        if (current >= 0) screens.get(current).onResume();
    }

    @Override protected void onPause() {
        if (current >= 0) screens.get(current).onPause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        // 清掉对当前页面的静态引用，避免服务长期持有已销毁的 Activity
        PunchService.setUiListener(null);
        super.onDestroy();
    }

    private void select(int index) {
        if (index == current || index < 0 || index >= screens.size()) return;
        if (current >= 0) screens.get(current).onHide();

        Screen s = screens.get(index);
        if (s.root == null) s.root = s.build();
        content.removeAllViews();
        content.addView(s.root, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        current = index;

        for (int i = 0; i < navItems.size(); i++) {
            boolean on = i == index;
            View item = navItems.get(i);
            item.setSelected(on);
            int c = getColor(on ? R.color.accent : R.color.text_dim);
            ((ImageView) item.findViewById(R.id.nav_icon)).setColorFilter(c);
            ((TextView) item.findViewById(R.id.nav_label)).setTextColor(c);
        }
        s.onShow();
    }

    /** 轻提示。 */
    public void toast(String msg) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
    }

    public String aboutText() {
        return "Viruzha 工具箱  v0.2\n"
             + "Android " + Build.VERSION.RELEASE + "（API " + Build.VERSION.SDK_INT + "）\n\n"
             + "链路：本机 → Tailscale Funnel 公网入口 → 家里的 Proxmox 主机 → wakeonlan\n"
             + "本 App 不需要开启任何 VPN，也不依赖 Tailscale 应用。\n\n"
             + "用无 Gradle 流水线构建：aapt2 + javac + d8 + zipalign + apksigner。\n\n"
             + "DSH 页内嵌完整 node 运行时，不需要 Termux。\n\n"
             + "打卡页的悬浮按钮会常驻在所有界面最上层。";
    }
}
