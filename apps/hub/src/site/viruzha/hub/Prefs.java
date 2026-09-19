package site.viruzha.hub;

import android.content.Context;
import android.content.SharedPreferences;

/** 应用配置：接口地址、token、设备信息、上次唤醒记录。 */
public class Prefs {
    private static final String FILE = "wake";
    private final Context ctx;
    private final SharedPreferences sp;

    public Prefs(Context c) {
        ctx = c;
        sp = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private String def(int res) { return ctx.getString(res); }

    public String endpoint()  { return sp.getString("endpoint", def(R.string.endpoint)); }
    public String token()     { return sp.getString("token", def(R.string.default_token)); }
    public String name()      { return sp.getString("name", def(R.string.default_name)); }
    public String mac()       { return sp.getString("mac", def(R.string.default_mac)); }

    public void setEndpoint(String v) { sp.edit().putString("endpoint", v).apply(); }
    public void setToken(String v)    { sp.edit().putString("token", v).apply(); }
    public void setName(String v)     { sp.edit().putString("name", v).apply(); }
    public void setMac(String v)      { sp.edit().putString("mac", v).apply(); }

    public String lastTime()   { return sp.getString("last_time", null); }
    public String lastDetail() { return sp.getString("last_detail", null); }
    public void setLast(String time, String detail) {
        sp.edit().putString("last_time", time).putString("last_detail", detail).apply();
    }

    /** 打码显示，避免 token 直接暴露在界面上。 */
    public static String mask(String t) {
        if (t == null || t.length() <= 10) return "••••••••";
        return t.substring(0, 4) + "····" + t.substring(t.length() - 4);
    }
}
