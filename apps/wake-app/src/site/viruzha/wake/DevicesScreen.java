package site.viruzha.wake;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 设备页：设备卡片列表。后续多设备只需往这里塞更多卡片。 */
public class DevicesScreen extends Screen implements Api.Callback, View.OnClickListener {

    private LinearLayout list;
    private View card;
    private View dot;
    private TextView name, state, mac, wake, check;

    public DevicesScreen(MainActivity app) { super(app); }

    @Override public View build() {
        root = LayoutInflater.from(app).inflate(R.layout.screen_devices, null);
        list = (LinearLayout) $(R.id.device_list);

        card = LayoutInflater.from(app).inflate(R.layout.device_card, list, false);
        dot   = card.findViewById(R.id.d_dot);
        name  = (TextView) card.findViewById(R.id.d_name);
        state = (TextView) card.findViewById(R.id.d_state);
        mac   = (TextView) card.findViewById(R.id.d_mac);
        wake  = (TextView) card.findViewById(R.id.d_wake);
        check = (TextView) card.findViewById(R.id.d_check);
        wake.setOnClickListener(this);
        check.setOnClickListener(this);
        list.addView(card);
        return root;
    }

    @Override public void onShow() {
        name.setText(app.prefs.name());
        mac.setText(app.prefs.mac());
        state.setText(app.getString(R.string.device_unknown));
        state.setTextColor(app.getColor(R.color.text_dim));
        dot.setBackgroundResource(R.drawable.dot_off);
    }

    @Override public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.d_wake) {
            wake.setEnabled(false);
            state.setText("发送中…");
            Api.get(Api.wakeUrl(app.prefs.endpoint(), app.prefs.token()), "wake", this);
        } else if (id == R.id.d_check) {
            state.setText("检测中…");
            Api.get(Ui.statusUrl(app.prefs.endpoint(), app.prefs.token()), "status", this);
        }
    }

    @Override public void onResult(String tag, String body, int code, String error, long ms) {
        if ("wake".equals(tag)) {
            wake.setEnabled(true);
            if (error == null && code == 200) {
                setState(R.drawable.dot_on, R.string.device_online, R.color.accent);
            } else if (code == 401) {
                setState(R.drawable.dot_bad, 0, R.color.danger, "token 无效");
            } else {
                setState(R.drawable.dot_bad, 0, R.color.danger, error != null ? "不可达" : ("HTTP " + code));
            }
            return;
        }
        if (!"status".equals(tag)) return;

        if (code == 200) {
            boolean online = Ui.bodyHasTrue(body, "online");
            setState(online ? R.drawable.dot_on : R.drawable.dot_off,
                     online ? R.string.device_online : R.string.device_offline,
                     online ? R.color.accent : R.color.text_dim);
        } else if (code == 404) {
            setState(R.drawable.dot_off, 0, R.color.text_dim, "服务端未启用 /status");
        } else if (code == 401) {
            setState(R.drawable.dot_bad, 0, R.color.danger, "token 无效");
        } else {
            setState(R.drawable.dot_bad, 0, R.color.danger, error != null ? "不可达" : ("HTTP " + code));
        }
    }

    private void setState(int dotRes, int strRes, int colorRes) {
        dot.setBackgroundResource(dotRes);
        if (strRes != 0) state.setText(strRes);
        state.setTextColor(app.getColor(colorRes));
    }

    private void setState(int dotRes, int strRes, int colorRes, String raw) {
        setState(dotRes, strRes, colorRes);
        if (strRes == 0 && raw != null) state.setText(raw);
    }
}
