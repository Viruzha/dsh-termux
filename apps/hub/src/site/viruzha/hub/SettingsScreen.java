package site.viruzha.hub;

import android.text.method.PasswordTransformationMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

/** 设置页：连接参数、设备信息、关于。 */
public class SettingsScreen extends Screen implements View.OnClickListener {

    private EditText endpoint, token, name, mac;
    private TextView saveMsg, about;

    public SettingsScreen(MainActivity app) { super(app); }

    @Override public View build() {
        root = LayoutInflater.from(app).inflate(R.layout.screen_settings, null);
        endpoint = (EditText) $(R.id.set_endpoint);
        token    = (EditText) $(R.id.set_token);
        name     = (EditText) $(R.id.set_name);
        mac      = (EditText) $(R.id.set_mac);
        saveMsg  = (TextView) $(R.id.save_msg);
        about    = (TextView) $(R.id.about_text);
        token.setTransformationMethod(new PasswordTransformationMethod());
        $(R.id.btn_save).setOnClickListener(this);
        about.setText(app.aboutText());
        return root;
    }

    @Override public void onShow() {
        endpoint.setText(app.prefs.endpoint());
        token.setText(app.prefs.token());
        name.setText(app.prefs.name());
        mac.setText(app.prefs.mac());
        saveMsg.setText("");
    }

    @Override public void onClick(View v) {
        if (v.getId() != R.id.btn_save) return;
        String ep = endpoint.getText().toString().trim();
        String tk = token.getText().toString().trim();
        String nm = name.getText().toString().trim();
        String mc = mac.getText().toString().trim();
        if (ep.length() == 0 || tk.length() == 0) { saveMsg.setText("地址和 token 不能为空"); return; }
        app.prefs.setEndpoint(ep);
        app.prefs.setToken(tk);
        if (nm.length() > 0) app.prefs.setName(nm);
        if (mc.length() > 0) app.prefs.setMac(mc);
        saveMsg.setText(app.getString(R.string.saved));
        // 其它页在 onShow 时会重新读取 prefs，无需在这里刷新
    }
}
