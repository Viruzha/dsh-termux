package site.viruzha.hub;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 打卡数据层。
 *
 * 「一天」的边界是**凌晨 5:00**：把时间往回挪 5 小时再取日期，
 * 这样熬夜到凌晨 2 点仍算前一天，不会被意外重置。
 *
 * 这个判定是**纯计算**的，不依赖定时器 —— 所以即使 App 没运行、
 * 手机重启过，下次打开时判定依然正确。
 */
public class PunchStore {

    private static final String FILE = "punch";
    private static final String KEY_LOGS = "logs";   // 每行： yyyy-MM-dd=epochMillis
    private static final long SHIFT_MS = 5L * 3600 * 1000;   // 凌晨 5 点
    private static final int MAX_KEEP = 90;

    private final SharedPreferences sp;

    public PunchStore(Context c) {
        sp = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static SimpleDateFormat dayFmt() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    }

    /** 某个时刻属于哪个「打卡日」。 */
    public static String punchDay(long now) {
        return dayFmt().format(new Date(now - SHIFT_MS));
    }

    /**
     * 距离下一次刷新（下一个凌晨 5 点）还有多少毫秒。
     *
     * 必须用日历字段定位 5 点，**不能**用「epoch 毫秒 / 86400000」：
     * 那是 UTC 的午夜，在东八区相当于本地 08:00，算出来的边界会偏 8 小时。
     */
    public static long msUntilReset(long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, 5);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= now) c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis() - now;
    }

    /** 解析出的记录（新的在前）。 */
    public static class Entry {
        public final String day;
        public final long time;
        public Entry(String day, long time) { this.day = day; this.time = time; }
    }

    public List<Entry> all() {
        List<Entry> out = new ArrayList<Entry>();
        String raw = sp.getString(KEY_LOGS, "");
        if (raw == null || raw.length() == 0) return out;
        for (String line : raw.split("\n")) {
            int i = line.indexOf('=');
            if (i <= 0) continue;
            try {
                out.add(new Entry(line.substring(0, i), Long.parseLong(line.substring(i + 1))));
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    /** 今天（按 5 点边界）的打卡时间；未打卡返回 0。 */
    public long today(long now) {
        String d = punchDay(now);
        for (Entry e : all()) if (e.day.equals(d)) return e.time;
        return 0L;
    }

    public boolean isPunched(long now) { return today(now) > 0L; }

    /**
     * 打卡。若当天已打卡则**保持首次时间不变**，返回 false。
     */
    public boolean punch(long now) {
        if (isPunched(now)) return false;
        String d = punchDay(now);
        StringBuilder sb = new StringBuilder();
        sb.append(d).append('=').append(now);
        int n = 0;
        for (Entry e : all()) {
            if (n++ >= MAX_KEEP) break;
            sb.append('\n').append(e.day).append('=').append(e.time);
        }
        sp.edit().putString(KEY_LOGS, sb.toString()).apply();
        return true;
    }

    /** 悬浮框位置记忆（-1 表示尚未设定）。 */
    public int bubbleX() { return sp.getInt("bubble_x", -1); }
    public int bubbleY() { return sp.getInt("bubble_y", -1); }
    public void saveBubblePos(int x, int y) {
        sp.edit().putInt("bubble_x", x).putInt("bubble_y", y).apply();
    }

    /**
     * 用户是否希望悬浮按钮处于开启状态。
     *
     * 与「服务此刻在不在跑」是两回事：服务可能被系统回收，
     * 开机广播要靠这个标志判断「该不该自动拉起来」。
     * 只有用户在界面上主动关闭时才置 false。
     */
    public boolean enabled() { return sp.getBoolean("overlay_enabled", false); }
    public void setEnabled(boolean on) { sp.edit().putBoolean("overlay_enabled", on).apply(); }

    /** 仅用于调试/设置页：清空记录。 */
    public void clear() { sp.edit().remove(KEY_LOGS).apply(); }

    public static String hhmm(long t) { return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(t)); }
    public static String hhmmss(long t) { return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(t)); }
    public static String friendly(String day) {
        // 2026-09-19 -> 09-19 周五
        try {
            Date d = dayFmt().parse(day);
            return new SimpleDateFormat("MM-dd EEE", Locale.CHINA).format(d);
        } catch (Exception e) { return day; }
    }
}
