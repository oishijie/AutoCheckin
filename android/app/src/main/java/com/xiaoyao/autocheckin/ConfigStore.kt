package com.xiaoyao.autocheckin

import android.content.Context

/** 配置持久化（SharedPreferences） */
object ConfigStore {
    private const val SP = "checkin_cfg"

    private const val K_INTENT = "intent_url"
    private const val K_STEPS = "steps"
    private const val K_PKG = "target_pkg"
    private const val K_HOUR = "hour"
    private const val K_MINUTE = "minute"
    private const val K_RETRY = "retry"
    private const val K_ENABLED = "enabled"
    private const val K_DEBUG = "debug_mode"

    /*
     * 以下入口与目标均由真机侦察确定（2026-09-25，华为 JEF-AN00 实测），
     * 用户未自行修改时直接生效，无需手填。
     */

    /**
     * 主页 scheme —— **兜底入口**。进入后停在「主页」Tab，
     * 必须再由步骤表切到「消息」Tab 才能看到签到入口。
     */
    const val HOME_INTENT = "hwwelink://cn.edu.fafu.iportal"

    private const val DEFAULT_PKG = "cn.edu.fafu.iportal"

    /**
     * 扫码 deeplink —— **默认主入口**（用户 2026-09-25 拍定「扫码优先」）。
     * 真机实测可直达通知中心（CategoryChatActivity），不必再切「消息」Tab，
     * 比主页 scheme 少一步、更稳。
     * 注意：qrcode 是 1024 字节加密数据，长期有效性未知；
     * 一旦失效，launchEntry 会自动降级到 HOME_INTENT。
     */
    const val SCAN_INTENT =
        "hwwelink://welink.huawei.com/qr?qrcode=lB57ytc6CzbUaN5U5eVvDZAeYhKTD1jzoS4bM%2FW13A4XmsB8jOWxCaVP8x0UWp9CStVpl0yr%2FMs9u48TC3ThTdebfTOApYWAvFGLy9p7Ph4SIXNfgjDwkgp%2BYfmVs4BoTtkbMr8bdFfNXjiQManrAVwka1JCr4SBkBbPHzq8CVkhfeSmNPWEq5GBzJsdop%2BI%2B0aZHW5EO4reiR1%2F3QJwE2J2HqNbBSIbw2T%2FCDh4QTUBVu11rFviD8NRSqEWb7h9uBZ%2FU48Px%2Berr6IwuCABIqfeY8KEcNJlrxwo57fIQ8rPCXNQr4maKHdg%2BKus3WUtV391zacrVHFoQhMoli8PZ22%2FzgYovMJ%2B1DkIS0MtmjEZkc6WdQqADP7IDFIBeL7c9xcd%2Fl1EBGXB7JwGqJvbAOLT18Omn4ROWAlxO6aq5KMvg31BRUTo6E8vRnP2AHqrp1VTquCNx%2FjvEJGOU%2Bd8zSaoJ9x%2BbE3G9HyJ5Z%2BYzurtGbW938QCHLUkV%2FIZ9EZCb4TIbw0Ktk9408FXxg%2BkjDXT%2BCrk7xUEpIplV0VgDB1HMhL4YcW6n8MXfOjeVro%2FG07xGYMonbospgV%2FB2Z1ZvxHDZewVHsjaIJknC8YNzoZrP6LJgtYlDcnyiiaj3mr2Yq4ICnIo3uJgFGOxw3dyfG0rabvWdFMVrJrZLWbNmo%2FlMgtwL9PWkSWGdov45zNylha62HLaGhXNUV9egbTIQAaEFHjSf4unIeJLxiQzEaUezht7mL1L%2BoY9fbQSqWISYPecGq0V7IdEjafR9Tg7qZ%2F8lOUeD3W4PiiUHh4%2Ff4SwXgZM8gMKN7E%2BK0em2fkEUoPNELV1IAcS3DOWYD9wRqyiaxeH%2FYuoYKRBblHNYQ2Z7EX1TO90Vhd%2F2wLyM%2Fij3op0H7xiGthhmTR5z3JZ3Ex6rMlX5K1673k43xRlGsKEzGICQeEHfS6s4109z3PhFg9dI32dmeGGN51qPqaljdh9TMDUfEwWMF1cpv9%2BJgHiYmO1dBa6BlE3k8iFFjvIfMFzr62PO6%2B0rAX6gyZwmETlWcpfJaLNyg4VZLYw2xzZbeMT0Z439SfHMgDyvIzsE0RvEx1J3azlUSbc1yif7gQengnQasv7NePraIXn3xsQJmQsK9LdUqn9m07BVls3bbxFmm7WhSil44F4vtJ6B9QyCVRI59rQYMFxbUgqnbVurV6csIhZc8imOVNRqktzB0aAW48nybhq7eRT6XJJdAHvYVO48h1RePmp0Mi7UeGxONY07XhX%2FtuYpzMQYS4EWaH5Om3HEEnirbqb9bExHX5rSjh2g4%2F%2Bb1CT5xDRg08ebNAVT7MlUd9oib%2FtXMUt5bsLu3WYiuOFsDeggevLQ%3D%3D"

    private fun sp(c: Context) = c.getSharedPreferences(SP, Context.MODE_PRIVATE)

    fun intentUrl(c: Context): String = sp(c).getString(K_INTENT, SCAN_INTENT) ?: SCAN_INTENT
    fun stepsText(c: Context): String = sp(c).getString(K_STEPS, Step.DEFAULT) ?: Step.DEFAULT
    fun targetPkg(c: Context): String = sp(c).getString(K_PKG, DEFAULT_PKG) ?: DEFAULT_PKG

    // 签到窗口 20:00–23:00，默认 20:30 触发（留出提醒推送的余量）
    fun hour(c: Context): Int = sp(c).getInt(K_HOUR, 20)
    fun minute(c: Context): Int = sp(c).getInt(K_MINUTE, 30)
    // 签到窗口有 3 小时（20:00-23:00），多留几次重试容错：
    // H5 页面偶发卡在「加载中…」，实测多试几次就能成功。
    fun retry(c: Context): Int = sp(c).getInt(K_RETRY, 3)
    fun enabled(c: Context): Boolean = sp(c).getBoolean(K_ENABLED, false)

    /**
     * 调试模式 —— 专治「一直重试、日志刷屏、现场留不住」。
     *
     * 开启后：整轮只跑 1 次（不重试）；签到页卡在「加载中」也不反复 BACK 重进；
     * 一旦某步失败就停在那儿，屏幕保持原样不动 ——
     * 这样才能立刻 dump 控件树，看清当时到底是什么界面。
     * 关闭则恢复「多轮重试 + 自动回收现场」的正常行为。
     */
    fun debugMode(c: Context): Boolean = sp(c).getBoolean(K_DEBUG, false)
    fun setDebugMode(c: Context, on: Boolean) {
        sp(c).edit().putBoolean(K_DEBUG, on).apply()
    }

    /** 解析出真正可执行的步骤列表（自动过滤注释与空行） */
    fun steps(c: Context): List<Step> =
        stepsText(c).lines().mapNotNull { Step.decode(it) }

    /**
     * 丢掉已保存的步骤表，回落到代码内置的 [Step.DEFAULT]。
     *
     * 存在的理由：步骤表是「保存进 SP」的，一旦存过，之后升级 APK 里
     * 新写的默认值就再也看不到 —— 调试期每次改默认步骤都会踩这个坑。
     * 触发：adb shell am start -n com.xiaoyao.autocheckin/.MainActivity --ez resetSteps true
     */
    fun resetSteps(c: Context) {
        sp(c).edit().remove(K_STEPS).apply()
    }

    fun save(
        c: Context,
        intentUrl: String,
        steps: String,
        pkg: String,
        hour: Int,
        minute: Int,
        retry: Int,
        enabled: Boolean
    ) {
        sp(c).edit()
            .putString(K_INTENT, intentUrl)
            .putString(K_STEPS, steps)
            .putString(K_PKG, pkg)
            .putInt(K_HOUR, hour)
            .putInt(K_MINUTE, minute)
            .putInt(K_RETRY, retry)
            .putBoolean(K_ENABLED, enabled)
            .apply()
    }
}
