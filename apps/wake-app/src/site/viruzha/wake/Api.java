package site.viruzha.wake;

import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 极简网络层：一次 GET，回调在主线程。
 * 刻意不使用匿名内部类 —— Termux 自带的 d8 3.3.20 处理 JDK 21 编译出的匿名类会 NPE。
 */
public final class Api {

    public interface Callback {
        void onResult(String tag, String body, int code, String error, long ms);
    }

    private static final Handler UI = new Handler(Looper.getMainLooper());

    private Api() { }

    public static void get(String url, String tag, Callback cb) {
        new Thread(new Worker(url, tag, cb)).start();
    }

    /** 拼出带 token 的唤醒地址。 */
    public static String wakeUrl(String endpoint, String token) {
        String sep = endpoint.contains("?") ? "&" : "?";
        return endpoint + sep + "token=" + token;
    }

    static class Worker implements Runnable {
        private final String url, tag;
        private final Callback cb;

        Worker(String url, String tag, Callback cb) {
            this.url = url; this.tag = tag; this.cb = cb;
        }

        @Override public void run() {
            long t0 = System.currentTimeMillis();
            int code = -1;
            String body = null, err = null;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(25000);
                c.setRequestMethod("GET");
                c.setRequestProperty("Accept", "application/json");
                code = c.getResponseCode();
                body = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            } catch (Exception e) {
                err = e.getClass().getSimpleName() + ": " + e.getMessage();
            } finally {
                if (c != null) c.disconnect();
            }
            long ms = System.currentTimeMillis() - t0;
            UI.post(new Deliver(cb, tag, body, code, err, ms));
        }
    }

    static class Deliver implements Runnable {
        private final Callback cb; private final String tag, body, err;
        private final int code; private final long ms;
        Deliver(Callback cb, String tag, String body, int code, String err, long ms) {
            this.cb = cb; this.tag = tag; this.body = body; this.code = code; this.err = err; this.ms = ms;
        }
        @Override public void run() { cb.onResult(tag, body, code, err, ms); }
    }

    static String read(InputStream in) {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String l;
            while ((l = r.readLine()) != null) sb.append(l);
            r.close();
        } catch (Exception ignored) { }
        return sb.toString();
    }
}
