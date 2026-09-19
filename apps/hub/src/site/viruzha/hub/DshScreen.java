package site.viruzha.hub;

import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DSH 功能页：在应用私有目录里跑起内置的 node 运行时，启动 DSH web，
 * 抓出带 token 的地址后把前端直接显示在本页的 WebView 里。
 *
 * 固定端口 + --trusted-host 是必须的：DSH 有 /api 浏览器信任栅栏，
 * 用随机端口会导致前端白屏。
 */
public class DshScreen extends Screen implements View.OnClickListener, Proc.Sink {

    static final int PORT = 13080;

    private static final Pattern URL_RE =
            Pattern.compile("http://127\\.0\\.0\\.1:\\d+/\\?token=[A-Za-z0-9_\\-]+");

    private TextView state, action, log;
    private ScrollView bootScroll;
    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Process server;
    private boolean busy;
    private int logLines;
    private String url;

    public DshScreen(MainActivity app) { super(app); }

    @Override public View build() {
        root = LayoutInflater.from(app).inflate(R.layout.screen_dsh, null);
        state      = (TextView) $(R.id.dsh_state);
        action     = (TextView) $(R.id.dsh_action);
        log        = (TextView) $(R.id.dsh_log);
        bootScroll = (ScrollView) $(R.id.dsh_log_scroll);
        web        = (WebView) $(R.id.dsh_web);
        $(R.id.dsh_start).setOnClickListener(this);
        setupWeb();
        return root;
    }

    private void setupWeb() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new ViewDiag());
        web.setWebChromeClient(new ChromeDiag());
    }

    @Override public void onClick(View v) {
        if (v.getId() == R.id.dsh_start) startDsh();
    }

    /** 服务是否已在运行（切 Tab 回来时不重复启动）。 */
    boolean running() { return server != null && url != null; }

    void startDsh() {
        if (busy) return;
        busy = true;
        logLines = 0;
        log.setText("");
        action.setText("");
        setState(R.drawable.dot_off, app.getString(R.string.dsh_idle), R.color.text_dim);
        new Thread(new BootTask(this)).start();
    }

    void launch() throws IOException {
        File h = Env.home(app);
        h.mkdirs();
        new File(h, ".dsh").mkdirs();
        File tmp = new File(app.getCacheDir(), "tmp");
        if (!tmp.isDirectory() && !tmp.mkdirs()) throw new IOException("无法创建 TMPDIR " + tmp);

        File bin = new File(Env.dshRoot(app), "lib/bin.js");
        String[] cmd = {
                Env.node(app).getAbsolutePath(),
                "--expose-internals",
                bin.getAbsolutePath(),
                "web", "--no-open",
                "--port", String.valueOf(PORT),
                "--trusted-host", "127.0.0.1:" + PORT
        };
        logLine("$ " + String.join(" ", cmd));
        server = Proc.start(cmd, Env.envArray(app), h, this);
    }

    // ---- 子进程输出 ---------------------------------------------------------
    @Override public void line(String s) {
        logLine(s);
        Matcher m = URL_RE.matcher(s);
        if (m.find()) ui.post(new ShowWeb(this, m.group()));
    }

    @Override public void exited(int code) {
        logLine("[进程退出 " + code + "]");
        ui.post(new Exit(this, code));
    }

    // ---- UI ----------------------------------------------------------------
    void showWeb(String u) {
        url = u;
        busy = false;
        setState(R.drawable.dot_on, app.getString(R.string.dsh_ok), R.color.accent);
        action.setText("已在应用内运行");
        web.loadUrl(u);
        bootScroll.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
    }

    void onExit(int code) {
        busy = false;
        server = null;
        url = null;
        setState(R.drawable.dot_bad, app.getString(R.string.dsh_bad), R.color.danger);
        action.setText("DSH 已停止（exit " + code + "）");
        web.setVisibility(View.GONE);
        bootScroll.setVisibility(View.VISIBLE);
    }

    void step(String s) {
        ui.post(new SetState(this, R.drawable.dot_off, s, R.color.accent));
        logLine("── " + s);
    }

    void fail(String s) {
        busy = false;
        ui.post(new SetState(this, R.drawable.dot_bad, s, R.color.danger));
        logLine("!! " + s);
    }

    void setState(int dotRes, String text, int colorRes) {
        ((View) $(R.id.dsh_dot)).setBackgroundResource(dotRes);
        state.setText(text);
        state.setTextColor(app.getColor(colorRes));
    }

    void logLine(String s) { ui.post(new AppendLog(this, s)); }

    // ---- 具名嵌套类（d8 3.3.20 不能处理匿名内部类）--------------------------
    static class BootTask implements Runnable {
        private final DshScreen s;
        BootTask(DshScreen s) { this.s = s; }

        @Override public void run() {
            try {
                s.step("检查运行时");
                if (!Env.ready(s.app)) {
                    s.step("首次运行：解压运行时（约 95MB）");
                    Env.extract(s.app);
                }
                s.step("验证 node");
                File h = Env.home(s.app);
                h.mkdirs();
                String v = Proc.capture(new String[] { Env.node(s.app).getAbsolutePath(), "-v" },
                        Env.envArray(s.app), h, 30000);
                s.logLine("node -v → " + v);
                if (v == null || !v.startsWith("v")) { s.fail("无法执行 node：" + v); return; }

                if (!Env.dshReady(s.app)) {
                    File zip = Env.bundleZip(s.app);
                    if (zip == null || !zip.isFile()) {
                        s.fail("未找到 DSH 本体。请把 dsh-bundle.zip 放到：\n"
                                + Env.dshRoot(s.app).getParentFile().getAbsolutePath());
                        return;
                    }
                    s.step("解压 DSH 本体");
                    long t0 = System.currentTimeMillis();
                    int n = Env.extractZip(zip, Env.dshRoot(s.app));
                    s.logLine("解压 " + n + " 个文件，耗时 "
                            + ((System.currentTimeMillis() - t0) / 1000) + " 秒");
                }

                s.step("启动 DSH web（端口 " + PORT + "）");
                s.launch();
            } catch (Throwable t) {
                s.fail(t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
    }

    static class SetState implements Runnable {
        private final DshScreen s; private final int d; private final String t; private final int c;
        SetState(DshScreen s, int d, String t, int c) { this.s = s; this.d = d; this.t = t; this.c = c; }
        @Override public void run() { s.setState(d, t, c); }
    }

    static class AppendLog implements Runnable {
        private final DshScreen s; private final String line;
        AppendLog(DshScreen s, String line) { this.s = s; this.line = line; }
        @Override public void run() {
            s.log.append(line + "\n");
            s.logLines++;
            if (s.logLines > 300) {
                CharSequence t = s.log.getText();
                s.log.setText(t.subSequence(t.length() / 2, t.length()));
                s.logLines = 150;
            }
        }
    }

    static class ShowWeb implements Runnable {
        private final DshScreen s; private final String u;
        ShowWeb(DshScreen s, String u) { this.s = s; this.u = u; }
        @Override public void run() { s.showWeb(u); }
    }

    static class Exit implements Runnable {
        private final DshScreen s; private final int code;
        Exit(DshScreen s, int code) { this.s = s; this.code = code; }
        @Override public void run() { s.onExit(code); }
    }

    static class ViewDiag extends WebViewClient {
        @Override public void onReceivedError(WebView v, int code, String desc, String url) {
            android.util.Log.e("HUB", "WebView 错误 " + code + " " + desc + " @ " + url);
        }
        @Override public void onPageFinished(WebView v, String u) {
            android.util.Log.i("HUB", "页面加载完成: " + u);
        }
    }

    static class ChromeDiag extends WebChromeClient {
        @Override public boolean onConsoleMessage(ConsoleMessage m) {
            android.util.Log.w("HUB", "JS: " + m.message() + " @" + m.lineNumber());
            return true;
        }
    }
}
