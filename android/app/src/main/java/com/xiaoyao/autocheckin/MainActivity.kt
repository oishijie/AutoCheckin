package com.xiaoyao.autocheckin

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * 主界面：开启无障碍、配置 intent 与步骤表、设定每日时间、查看运行日志。
 * 刻意不依赖任何 androidx 组件，保证 APK 体积最小。
 *
 * UI 是自绘的 Material 3：配色 token 在 values/colors.xml（深色覆盖在 values-night/），
 * 形状与涟漪在 res/drawable/，可复用的样式在 values/styles.xml。
 */
class MainActivity : Activity() {

    private lateinit var intentEt: EditText
    private lateinit var stepsEt: EditText
    private lateinit var pkgEt: EditText
    private lateinit var hourEt: EditText
    private lateinit var minuteEt: EditText
    private lateinit var enabledCb: Switch
    private lateinit var debugCb: Switch
    private lateinit var logTv: TextView
    private lateinit var logScroll: ScrollView

    /** 状态区三行：指示点 + 值文本。颜色按状态 tint，一个 drawable 覆盖三态 */
    private lateinit var dotAccess: View
    private lateinit var valAccess: TextView
    private lateinit var dotOverlay: View
    private lateinit var valOverlay: TextView
    private lateinit var dotProbe: View
    private lateinit var valProbe: TextView

    // ---------------- 底部 Tab ----------------
    // 三页视图是【常驻】的，切换只改 visibility：不重建视图，
    // 所以「设置」页里改到一半的内容不会因为切去看一眼日志就丢。

    private lateinit var tabPages: List<View>
    private lateinit var tabItems: List<View>
    private lateinit var tabIndicators: List<View>
    private lateinit var tabIcons: List<ImageView>
    private lateinit var tabLabels: List<TextView>

    private var currentTab = TAB_STATUS

    /** 本机版本号（取自 PackageInfo），「检查更新」拿它跟远端 tag 比大小 */
    private var currentVersion = "—"

    private val ui = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refreshLog()
            ui.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusRefreshTargets()

        intentEt = findViewById(R.id.etIntent)
        stepsEt = findViewById(R.id.etSteps)
        pkgEt = findViewById(R.id.etPkg)
        hourEt = findViewById(R.id.etHour)
        minuteEt = findViewById(R.id.etMinute)
        enabledCb = findViewById(R.id.cbEnabled)
        debugCb = findViewById(R.id.cbDebug)
        logTv = findViewById(R.id.tvLog)
        logScroll = findViewById(R.id.logScroll)

        loadConfig()

        setupTabs(savedInstanceState?.getInt(KEY_TAB) ?: TAB_STATUS)
        showVersion()
        setupAbout()

        // 调试模式开关【即时生效】，不跟「保存」按钮走 ——
        // 排查时改一下就想马上重跑，不该还要记得去点保存。
        debugCb.setOnCheckedChangeListener { _, on ->
            ConfigStore.setDebugMode(this, on)
            Logger.log(
                this,
                if (on) "🐞 调试模式已开启：只跑 1 轮，失败即停并保留现场"
                else "调试模式已关闭：恢复多轮重试 + 自动回收现场"
            )
            refreshLog()
        }

        findViewById<Button>(R.id.btnAccess).setOnClickListener { openAccessibilitySettings() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { save(true) }
        findViewById<Button>(R.id.btnRun).setOnClickListener { runNow() }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            Logger.clearMem()
            refreshLog()
        }

        // 权限引导：定时签到必须在【后台】启动 deeplink，缺悬浮窗权限会被系统拦掉。
        // 无 UI 触发（adb runNow / testRecover / onceIn）时不弹，避免打断自动化。
        if (intent?.getBooleanExtra("runNow", false) != true &&
            intent?.getBooleanExtra("testRecover", false) != true &&
            intent?.getBooleanExtra("coldReset", false) != true &&
            intent?.getBooleanExtra("resetSteps", false) != true &&
            (intent?.getIntExtra("onceIn", 0) ?: 0) <= 0
        ) {
            ui.post { ensureOverlayPermission() }
        }

        // 支持无 UI 触发（便于电脑端 adb 调试）：
        //   adb shell am start -n com.xiaoyao.autocheckin/.MainActivity --ez runNow true
        //   adb shell am start -n com.xiaoyao.autocheckin/.MainActivity --ez testRecover true
        //   adb shell am start -n com.xiaoyao.autocheckin/.MainActivity --ez coldReset true
        //   adb shell am start -n com.xiaoyao.autocheckin/.MainActivity --ei onceIn 60
        //     ↑ 模拟定时：N 秒后经 AlarmManager 触发，真正复现「后台 + 熄屏」现场
        maybeRunNow(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeRunNow(intent)
    }

    /**
     * 处理 adb 触发的无 UI 参数（runNow / testRecover / backFirst / onceIn）。
     *
     * 无障碍服务在 force-stop 之后要被系统重新绑定、需数秒才就绪，
     * 所以这里轮询等服务可用，而不是傻等一个固定时长
     * （踩过的坑：固定 800ms 时服务还没连上，runNow 直接静默 return）。
     *
     * backFirst=true 时会先把 App 退到后台再执行 —— 用来近似复现「定时触发」的场景。
     *
     * ⚠️ backFirst 只能"近似"：App 刚离开前台，仍处在 Android 的 BAL 宽限期内，
     *    后台跳转还会被放行，容易给出假阳性。要真正复现请用 onceIn：
     *      adb shell am start -n com.xiaoyao.autocheckin/.MainActivity --ei onceIn 60
     *    它通过 AlarmManager 走完整定时路径，排好后自己退到后台，届时熄屏等待即可。
     */
    private fun maybeRunNow(from: Intent?) {
        val wantRun = from?.getBooleanExtra("runNow", false) == true
        val wantRecover = from?.getBooleanExtra("testRecover", false) == true
        val wantProbe = from?.getBooleanExtra("probeWindow", false) == true
        val wantShutter = from?.getBooleanExtra("testShutter", false) == true
        val wantColdReset = from?.getBooleanExtra("coldReset", false) == true
        val backFirst = from?.getBooleanExtra("backFirst", false) == true
        val onceIn = from?.getIntExtra("onceIn", 0) ?: 0

        // 重置步骤表：丢掉 SP 里的旧文本，让编辑框回落到代码内置的最新默认值。
        //
        // 为什么需要它：步骤表一旦 save() 过就存在 SP 里，之后升级 APK 里
        // 新写的 Step.DEFAULT 永远不会生效（读的是 SP 里的旧文本）。
        // 必须排在 runNow 之前 —— runNow 会 save()，把编辑框内容再写回 SP。
        if (from?.getBooleanExtra("resetSteps", false) == true) {
            ConfigStore.resetSteps(this)
            stepsEt.setText(ConfigStore.stepsText(this))
            Logger.log(this, "步骤表已重置为内置默认值（${ConfigStore.steps(this).size} 条有效步骤）")
        }

        // adb 直切调试模式：--ez debug true / false
        // 电脑端调参时省得再去点屏幕，也能和 resetSteps / runNow 一条命令串起来。
        if (from?.hasExtra("debug") == true) {
            val on = from.getBooleanExtra("debug", false)
            ConfigStore.setDebugMode(this, on)
            debugCb.isChecked = on
        }

        // 模拟定时：只排闹钟，不立即执行
        if (onceIn > 0) {
            Scheduler.scheduleOnce(this, onceIn)
            Toast.makeText(this, "已设定 ${onceIn} 秒后模拟定时，现在请熄屏", Toast.LENGTH_LONG).show()
            // 排完立刻把自己退到后台，让现场更接近真实定时（App 不在前台）
            ui.postDelayed({ moveTaskToBack(true) }, 2500L)
            return
        }

        if (!wantRun && !wantRecover && !wantProbe && !wantShutter && !wantColdReset) return

        var waited = 0
        val probe = object : Runnable {
            override fun run() {
                val svc = CheckinAccessibilityService.instance
                if (svc == null) {
                    if (waited < 20000) {
                        waited += 500
                        ui.postDelayed(this, 500L)
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "无障碍服务未就绪，请先在系统设置里开启",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return
                }

                // 窗口探测只读、不改动界面，直接跑
                if (wantProbe) {
                    svc.probeWindow()
                    return
                }

                // 快门识别测试：只看截图识别 + 点一下，不跑任何其他步骤
                if (wantShutter) {
                    svc.testShutter()
                    return
                }

                // 冷启动测试：回桌面 → 清掉目标 App 进程 → 从入口重进
                if (wantColdReset) {
                    svc.testColdReset()
                    return
                }

                if (backFirst) {
                    // 先把自己退到后台，再执行 —— 复现定时触发的真实环境
                    moveTaskToBack(true)
                    ui.postDelayed({
                        if (wantRecover) svc.testRecover() else runNow()
                    }, 2000L)
                } else {
                    if (wantRecover) svc.testRecover() else runNow()
                }
            }
        }
        ui.postDelayed(probe, 500L)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshLog()
        ui.post(ticker)
    }

    override fun onPause() {
        ui.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 记住当前页，横竖屏切换/被系统回收重建后不会跳回「状态」页
        outState.putInt(KEY_TAB, currentTab)
    }

    // ---------------- 底部 Tab ----------------

    private fun setupTabs(initialTab: Int) {
        tabPages = listOf(
            findViewById(R.id.pageStatus),
            findViewById(R.id.pageSettings),
            findViewById(R.id.pageAbout)
        )
        tabItems = listOf(
            findViewById(R.id.tabStatus),
            findViewById(R.id.tabSettings),
            findViewById(R.id.tabAbout)
        )
        tabIndicators = listOf(
            findViewById(R.id.indStatus),
            findViewById(R.id.indSettings),
            findViewById(R.id.indAbout)
        )
        tabIcons = listOf(
            findViewById(R.id.icoStatus),
            findViewById(R.id.icoSettings),
            findViewById(R.id.icoAbout)
        )
        tabLabels = listOf(
            findViewById(R.id.lblStatus),
            findViewById(R.id.lblSettings),
            findViewById(R.id.lblAbout)
        )

        tabItems.forEachIndexed { index, item ->
            item.setOnClickListener { selectTab(index) }
        }

        selectTab(initialTab)
    }

    /**
     * 切页。选中态用「pill 指示器 + 图标/文字染色」双通道表达 ——
     * 只染文字颜色的话，在深色主题下对比度不够，一眼看不出选中的是哪个。
     */
    private fun selectTab(index: Int) {
        currentTab = index
        val on = getColor(R.color.md_primary)
        val off = getColor(R.color.md_on_surface_variant)

        tabPages.forEachIndexed { i, page ->
            page.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        tabIndicators.forEachIndexed { i, indicator -> indicator.isSelected = i == index }
        tabIcons.forEachIndexed { i, icon -> icon.setColorFilter(if (i == index) on else off) }
        tabLabels.forEachIndexed { i, label -> label.setTextColor(if (i == index) on else off) }
    }

    /** 关于页的版本号取自 PackageInfo，避免和 build.gradle 里的 versionName 写两遍对不上 */
    @Suppress("DEPRECATION")
    private fun showVersion() {
        val name = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (t: Throwable) {
            null
        }
        currentVersion = name ?: "—"
        findViewById<TextView>(R.id.tvVersion).text = "版本 $currentVersion · 无障碍方案 · 免 root"
    }

    // ---------------- 关于页 ----------------

    /**
     * 关于页的三张说明卡默认收起，点标题行展开。
     *
     * 为什么默认收起：这三段是「看一遍就记住」的说明文字，展开着占掉整屏，
     * 真正要找的「检查更新 / 相关链接」反而被挤到看不见的地方。
     */
    private fun setupAbout() {
        bindFold(R.id.hdrHow, R.id.arwHow, R.id.bodyHow)
        bindFold(R.id.hdrSteps, R.id.arwSteps, R.id.bodySteps)
        bindFold(R.id.hdrNotes, R.id.arwNotes, R.id.bodyNotes)

        findViewById<View>(R.id.lnkRepo).setOnClickListener { openUrl(Updater.URL_REPO, "GitHub 仓库") }
        findViewById<View>(R.id.lnkLatest).setOnClickListener { openUrl(Updater.URL_LATEST, "最新发布页") }
        findViewById<View>(R.id.lnkIssues).setOnClickListener { openUrl(Updater.URL_ISSUES, "反馈页") }

        findViewById<Button>(R.id.btnCheckUpdate).setOnClickListener { checkUpdate() }
    }

    /**
     * 把「标题行 + 箭头 + 正文」绑成一组折叠控件。
     *
     * 箭头只做旋转不换字符：拉丁字符换字形会跳一下，旋转是连续的。
     * 初始态直接 setRotation（不走动画），否则每次进页面都会看到箭头自己转一下。
     */
    private fun bindFold(headerId: Int, arrowId: Int, bodyId: Int) {
        val header = findViewById<View>(headerId)
        val arrow = findViewById<TextView>(arrowId)
        val body = findViewById<View>(bodyId)

        fun apply(expanded: Boolean, animate: Boolean) {
            body.visibility = if (expanded) View.VISIBLE else View.GONE
            val degree = if (expanded) 0f else -90f
            if (animate) arrow.animate().rotation(degree).setDuration(160L).start()
            else arrow.rotation = degree
        }

        apply(expanded = false, animate = false)
        header.setOnClickListener { apply(body.visibility != View.VISIBLE, animate = true) }
    }

    private fun openUrl(url: String, what: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (t: Throwable) {
            Toast.makeText(this, "无法打开$what：${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** 查 GitHub 上的最新 Release。请求在后台线程，[Updater] 保证回调回主线程 */
    private fun checkUpdate() {
        val btn = findViewById<Button>(R.id.btnCheckUpdate)
        val state = findViewById<TextView>(R.id.tvUpdateState)

        btn.isEnabled = false
        state.text = "正在检查…"

        Updater.check(currentVersion) { result ->
            btn.isEnabled = true
            state.text = when (result) {
                is Updater.Result.Newer ->
                    "发现新版本 ${result.version}（当前 $currentVersion），可到「下载最新版」获取"

                is Updater.Result.Latest ->
                    "已是最新版本（${result.version}）"

                is Updater.Result.Failed ->
                    "检查失败：${result.reason}"
            }
            Logger.log(this, "检查更新：${state.text}")
        }
    }

    // ---------------- 配置读写 ----------------

    private fun loadConfig() {
        intentEt.setText(ConfigStore.intentUrl(this))
        stepsEt.setText(ConfigStore.stepsText(this))
        pkgEt.setText(ConfigStore.targetPkg(this))
        hourEt.setText(ConfigStore.hour(this).toString())
        minuteEt.setText(ConfigStore.minute(this).toString())
        enabledCb.isChecked = ConfigStore.enabled(this)
        debugCb.isChecked = ConfigStore.debugMode(this)
    }

    private fun save(showToast: Boolean) {
        val intentUrl = intentEt.text.toString().trim()
        val steps = stepsEt.text.toString()
        val pkg = pkgEt.text.toString().trim()
        val hour = hourEt.text.toString().trim().toIntOrNull()?.coerceIn(0, 23) ?: 22
        val minute = minuteEt.text.toString().trim().toIntOrNull()?.coerceIn(0, 59) ?: 0
        val enabled = enabledCb.isChecked

        hourEt.setText(hour.toString())
        minuteEt.setText(minute.toString())

        ConfigStore.save(this, intentUrl, steps, pkg, hour, minute, 3, enabled)

        val parsed = ConfigStore.steps(this)
        Logger.log(this, "配置已保存，解析出 ${parsed.size} 条有效步骤")

        if (enabled) {
            Scheduler.scheduleDaily(this, hour, minute)
        } else {
            Scheduler.cancel(this)
        }
        refreshLog()
        if (showToast) {
            Toast.makeText(this, if (enabled) "已保存，定时已启动" else "已保存，定时已关闭", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- 状态与操作 ----------------

    /** 绑定状态区三行的取值控件（onCreate 里调用一次） */
    private fun statusRefreshTargets() {
        dotAccess = findViewById(R.id.dotAccess)
        valAccess = findViewById(R.id.valAccess)
        dotOverlay = findViewById(R.id.dotOverlay)
        valOverlay = findViewById(R.id.valOverlay)
        dotProbe = findViewById(R.id.dotProbe)
        valProbe = findViewById(R.id.valProbe)
    }

    private fun refreshStatus() {
        val on = isAccessibilityEnabled()
        val overlay = Settings.canDrawOverlays(this)
        val probe = OverlayProbe.active

        markRow(dotAccess, valAccess, if (on) ST_OK else ST_BAD, if (on) "已开启" else "未开启")
        markRow(
            dotOverlay, valOverlay,
            if (overlay) ST_OK else ST_BAD,
            if (overlay) "已授权" else "未授权"
        )

        // 权限只是入场券，真正起作用的是服务挂上的那个 1×1 隐形探针窗口。
        // 分开显示，是因为「有权限但服务没连上」时探针依然是 false，定时照样失败。
        when {
            probe -> markRow(dotProbe, valProbe, ST_OK, "生效")
            !on -> markRow(dotProbe, valProbe, ST_IDLE, "待服务开启")
            else -> markRow(dotProbe, valProbe, ST_BAD, "未生效")
        }
    }

    /**
     * 给状态行着色：指示点与右侧文字同色。
     * 绿=正常 / 红=异常 / 灰=待定（既不算好也不算坏，只是还没轮到它）。
     */
    private fun markRow(dot: View, value: TextView, state: Int, text: String) {
        val color = getColor(
            when (state) {
                ST_OK -> R.color.status_ok
                ST_BAD -> R.color.status_bad
                else -> R.color.status_idle
            }
        )
        dot.backgroundTintList = ColorStateList.valueOf(color)
        value.text = text
        value.setTextColor(color)
    }

    /**
     * 引导授予「显示在其他应用上层」权限。
     *
     * Android 12+ 会拦掉后台应用的 startActivity：定时签到触发时本 App 就在后台，
     * deeplink 会被静默丢弃（日志照样打「已发起跳转」，页面纹丝不动），然后 4 轮空转。
     *
     * 注意：**光有权限还不够**，服务还必须真的挂上一个悬浮窗窗口
     * （见 OverlayProbe），系统才认「调用方 uid 有可见窗口」并放行。
     *
     * 手动点击「立即执行」时 App 停在前台，不受这条限制 —— 所以这个坑在开发期极易漏掉。
     */
    private fun ensureOverlayPermission() {
        if (Settings.canDrawOverlays(this)) return
        AlertDialog.Builder(this)
            .setTitle("还差一个权限：显示在其他应用上层")
            .setMessage(
                "定时签到是在【后台】自动触发的。Android 12 起会禁止后台应用启动跳转链接，" +
                    "没有这个权限，到点执行时 deeplink 会被系统直接丢掉，签到不会成功。\n\n" +
                    "授权路径：设置 → 应用 → 本应用 → 显示在其他应用上层。\n\n" +
                    "授权后状态栏会显示「后台跳转豁免：✅ 探针生效」。\n\n" +
                    "（手动点击「立即执行」测试时 App 在前台，不受此限制。）"
            )
            .setPositiveButton("去授权") { _, _ -> requestOverlayPermission() }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun requestOverlayPermission() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (t: Throwable) {
            Toast.makeText(this, "无法打开授权页：${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        if (CheckinAccessibilityService.isReady) return true
        val expected = ComponentName(this, CheckinAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it.toString()) == expected }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "请在列表中找到「${getString(R.string.app_name)}」并开启", Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            Toast.makeText(this, "无法打开设置：${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun runNow() {
        save(false)
        val svc = CheckinAccessibilityService.instance
        if (svc == null) {
            Toast.makeText(this, "无障碍服务未开启，请先点击第 ① 步开启", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "开始执行，可切到目标 App 观察", Toast.LENGTH_SHORT).show()
        svc.runAsync("手动测试")
    }

    private fun refreshLog() {
        val lines = Logger.recent()
        logTv.text = if (lines.isEmpty()) "（暂无日志）" else lines.joinToString("\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private companion object {
        /** 状态行取值：绿（正常）/ 红（异常）/ 灰（待定） */
        const val ST_IDLE = 0
        const val ST_OK = 1
        const val ST_BAD = 2

        /** 底部 Tab 下标 */
        const val TAB_STATUS = 0
        const val TAB_SETTINGS = 1
        const val TAB_ABOUT = 2

        /** 重建时用来记住停留在哪一页 */
        const val KEY_TAB = "currentTab"
    }
}
