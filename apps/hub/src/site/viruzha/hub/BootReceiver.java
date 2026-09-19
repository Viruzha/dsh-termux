package site.viruzha.hub;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

/**
 * 开机自启：收到开机广播后，若用户之前开着悬浮按钮，就把它拉起来。
 *
 * 这是 Android **官方支持**的自启方式。为什么不用另外两条路：
 *   - 设备管理员（DevicePolicyManager）：它是企业 MDM 用的（远程锁定/擦除/密码策略），
 *     与自启毫无关系，还会让卸载必须先取消激活 —— 纯负担。
 *   - 无障碍服务：确实极难被系统回收，但要用户手动开启、系统会弹安全警告，
 *     且用它做保活属于滥用（Google Play 政策亦不允许）。
 *
 * ⚠️ MIUI / EMUI 等国产系统另有「自启动」白名单，不打开的话系统会直接拦掉本广播。
 * 那一步只能由用户在系统设置里手动完成，任何 App 都绕不过。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override public void onReceive(Context c, Intent intent) {
        String action = (intent == null) ? null : intent.getAction();
        Log.i("HUB", "BootReceiver 收到广播: " + action);
        if (action == null) return;

        // 部分厂商（小米/OPPO/vivo/HTC）用自己的快速开机广播
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action);
        if (!boot) return;

        PunchStore store = new PunchStore(c);
        if (!store.enabled()) {
            Log.i("HUB", "用户没开悬浮按钮，跳过");
            return;
        }
        if (!Settings.canDrawOverlays(c)) {
            Log.w("HUB", "悬浮窗权限已丢失，跳过自启");
            return;
        }
        PunchService.start(c);
        Log.i("HUB", "已在开机后拉起 PunchService");
    }
}
