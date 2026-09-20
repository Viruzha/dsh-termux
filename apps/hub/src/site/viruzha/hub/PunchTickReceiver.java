package site.viruzha.hub;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 打卡状态的「重新计算」触发器。
 *
 * 为什么需要它：{@link PunchBubble#refresh()} 不是自更新的 —— 它只在被调用时
 * 才重新读数据。而「打卡日」是按**凌晨 5 点**换的，过了 5 点后
 * `store.today()` 会变成 0，但没人去重新算，悬浮框就一直停在旧时间上。
 *
 * 三种触发来源：
 *   1. 凌晨 5 点的 AlarmManager 闹钟（见 PunchService.scheduleReset）
 *   2. 亮屏 / 解锁（动态注册，设备醒来时正好是用户要看手机的时候）
 *   3. 系统时间或时区被改动
 *
 * 本类同时用于显式 PendingIntent（闹钟）和动态注册（亮屏），
 * 两种情况都只需「刷新 + 重排下一次闹钟」。
 */
public class PunchTickReceiver extends BroadcastReceiver {

    @Override public void onReceive(Context c, Intent intent) {
        String action = (intent == null) ? null : intent.getAction();
        Log.i("HUB", "PunchTickReceiver 触发: " + action);
        PunchService.refreshNow();
        PunchService.scheduleReset(c);   // 幂等：重新排下一次 5 点
    }
}
