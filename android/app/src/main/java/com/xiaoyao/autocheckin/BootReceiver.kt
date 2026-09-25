package com.xiaoyao.autocheckin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机自启：重新注册每日定时（AlarmManager 的定时在重启后会丢失） */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (ConfigStore.enabled(context)) {
            Logger.log(context, "开机完成，重新注册每日定时")
            Scheduler.scheduleDaily(context, ConfigStore.hour(context), ConfigStore.minute(context))
        }
    }
}
