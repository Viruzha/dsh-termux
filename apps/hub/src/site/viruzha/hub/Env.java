package site.viruzha.hub;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** 运行时环境：把 assets/runtime 解压到应用私有目录，并提供各路径。 */
public final class Env {

    private static final String ASSET_ROOT = "runtime";

    private Env() { }

    public static File dir(Context c)      { return new File(c.getFilesDir(), "runtime"); }
    public static File node(Context c)     { return new File(dir(c), "bin/node"); }
    public static File rg(Context c)       { return new File(dir(c), "bin/rg"); }
    public static File libDir(Context c)   { return new File(dir(c), "lib"); }
    public static File dshRoot(Context c)  { return new File(c.getFilesDir(), "dsh"); }
    public static File home(Context c)     { return new File(c.getFilesDir(), "home"); }

    /** 是否已解压且可执行。 */
    public static boolean ready(Context c) {
        File n = node(c);
        return n.isFile() && n.canExecute()
            && new File(libDir(c), "libc++_shared.so").isFile();
    }

    /** DSH 本体是否已就位。 */
    public static boolean dshReady(Context c) {
        return new File(dshRoot(c), "lib/bin.js").isFile();
    }

    /**
     * DSH 包的位置。优先应用私有目录（一定能读），
     * 其次外部私有目录（targetSdk 28 的旧存储模式下可能需要存储权限）。
     */
    public static File bundleZip(Context c) {
        File internal = new File(c.getFilesDir(), "dsh-bundle.zip");
        if (internal.isFile()) return internal;
        File ext = c.getExternalFilesDir(null);
        if (ext != null) {
            File f = new File(ext, "dsh-bundle.zip");
            if (f.isFile()) return f;
        }
        return internal;   // 返回此路径用于提示
    }

    /** 解压 DSH 包到 filesDir/dsh。 */
    public static int extractZip(File zip, File dst) throws IOException {
        int count = 0;
        String root = dst.getCanonicalPath();
        ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip), 1 << 16));
        byte[] buf = new byte[128 * 1024];
        try {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                File out = new File(dst, e.getName());
                if (!out.getCanonicalPath().startsWith(root)) { zin.closeEntry(); continue; }
                if (e.isDirectory()) { out.mkdirs(); zin.closeEntry(); continue; }
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory()) parent.mkdirs();
                FileOutputStream fos = new FileOutputStream(out);
                try {
                    int n;
                    while ((n = zin.read(buf)) > 0) fos.write(buf, 0, n);
                } finally { fos.close(); }
                zin.closeEntry();
                count++;
            }
        } finally { zin.close(); }
        return count;
    }

    /** 解压 assets/runtime 到 filesDir/runtime。 */
    public static void extract(Context c) throws IOException {
        File dst = dir(c);
        copyTree(c.getAssets(), ASSET_ROOT, dst);
        boolean a = node(c).setExecutable(true, false);
        boolean b = rg(c).setExecutable(true, false);
        if (!a || !b) throw new IOException("无法设置执行权限");
    }

    private static void copyTree(AssetManager am, String path, File dst) throws IOException {
        String[] kids = am.list(path);
        if (kids == null || kids.length == 0) {
            copyFile(am, path, dst);
            return;
        }
        if (!dst.isDirectory() && !dst.mkdirs()) throw new IOException("无法创建目录 " + dst);
        for (String k : kids) copyTree(am, path + "/" + k, new File(dst, k));
    }

    private static void copyFile(AssetManager am, String path, File dst) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建目录 " + parent);
        }
        InputStream in = am.open(path);
        FileOutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[128 * 1024];
        try {
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            try { in.close(); } catch (IOException ignored) { }
            out.close();
        }
    }

    /** 运行时所需的环境变量：LD_LIBRARY_PATH 指到本目录，HOME 指到应用私有目录。 */
    public static String[] envArray(Context c) {
        return new String[] {
            "LD_LIBRARY_PATH=" + libDir(c).getAbsolutePath(),
            // node 的 OpenSSL 默认配置路径编译成了 Termux 的 $PREFIX，必须显式覆盖
            "OPENSSL_CONF=" + new File(dir(c), "etc/openssl.cnf").getAbsolutePath(),
            "HOME=" + home(c).getAbsolutePath(),
            "TMPDIR=" + new File(c.getCacheDir(), "tmp").getAbsolutePath(),
            "PATH=" + new File(dir(c), "bin").getAbsolutePath() + ":/system/bin",
            "LANG=C.UTF-8",
        };
    }
}
