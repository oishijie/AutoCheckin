package com.xiaoyao.autocheckin

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 每日定时调度（AlarmManager 精确闹钟） */
object Scheduler {
    private const val REQ = 1001
    private const val REQ_ONCE = 1002
    const val ACTION_ALARM = "com.xiaoyao.autocheckin.ALARM"
    const val EXTRA_ONCE = "once"

    private fun pending(ctx: Context): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_ALARM)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(ctx, REQ, i, flags)
    }

    private fun pendingOnce(ctx: Context): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java)
            .setAction(ACTION_ALARM)
            .putExtra(EXTRA_ONCE, true)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(ctx, REQ_ONCE, i, flags)
    }

    /** 计算下一次触发时刻：今天该时刻已过则顺延到明天 */
    private fun nextTrigger(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (!target.after(now)) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis
    }

    fun scheduleDaily(ctx: Context, hour: Int, minute: Int) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = nextTrigger(hour, minute)
        val p = pending(ctx)

        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
            Logger.log(
                ctx,
                "已设定每日 ${pad(hour)}:${pad(minute)} 定时（下次触发 " +
                        "${SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(at))}）"
            )
        } catch (t: Throwable) {
            // 部分系统不允许精确闹钟，降级为非精确但仍可在息屏下触发
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
            Logger.log(ctx, "精确闹钟不可用（${t.message}），已降级为非精确定时")
        }
    }

    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(ctx))
        Logger.log(ctx, "已取消定时任务")
    }

    /**
     * 一次性「模拟定时」：N 秒后触发，走的是与每日定时**完全相同**的路径
     * （AlarmManager → AlarmReceiver → 无障碍服务），只是不重排下一次。
     *
     * ══ 为什么必须有这个入口 ══
     * 定时签到的真实现场是「App 在后台 + 屏幕熄灭」，而手动点按钮或用
     * `am start` 触发都会把 App 拉到前台 —— 于是落进 Android 的 BAL 宽限期
     * （应用刚离开前台的短时间内允许启动 Activity），把真正的问题掩盖掉。
     * 2026-09-25 就是这么被坑了一整天：手动测试次次成功，定时触发次次失败。
     * 只有走闹钟才能真正复现。
     *
     * 用法（排好后 App 会自己退到后台，请接着熄屏等待）：
     *   adb shell am start -n com.xiaoyao.autocheckin/.MainActivity --ei onceIn 60
     *
     * ⚠️ 必须是 `--ei`（int extra）。写成 `--ez`（boolean）时 "60" 会被解析成
     *    Boolean.parseBoolean("60") = false，getIntExtra 拿到 0，函数直接静默 return，
     *    现象是「命令执行了、屏幕也亮了，却什么也没发生」—— 别问怎么知道的。
     */
    fun scheduleOnce(ctx: Context, seconds: Int) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = System.currentTimeMillis() + seconds * 1000L
        val p = pendingOnce(ctx)
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        } catch (t: Throwable) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        }
        Logger.log(
            ctx,
            "已设定【模拟定时】${seconds} 秒后触发（${
                SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(at))
            }）—— 现在可以熄屏了"
        )
    }

    private fun pad(v: Int) = if (v < 10) "0$v" else "$v"
}
