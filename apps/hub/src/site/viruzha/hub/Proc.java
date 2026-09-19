package site.viruzha.hub;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Map;

/** 进程封装：启动子进程并把输出按行回调（stderr 合并进 stdout）。 */
public final class Proc {

    public interface Sink {
        void line(String s);
        void exited(int code);
    }

    private Proc() { }

    public static Process start(String[] cmd, String[] env, File dir, Sink sink) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (dir != null) {
            if (!dir.isDirectory()) dir.mkdirs();
            pb.directory(dir);
        }
        if (env != null) {
            Map<String, String> e = pb.environment();
            for (String kv : env) {
                int i = kv.indexOf('=');
                if (i > 0) e.put(kv.substring(0, i), kv.substring(i + 1));
            }
        }
        pb.redirectErrorStream(true);
        Process p = pb.start();
        new Thread(new Pump(p, sink)).start();
        return p;
    }

    /** 跑一个短命令并返回它在超时内的全部输出。 */
    public static String capture(String[] cmd, String[] env, File dir, long timeoutMs) {
        StringBuilder sb = new StringBuilder();
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (dir != null) {
                if (!dir.isDirectory()) dir.mkdirs();   // 工作目录不存在会报 error=2
                pb.directory(dir);
            }
            if (env != null) {
                Map<String, String> e = pb.environment();
                for (String kv : env) {
                    int i = kv.indexOf('=');
                    if (i > 0) e.put(kv.substring(0, i), kv.substring(i + 1));
                }
            }
            pb.redirectErrorStream(true);
            p = pb.start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
            r.close();
            final Process fp = p;
            Thread t = new Thread(new Waiter(fp));
            t.start();
            t.join(timeoutMs);
            if (t.isAlive()) { p.destroy(); sb.append("[超时]"); }
            else sb.append("[exit ").append(p.exitValue()).append(']');
        } catch (Exception ex) {
            sb.append("异常: ").append(ex.getClass().getSimpleName()).append(": ").append(ex.getMessage());
            if (p != null) p.destroy();
        }
        return sb.toString().trim();
    }

    static class Waiter implements Runnable {
        private final Process p;
        Waiter(Process p) { this.p = p; }
        @Override public void run() { try { p.waitFor(); } catch (InterruptedException ignored) { } }
    }

    static class Pump implements Runnable {
        private final Process p;
        private final Sink sink;
        Pump(Process p, Sink sink) { this.p = p; this.sink = sink; }
        @Override public void run() {
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                String l;
                while ((l = r.readLine()) != null) sink.line(l);
                r.close();
            } catch (Exception ignored) {
            } finally {
                try { sink.exited(p.waitFor()); } catch (Exception ignored) { }
            }
        }
    }
}
