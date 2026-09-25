package com.xiaoyao.autocheckin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 定时闹钟触发点：唤醒常驻的无障碍服务执行签到，并顺延排下一次 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // once=true 是调试用的「模拟定时」，跑完后不重排，避免影响每日定时
        val once = intent?.getBooleanExtra(Scheduler.EXTRA_ONCE, false) == true
        Logger.log(context, if (once) "⏰ 模拟定时触发" else "⏰ 定时触发")

        val svc = CheckinAccessibilityService.instance
        if (svc == null) {
            Logger.log(context, "❌ 无障碍服务未连接，无法执行（请检查是否被系统关闭）")
        } else {
            svc.runAsync(if (once) "模拟定时" else "定时任务")
        }

        // 无论本次是否成功，都重排下一次，保证每天都会触发
        if (!once && ConfigStore.enabled(context)) {
            Scheduler.scheduleDaily(context, ConfigStore.hour(context), ConfigStore.minute(context))
        }
    }
}
