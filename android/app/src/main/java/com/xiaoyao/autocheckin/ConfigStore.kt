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

    /** 最近一次「点过提交」的日期（yyyy-MM-dd）。见 [markSubmitted]。 */
    private const val K_SUBMIT_DATE = "submit_date"

    /** 最近一次【成功识别到快门】的坐标，存的是比例（"xRatio,yRatio"）。见 [saveShutterPoint]。 */
    private const val K_SHUTTER_POINT = "shutter_point"

    // ---- 结果推送（微信） ----
    private const val K_PUSH_ON = "push_on"
    private const val K_PUSH_URL = "push_url"
    private const val K_PUSH_TOKEN = "push_token"
    private const val K_PUSH_USER = "push_user"

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
     * 开启后：整轮只跑 1 次（不重试）—— 收敛的是【外层整轮重来】
     * （会冷启动目标 App、重走 deeplink、把现场清空），那才是真正刷屏的部分。
     * 而步骤内部的「BACK 重进 + 冷启动」容错【照常生效】：签到页卡「加载中」
     * 是常态，砍掉这个等于提前宣布失败（2026-09-25 真机踩过这个坑）。
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

    // ==================== 提交守卫（一天只能签一次） ====================
    //
    // 为什么需要它：整个流程【会重跑整轮】（⑱ 等成功弹窗超时 → 判失败 → 冷启动重来）。
    // 而最危险的情形是「H5 其实已受理、只是弹窗出现得慢」—— 这时已经签上了，
    // 判失败重来就会对着同一天第二次点提交，拿唯一一次机会冒险。
    //
    // 做法：把「点过提交」的【日期】落盘。重跑时若发现今天已点过，引擎就跳过提交步。
    // 只存日期（yyyy-MM-dd）不存时间戳，跨天自动失效，不需要额外的清理任务。

    /** 今天，yyyy-MM-dd。零依赖：用 framework 自带的 SimpleDateFormat。 */
    fun todayStr(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    /** 最近一次「点过提交」的日期；从未点过返回 null。 */
    fun submitDate(c: Context): String? = sp(c).getString(K_SUBMIT_DATE, null)

    /** 今天是否已经点过提交。 */
    fun submittedToday(c: Context): Boolean = submitDate(c) == todayStr()

    /**
     * 落盘「今天已点过提交」。
     *
     * ⚠️ 必须在【点击派发成功后立刻】调用，不能等 ⑱ 的结果 ——
     *    ⑱ 等的是异步弹窗，它超时既可能是「H5 拒绝」也可能是「H5 慢」，
     *    但从「当天机会是否已消耗」的角度看，只要请求发出去就一样了。
     */
    fun markSubmitted(c: Context) {
        sp(c).edit().putString(K_SUBMIT_DATE, todayStr()).apply()
    }

    /**
     * 清掉提交标记，让提交步可以再次执行。
     *
     * 用途：调试时点过一次提交后想再跑完整流程，用它复位。
     * 触发：adb shell am start -n com.xiaoyao.autocheckin/.MainActivity --ez clearSubmitDate true
     * ⚠️ 正常使用【不要】清 —— 清了就等于放弃「不会二次提交」这层保护。
     */
    fun clearSubmitDate(c: Context) {
        sp(c).edit().remove(K_SUBMIT_DATE).apply()
    }

    // ==================== 快门坐标记忆（识别失败的兜底） ====================
    //
    // 为什么需要它：ShutterFinder 是「图像识别」，理论上够稳，但它依赖
    // AccessibilityService.takeScreenshot —— 相机页在预览时偶尔会拒绝截图
    // （拿不到 HardwareBuffer），一拒绝就整个识别链条归零。
    //
    // 老代码这时会回退到写死的 50%,91% —— 而那个比例真机实测偏了 112px
    // （算出来 y≈2068，实际快门 y≈2180），基本必失。等于「兜底兜了个空」。
    //
    // 改成记住【上次真的识别到快门的那一点】：快门在同一个 App 的同一种
    // 相机页面里位置是固定的，上次准这次也准。存比例而不是像素，
    // 换分辨率/换设备也还能凑合。

    /** 归一化后的快门坐标（相对屏幕物理尺寸，0~1）。 */
    data class ShutterPoint(val xRatio: Float, val yRatio: Float)

    /** 上次成功识别到的快门坐标；从未成功过返回 null。 */
    fun shutterPoint(c: Context): ShutterPoint? {
        val s = sp(c).getString(K_SHUTTER_POINT, null) ?: return null
        val p = s.split(",")
        if (p.size != 2) return null
        val x = p[0].trim().toFloatOrNull() ?: return null
        val y = p[1].trim().toFloatOrNull() ?: return null
        // 明显不合理的一律当没有（防手改 SP 或旧版本脏数据把点甩到天上）
        if (x <= 0.01f || x >= 0.99f || y <= 0.01f || y >= 0.99f) return null
        return ShutterPoint(x, y)
    }

    /**
     * 记下「这次识别到的快门在哪」。传入的是**屏幕物理像素**坐标，
     * 内部按屏幕尺寸归一化后存储。
     */
    fun saveShutterPoint(c: Context, x: Float, y: Float, screenW: Int, screenH: Int) {
        if (screenW <= 0 || screenH <= 0) return
        val xr = x / screenW
        val yr = y / screenH
        if (xr <= 0.01f || xr >= 0.99f || yr <= 0.01f || yr >= 0.99f) return
        sp(c).edit().putString(K_SHUTTER_POINT, "$xr,$yr").apply()
    }

    /** 忘掉快门坐标（识别的兜底又回到默认比例）。 */
    fun clearShutterPoint(c: Context) {
        sp(c).edit().remove(K_SHUTTER_POINT).apply()
    }

    // ==================== 结果推送（微信） ====================
    //
    // 为什么做：签到是【无人值守】的 —— 定时在后台跑，成功了没人知道，
    // 失败了更没人知道，只能自己想起来去翻日志。把结果推到微信上，
    // 「签上了 / 没签上」一眼就知道，失败了还来得及当天手动补。
    //
    // ⚠️ token 是密钥，绝不写进代码默认值（仓库是公开的）。只能由用户在
    //    设置页里填，存在本机 SharedPreferences 里。

    /** 默认推送服务地址（用户的 Cloudflare Worker）。不含任何密钥，可硬编码。 */
    const val DEFAULT_PUSH_URL = "https://push.142588.xyz/wxsend"

    fun pushEnabled(c: Context): Boolean = sp(c).getBoolean(K_PUSH_ON, false)
    fun pushUrl(c: Context): String =
        sp(c).getString(K_PUSH_URL, DEFAULT_PUSH_URL) ?: DEFAULT_PUSH_URL

    fun pushToken(c: Context): String = sp(c).getString(K_PUSH_TOKEN, "") ?: ""

    /** 临时覆盖接收人（可选）。留空表示用服务端默认的那批用户。 */
    fun pushUserId(c: Context): String = sp(c).getString(K_PUSH_USER, "") ?: ""

    fun savePush(c: Context, enabled: Boolean, url: String, token: String, userId: String) {
        sp(c).edit()
            .putBoolean(K_PUSH_ON, enabled)
            .putString(K_PUSH_URL, url)
            .putString(K_PUSH_TOKEN, token)
            .putString(K_PUSH_USER, userId)
            .apply()
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
