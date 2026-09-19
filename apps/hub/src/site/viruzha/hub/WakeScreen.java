package site.viruzha.hub;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 首页：一眼看到服务端状态，一键唤醒。 */
public class WakeScreen extends Screen implements Api.Callback, View.OnClickListener {

    private View chipDot;
    private TextView chipText, targetIp, devName, devMac, wakeBtn, wakeMsg, lastTime, lastDetail;

    public WakeScreen(MainActivity app) { super(app); }

    @Override public View build() {
        root = LayoutInflater.from(app).inflate(R.layout.screen_wake, null);
        chipDot    = $(R.id.chip_dot);
        chipText   = (TextView) $(R.id.chip_text);
        targetIp   = (TextView) $(R.id.target_ip);
        devName    = (TextView) $(R.id.dev_name);
        devMac     = (TextView) $(R.id.dev_mac);
        wakeBtn    = (TextView) $(R.id.wake_btn);
        wakeMsg    = (TextView) $(R.id.wake_msg);
        lastTime   = (TextView) $(R.id.last_time);
        lastDetail = (TextView) $(R.id.last_detail);
        wakeBtn.setOnClickListener(this);
        return root;
    }

    @Override public void onShow() {
        devName.setText(app.prefs.name());
        devMac.setText(app.prefs.mac());
        targetIp.setText(Ui.hostOf(app.prefs.endpoint()));
        String t = app.prefs.lastTime();
        lastTime.setText(t == null ? app.getString(R.string.none) : t);
        String d = app.prefs.lastDetail();
        lastDetail.setText(d == null ? app.getString(R.string.last_hint) : d);
        chip(R.drawable.dot_off, app.getString(R.string.state_checking), R.color.text_dim);
        Api.get(Ui.healthUrl(app.prefs.endpoint()), "health", this);
    }

    @Override public void onClick(View v) {
        if (v.getId() == R.id.wake_btn) {
            wakeBtn.setEnabled(false);
            wakeMsg.setText("正在发送…");
            Api.get(Api.wakeUrl(app.prefs.endpoint(), app.prefs.token()), "wake", this);
        }
    }

    @Override public void onResult(String tag, String body, int code, String error, long ms) {
        if ("health".equals(tag)) {
            if (error == null && code == 200) chip(R.drawable.dot_on, app.getString(R.string.state_ok), R.color.accent);
            else chip(R.drawable.dot_bad, app.getString(R.string.state_bad), R.color.danger);
            return;
        }
        if (!"wake".equals(tag)) return;

        wakeBtn.setEnabled(true);
        boolean ok = error == null && code == 200;
        String detail;
        if (ok)                detail = "魔术包已发送 · " + ms + " ms";
        else if (code == 401)  detail = "token 无效（HTTP 401），请到「设置」更新";
        else if (error != null) detail = error;
        else                   detail = "HTTP " + code + " · " + body;

        wakeMsg.setText(ok ? "✅ 已发送魔术包" : "❌ " + detail);
        if (ok) {
            String now = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
            lastTime.setText(now);
            lastDetail.setText(detail);
            app.prefs.setLast(now, detail);
        }
    }

    private void chip(int dotRes, String text, int colorRes) {
        chipDot.setBackgroundResource(dotRes);
        chipText.setText(text);
        chipText.setTextColor(app.getColor(colorRes));
    }
}
