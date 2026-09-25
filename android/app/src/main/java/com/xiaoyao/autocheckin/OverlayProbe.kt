package com.xiaoyao.autocheckin

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * 「后台启动 Activity」豁免探针 —— Android 12+ 定时签到的命门。
 *
 * ══ 为什么需要它（2026-09-25 真机实证，华为 JEF-AN00 / Android 12 / EMUI 14.2）══
 *
 * 定时触发时 App 在后台，「屏幕刚点亮 → 发起 deeplink 跳转」被系统直接丢弃：
 *
 *   W ActivityTaskManager: activity start fail: Background activity start
 *     callingPackage: com.xiaoyao.autocheckin
 *     isCallingUidForeground: false
 *     callingUidHasAnyVisibleWindow: false     ← 关键因子
 *     allowBackgroundActivityStart: false
 *   E ActivityTaskManager: activity start fail: Abort background activity starts from 10190
 *
 * 阴险之处：`startActivity()` 不抛异常、日志里照样打「已发起跳转」，
 * 页面却纹丝不动 —— 极容易被误判成坐标 / 控件 / 入口配置问题。
 *
 * ══ 为什么「只给悬浮窗权限」不够 ══
 *
 * 实测该机 `appops SYSTEM_ALERT_WINDOW = allow` 已生效，系统仍判
 * `allowBackgroundActivityStart=false`。也就是说：**光有权限不算数**。
 *
 * AOSP 的放行分支里有一条 BAL_ALLOW_VISIBLE_WINDOW：
 * 「调用方 uid 存在任何可见窗口 → 直接放行」。
 * 悬浮窗（TYPE_APPLICATION_OVERLAY）正是挂在调用方 uid 下的窗口，
 * 所以只要挂一个（哪怕 1×1 像素）悬浮窗，
 * `callingUidHasAnyVisibleWindow` 立刻从 false 变 true，跳转即被放行。
 *
 * 同理，这也解释了为什么「用 adb 手动测总是成功」——
 * am start 由 com.android.shell（uid 2000）发起，自带
 * START_ACTIVITIES_FROM_BACKGROUND 特权，压根不受这条限制约束：
 *
 *   D ActivityTaskManager: Background activity start allowed:
 *     START_ACTIVITIES_FROM_BACKGROUND permission granted for callingUid = 2000
 *     callingPackage = com.android.shell
 *
 * ══ 实现要点 ══
 *
 * - 尺寸 1×1 像素、半透明背景、不可触摸、不可获焦 —— 用户完全看不见、摸不到。
 * - `alpha` 必须是 1.0（非 0）：若为 0，WindowState.isFullyTransparent() 成立，
 *   系统可能不计入「可见窗口」，豁免就白做了。
 * - 与服务同生命周期：onServiceConnected 挂上，onUnbind/onDestroy 摘掉。
 * - 熄屏时窗口本身不可见，所以**必须在点亮屏幕之后**的跳转才受益，
 *   这正是 wakeScreen() 要先跑、并等屏幕真正 interactive 的原因。
 */
object OverlayProbe {

    private var windowManager: WindowManager? = null
    private var probeView: View? = null

    /** 探针是否已挂上（供日志/界面显示） */
    @Volatile
    var active: Boolean = false
        private set

    /**
     * 挂上探针窗口；重复调用是安全的。
     *
     * @return true 表示此刻探针处于生效状态
     */
    fun show(ctx: Context): Boolean {
        if (active && probeView != null) return true
        return try {
            val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val view = View(ctx).apply {
                // alpha 用 1/255 而非 0：既要肉眼不可见，又要保持「非全透明」。
                setBackgroundColor(Color.argb(1, 0, 0, 0))
            }

            val lp = WindowManager.LayoutParams(
                1, 1, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
                alpha = 1.0f
            }

            manager.addView(view, lp)
            windowManager = manager
            probeView = view
            active = true
            Logger.log(ctx, "后台跳转豁免探针已挂上（1×1 隐形悬浮窗）")
            true
        } catch (t: Throwable) {
            active = false
            // 最常见原因：悬浮窗权限没给。addView 会抛 BadTokenException / SecurityException。
            Logger.log(ctx, "⚠️ 探针挂载失败（${t.javaClass.simpleName}）：${t.message}")
            Logger.log(ctx, "   → 定时签到会因此打不开目标 App，请到「设置 → 应用 → FAFU签到 → 显示在其他应用上层」手动打开")
            false
        }
    }

    /** 摘掉探针窗口 */
    fun hide() {
        val v = probeView ?: return
        try {
            windowManager?.removeView(v)
        } catch (_: Throwable) {
            // 窗口可能随进程一起没了，忽略
        }
        probeView = null
        active = false
    }
}
