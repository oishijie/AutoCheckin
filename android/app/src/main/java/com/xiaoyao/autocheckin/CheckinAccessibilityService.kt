package com.xiaoyao.autocheckin

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 核心无障碍服务：常驻系统，负责执行整个签到流程。
 * 由系统绑定（在设置 → 无障碍 中手动开启），可在后台被 Activity 或定时闹钟调用。
 */
class CheckinAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: CheckinAccessibilityService? = null
            private set

        private val running = AtomicBoolean(false)

        /**
         * 说明：这里曾经有个 STUCK_GRACE_MS（8 秒判死）——
         * 一看到页面挂着「加载中...」超过 8 秒就提前放弃、退回重进。
         *
         * 它服务于一个已经消失的场景：早期版本用「右上角 … → 刷新」救卡死页面，
         * 结果把页面推进 health-wx 死状态。现在恢复手段改成了 BACK 重进，
         * 页面慢加载（H5 冷启动实测能到 20 秒）反而成了常态，
         * 提前判死只会把本来能成的流程误杀 —— 所以整个判死机制已移除，
         * 改成老实等满 waitMs，超时后由上层决定要不要重进。
         */

        /** 无障碍服务是否处于已连接状态 */
        val isReady: Boolean get() = instance != null

        /**
         * 每一组里，先试几次「BACK 退回 → 重新点开卡片」。
         *
         * 用户 2026-09-25 拍定 3 次。理由：别急着下"救不回来"的结论 ——
         * 多数「加载中」BACK 重进一两次就出来了，而这个动作便宜（一轮约 7 秒）。
         * 试满之后再上进程级冷启动（见 [COLD_GROUPS]）。
         */
        private const val BACK_TRIES_PER_GROUP = 3

        /**
         * 重进后单次等待时长（毫秒）。
         *
         * 比首次冷加载短得多：页面刚打开过，资源都在缓存里。
         * 实测页面要么两三秒就出来、要么就是真卡住 —— 所以宁可每轮等短一点、
         * 多轮几次，反馈更快，整体耗时也未必更长。
         */
        private const val RETRY_WAIT_MS = 6000L

        /**
         * 「BACK × [BACK_TRIES_PER_GROUP] → 进程级冷启动」这个【组】重复几遍。
         *
         * 顺序由用户 2026-09-25 拍定：每一组都必须先把 BACK 试满，才允许冷启动；
         * **任何检测都不许短路这个顺序**（health-wx 只作日志提示，见调用处）。
         *
         * 为什么只给 2 组：第 1 组失败通常是页面 / 网络慢，第 2 组刚重置过、还有机会；
         * 到第 3 组还不行，基本可判定【不是重进能解决】的问题了（网络断、
         * 签到窗口没开、deeplink 凭证过期），再耗一组只是把失败时间往后推 ——
         * 那种情况交给外层整轮重来更值（它会重走 deeplink、从头完整来一遍）。
         *
         * 耗时账（全失败路径）：单次 BACK 重进 ≈ 7 秒、单次冷启动 ≈ 30 秒，
         * 一组 ≈ 51 秒，2 组 ≈ 102 秒；再乘外层 4 轮 ≈ 6.8 分钟。
         * 成功路径不吃这个亏 —— 第一次 BACK 就成的话 7 秒就结束。
         */
        private const val COLD_GROUPS = 2

        /**
         * 冷启动后的等待时长。
         *
         * 比热缓存重进（[RETRY_WAIT_MS]）长得多，因为它是一次【全新进程】的
         * 冷加载：目标 App 要重新初始化、WebView 内核要重新起、H5 资源要重新拉。
         * 实测冷启动能到 20 秒，给短了会把本来能成的流程误判成失败。
         */
        private const val COLD_RESTART_WAIT_MS = 20000L
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Logger.log(this, "无障碍服务已连接 ✅")
        // 挂上 1×1 隐形悬浮窗：Android 12+ 定时触发时 App 在后台，
        // 没有它 deeplink 会被「后台启动 Activity」限制直接丢弃（详见 OverlayProbe）。
        OverlayProbe.show(this)
    }

    /** 唤醒期间持有的屏幕 WakeLock（保证整条流程屏幕不灭） */
    private var screenLock: PowerManager.WakeLock? = null

    /** 最近一次窗口切换到的 Activity 类名，由无障碍事件更新 */
    @Volatile
    private var lastWindowClass: String = ""

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 本方案以「主动轮询查找控件」为主，事件只用来记录当前 Activity 类名，
        // 供 launchEntry 判断入口是否「直达通知中心」。
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val cls = event.className?.toString().orEmpty()
            if (cls.isNotEmpty()) lastWindowClass = cls
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        OverlayProbe.hide()
        Logger.log(this, "无障碍服务已断开 ⚠️")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        OverlayProbe.hide()
        screenLock?.let { if (it.isHeld) it.release() }
        screenLock = null
        super.onDestroy()
    }

    /** 异步执行签到流程，供 Activity / 定时广播调用；并发触发时只执行一次 */
    fun runAsync(source: String) {
        if (!running.compareAndSet(false, true)) {
            Logger.log(this, "已有任务在执行，忽略本次触发（$source）")
            return
        }
        Thread {
            try {
                execute(source)
            } catch (t: Throwable) {
                Logger.log(this, "执行异常: ${t.message}")
            } finally {
                running.set(false)
            }
        }.start()
    }

    /**
     * 单项测试：演练「卡死恢复」这条路径 —— BACK 退回列表，再重新点开签到卡片。
     * 不跑完整签到流程。
     *
     * 用途：签到页卡在「加载中...」是概率事件，很难蹲到现场；
     * 把恢复这段单独拆出来，只要此刻停在签到页（正常状态也行）就能演练一遍。
     *
     * 触发：
     *   adb shell am start -n com.xiaoyao.autocheckin/.MainActivity --ez testRecover true
     *
     * 判读日志：
     *   已重新点开卡片，重新等待页面加载   ← 恢复动作生效
     *   退回后没找到签到卡片               ← 步骤表里缺「进入卡片」那一步
     */
    fun testRecover() {
        if (!running.compareAndSet(false, true)) {
            Logger.log(this, "已有任务在执行，忽略本次恢复演练")
            return
        }
        Thread {
            try {
                Logger.log(this, "===== 单项测试：卡死恢复（BACK 退回后重进）=====")

                // adb 是通过启动 MainActivity 来触发本方法的，此刻前台就是本 App 自己。
                // 必须先把自己退到后台、回到触发前的页面，否则抓到的节点树是设置界面。
                var pkg = rootInActiveWindow?.packageName?.toString().orEmpty()
                if (pkg == packageName) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    Thread.sleep(2000)
                    pkg = rootInActiveWindow?.packageName?.toString().orEmpty()
                }
                Logger.log(this, "当前前台：${pkg.ifEmpty { "未知" }}")
                if (pkg.isEmpty()) {
                    Logger.log(this, "⚠️ 取不到前台窗口，请确认屏幕已解锁")
                }

                val steps = ConfigStore.steps(this)
                val ok = recoverFromStuckCard(steps, steps.size)
                Logger.log(this, if (ok) "✅ 恢复动作完成" else "❌ 恢复动作失败")
            } catch (t: Throwable) {
                Logger.log(this, "恢复演练异常: ${t.message}")
            } finally {
                running.set(false)
            }
        }.start()
    }

    /**
     * 单项测试：进程级冷启动兜底。
     *
     * 用途：验证「页面级 BACK 重进救不回来时，杀进程能不能救」。
     * 只要此刻停在卡住的签到页上，跑这一条就能看到完整的冷启动链路，
     * 不必跑整条流程、也不必蹲那个概率性的卡死现场。
     *
     * 触发：
     *   adb shell am start -n com.xiaoyao.autocheckin/.MainActivity --ez coldReset true
     *
     * 判读日志：
     *   已清掉 <目标包名> 进程              ← 杀进程成功
     *   ⚠️ <目标包名> 仍在运行              ← ROM 拦了请求，这条路走不通
     *   已从入口重进（直达通知中心=…）        ← deeplink 重新拉起成功
     */
    fun testColdReset() {
        if (!running.compareAndSet(false, true)) {
            Logger.log(this, "已有任务在执行，忽略本次冷启动测试")
            return
        }
        Thread {
            try {
                Logger.log(this, "===== 单项测试：进程级冷启动 =====")
                val ok = hardResetTargetApp("单项测试")
                Logger.log(this, if (ok) "✅ 目标 App 进程已清掉" else "❌ 未能确认清掉进程（见上方告警）")
                val direct = launchEntry()
                Logger.log(this, "已从入口重进（直达通知中心=$direct）")
            } catch (t: Throwable) {
                Logger.log(this, "冷启动测试异常: ${t.message}")
            } finally {
                running.set(false)
            }
        }.start()
    }

    /**
     * 窗口探测：把「当前活动窗口」以及所有交互窗口的节点树打到日志里。
     *
     * 用途：某个页面明明看得见、uiautomator 也 dump 得到，无障碍却按 id 找不到控件时，
     * 靠它区分到底是
     *   ① rootInActiveWindow 返回了别的窗口（活动窗口判定问题），还是
     *   ② 窗口对了但节点树里没有那个 id（viewIdResourceName 没上报 / 节点被截断）。
     *
     * 触发：
     *   adb shell am start -n com.xiaoyao.autocheckin/.MainActivity --ez probeWindow true
     */
    /**
     * 只测「截图找快门」这一个动作，不跑前面任何步骤。
     *
     * 存在的理由：整条流程动辄一两分钟，还常卡在「签到页加载」这种
     * 与快门无关的环节，想单独验一下图像识别得靠运气。
     * 手动把界面开到相机页，然后：
     *   adb shell am start -n com.xiaoyao.autocheckin/.MainActivity --ez testShutter true
     * 就能直接看识别结果、耗时和归一化位置。
     */
    fun testShutter() {
        Thread {
            Logger.log(this, "===== 快门识别测试开始 =====")
            val t0 = System.currentTimeMillis()
            val pt = ShutterFinder.locate(this)
            val cost = System.currentTimeMillis() - t0
            val dm = resources.displayMetrics
            Logger.log(this, "识别耗时 ${cost}ms；displayMetrics=${dm.widthPixels}x${dm.heightPixels}")
            if (pt == null) {
                Logger.log(this, "✗ 没识别到白色快门（截图被拒？或当前不在相机页？）")
            } else {
                Logger.log(
                    this,
                    "✓ 识别到快门 (${pt.x}, ${pt.y})，归一化 " +
                        "(${"%.1f".format(pt.x / dm.widthPixels * 100)}%, " +
                        "${"%.1f".format(pt.y / dm.heightPixels * 100)}%)"
                )
                val ok = StepEngine.tap(this, pt.x, pt.y)
                Logger.log(this, if (ok) "✓ 已点击快门" else "✗ 点击失败")
            }
            Logger.log(this, "===== 快门识别测试结束 =====")
        }.start()
    }

    fun probeWindow() {
        Thread { dumpWindowsNow("手动触发") }.start()
    }

    /**
     * 把当前窗口与节点树打进日志（同步执行）。
     *
     * @param why 触发原因，写进日志便于回溯
     */
    private fun dumpWindowsNow(why: String) {
        try {
            Logger.log(this, "===== 窗口探测（$why）=====")

            val root = rootInActiveWindow
            if (root == null) {
                Logger.log(this, "rootInActiveWindow = null ← 读不到活动窗口")
            } else {
                Logger.log(this, "rootInActiveWindow → pkg=${root.packageName} cls=${root.className}")
                val n = dumpTree(root, 1, 0)
                Logger.log(this, "   节点树共打印 $n 个有效节点")
            }

            val ws = windows
            Logger.log(this, "getWindows() 数量 = ${ws?.size ?: -1}")
            ws?.forEachIndexed { i, w ->
                val r = w.root
                Logger.log(
                    this,
                    "   [$i] type=${w.type} active=${w.isActive} focused=${w.isFocused}" +
                        " pkg=${r?.packageName} cls=${r?.className}"
                )
            }
            Logger.log(this, "===== 窗口探测结束 =====")
        } catch (t: Throwable) {
            Logger.log(this, "窗口探测异常: ${t.message}")
        }
    }

    /** 递归打印节点树（只打有 id / 有文字 / 可点的节点），最多 80 行 */
    private fun dumpTree(node: AccessibilityNodeInfo, depth: Int, count: Int): Int {
        var n = count
        if (n >= 80) return n
        val id = node.viewIdResourceName?.substringAfterLast('/') ?: ""
        val cls = node.className?.toString()?.substringAfterLast('.') ?: ""
        val txt = node.text?.toString() ?: ""
        if (id.isNotEmpty() || txt.isNotEmpty() || node.isClickable) {
            val r = android.graphics.Rect().also { node.getBoundsInScreen(it) }
            Logger.log(
                this,
                "   ${"  ".repeat(depth)}$cls id=$id click=${node.isClickable}" +
                    " $r text=${txt.take(14)}"
            )
            n++
        }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            n = dumpTree(c, depth + 1, n)
        }
        return n
    }

    private fun execute(source: String) {
        Logger.log(this, "========== 开始签到（$source）==========")

        val steps = ConfigStore.steps(this)
        if (steps.isEmpty()) {
            Logger.log(this, "❌ 步骤表为空，请先在 App 里配置步骤")
            return
        }
        Logger.log(this, "共 ${steps.size} 条步骤")

        // 调试模式：整轮只跑 1 次，且【不打扫现场】——
        // 失败了就让屏幕停在出事那一屏，好立刻 dump 控件树。
        // 正常模式才启用多轮重试（每轮都会先退出目标 App、从头再走一遍）。
        val debug = ConfigStore.debugMode(this)
        val retries = if (debug) 0 else ConfigStore.retry(this)
        if (debug) {
            Logger.log(this, "🐞 调试模式：只跑 1 轮，失败后原地保留现场，不做任何清理")
        }
        for (attempt in 1..(retries + 1)) {
            Logger.log(this, "----- 第 $attempt / ${retries + 1} 次尝试 -----")
            if (attempt > 1) Thread.sleep(3000)
            if (runOnce(steps, attempt)) {
                Logger.log(this, "🎉 签到流程执行完毕")
                return
            }
        }
        Logger.log(
            this,
            if (debug) {
                "❌ 调试模式：本次失败即停，现场已原样保留（可直接抓控件树）"
            } else {
                "❌ 多次尝试均失败，请检查步骤配置或改用 id/坐标定位"
            }
        )
    }

    private fun runOnce(steps: List<Step>, attempt: Int): Boolean {
        // 重试前先把目标 App 真正做掉，再从入口完整走一遍。
        //
        // ⚠️ 这里以前只按 4 次返回键，注释却写着「退出目标 App」—— 返回键退不掉
        //    进程，H5 的 WebView 实例照旧被复用，卡死的页面下一轮还是那张脸，
        //    「退出重来」名存实亡。现在换成进程级冷启动，才真的是从零开始。
        if (attempt > 1) {
            hardResetTargetApp("整轮重来前的现场回收")
        }

        wakeScreen()
        // 返回 true 表示入口已「直达通知中心」，此时无需再切「消息」Tab
        val directToChat = launchEntry()
        Thread.sleep(5000)

        val expectedPkg = ConfigStore.targetPkg(this)
        if (expectedPkg.isNotEmpty()) {
            val now = rootInActiveWindow?.packageName?.toString()
            if (now == null) {
                // 连前台窗口都取不到 —— 后面所有步骤都会以「未找到控件」失败。
                // 最常见的成因是 Android 12+ 的「后台启动 Activity 限制」把跳转丢了，
                // 没必要再耗完本次的全部步骤，直接判本轮失败。
                Logger.log(
                    this,
                    "❌ 取不到前台窗口，跳转很可能被系统拦掉了，本轮直接放弃" +
                        "（若 App 在后台运行，请授予「显示在其他应用上层」权限）"
                )
                return false
            }
            if (now != expectedPkg) {
                Logger.log(this, "提示：当前前台是 $now，与预期 $expectedPkg 不一致，继续尝试")
            } else {
                Logger.log(this, "已进入目标 App：$expectedPkg")
            }
        }

        for ((i, step) in steps.withIndex()) {
            // tab 步骤用于切底部 Tab。若入口已直达通知中心，
            // 这一步只会把界面切走（还得靠下一步切回来），直接跳过。
            if (step.kind == "tab" && directToChat) {
                Logger.log(this, "→ [${i + 1}/${steps.size}] tab=${step.value}（已直达通知中心，跳过）")
                continue
            }
            Logger.log(this, "→ [${i + 1}/${steps.size}] ${step.kind}=${step.value}")

            // 坐标步骤。value 支持两种写法：
            //   绝对像素  xy|540,2060
            //   屏幕比例  xy|50%,86%      ← 推荐，换分辨率不用改
            if (step.kind == "xy") {
                val parts = step.value.split(",")
                val dm = resources.displayMetrics
                val x = parseCoord(parts.getOrNull(0), dm.widthPixels)
                val y = parseCoord(parts.getOrNull(1), dm.heightPixels)
                if (x == null || y == null) {
                    Logger.log(this, "   ✗ 坐标格式错误，应为 \"x,y\" 或 \"50%,86%\"")
                    if (!step.optional) return false
                    continue
                }
                val ok = StepEngine.tap(this, x, y)
                Logger.log(
                    this,
                    if (ok) "   ✓ 已按坐标点击（$x, $y）" else "   ✗ 坐标点击失败（$x, $y）"
                )
                if (!ok && !step.optional) return false
                Thread.sleep(700)
                continue
            }

            // 快门步骤：截图 → 认出白色快门圆盘 → 点它的中心。
            //
            // 为什么要单独搞一种步骤：相机页的快门 cb_capture 是个裸 View，
            // 无障碍树里常常读不到（同机实测：uiautomator dump 有、服务读没有），
            // 而「按比例算坐标」会撞上坐标系偏移 ——
            //   displayMetrics.heightPixels 给的是应用窗口高度 2273，
            //   节点 bounds 却是屏幕坐标（0~2400），两者恒定差 127px；
            //   更坑的是这差值还随页面变（签到页内容到 2374，编辑页只到 2273）。
            //   于是比例算出来的点正好在快门边缘上下游走 → 时灵时不灵。
            // 截图认像素拿到的是屏幕物理坐标，把这一层全绕开了。
            if (step.kind == "shutter") {
                val pt = ShutterFinder.locate(this)
                val x: Float
                val y: Float
                if (pt != null) {
                    Logger.log(this, "   · 图像识别到快门 (${pt.x}, ${pt.y})")
                    x = pt.x
                    y = pt.y
                } else {
                    // 兜底：识别不出来（旧系统 / 截图被拒 / 白盘被挡）就回退到经验比例。
                    // 91% 是 2026-09-25 真机逐次数出来的值，虽然可能偏，
                    // 但快门圆盘够大，仍有机会命中。
                    val dm = resources.displayMetrics
                    x = dm.widthPixels * 0.5f
                    y = dm.heightPixels * 0.91f
                    Logger.log(this, "   · 未识别到白色快门，回退比例坐标 ($x, $y)")
                }
                val ok = StepEngine.tap(this, x, y)
                Logger.log(this, if (ok) "   ✓ 已点击快门" else "   ✗ 快门点击失败")
                if (!ok && !step.optional) return false
                Thread.sleep(700)
                continue
            }

            // 等待步骤：只等目标出现、不点击。
            // 用于确认页面真的加载出来了 —— 卡在「加载中…」时会超时返回失败，
            // 从而触发整体重试（重试前会自动退出 App 重新进入）。
            //
            // uiwait 是它的同胞（按字体图标码点等），区别在于：
            // uiwait 超时【不做】卡死恢复 —— 它等的是「照片回传完成」这类
            // 页面内部的状态变化，此时 BACK 退回列表再点卡片会把整条流程搞乱。
            if (step.kind == "wait" || step.kind == "uniwait" || step.kind == "textwait") {
                var node = awaitPageReady(step)
                if (node == null && step.kind == "wait") {
                    // 一遍遍 BACK 退回 + 重新点开卡片，直到页面加载出来。
                    //
                    // ⚠️ 注意 health-wx：这是点过「刷新」之后的死状态，
                    //    这时重进打开的往往还是同一个死掉的 WebView ——
                    //    实测重进确实经常无效，但**也有救回来的时候**，
                    //    所以只提示、不中断，该试的还是要试完。
                    // ⚠️ 2026-09-25 21:20 修正：调试模式【不再】削减重进次数。
                    //
                    //   之前为了「日志别太乱」把调试模式的重进压到 1 次，真机现象就是
                    //   「页面卡在加载中却一遍都不多试、第二遍就停住了」——
                    //   把流程自身的容错能力当成噪声砍掉，属于误判。
                    //
                    //   该收敛的是【外层整轮重来】（会 force-stop 目标 App、重走
                    //   deeplink、现场被清空），那部分在 runAll() 里由 debug 控制；
                    //   而这里的「BACK 退回 + 重新点开卡片」是【页面级容错】，
                    //   必须给足 —— 签到页卡「加载中」是常态，实测往往要重进
                    //   一两次才出来，只给 1 次等于提前宣布失败。
                    //
                    // ── 以下是重进策略本体 ──
                    //
                    // 「BACK 重进」与「进程级冷启动」交替成组，顺序由用户 2026-09-25 拍定：
                    //
                    //   第 1 组：BACK × 3（各等 6 秒）→ 仍不出现 → 冷启动一次
                    //   第 2 组：BACK × 3（各等 6 秒）→ 仍不出现 → 冷启动一次
                    //
                    // 为什么两者交替而不是择一：它们治的是【不同的卡死】。
                    //   · BACK 重进便宜（约 7 秒），治页面懒加载慢 —— 多数一两次就成；
                    //   · 冷启动贵（约 30 秒），治 WebView【实例】级死锁 —— 进程不死，
                    //     重新点卡片打开的就是同一个坏实例，BACK 跑多少轮都是空转。
                    // 冷启动之后再补一组 BACK 也不算重复劳动：那时页面刚被重置、又没
                    // 加载出来，正是重新触发加载最值得一试的窗口期。
                    //
                    // ⚠️ 这个顺序【不许被任何检测短路】。health-wx 死状态只提示、
                    //    不改变流程 —— 早期版本检测到它就直奔冷启动，等于把用户要求的
                    //    「先试满 BACK」整段跳过（注释写着"不中断"，代码却在中断）。
                    if (StepEngine.isH5Dead(this)) {
                        Logger.log(
                            this,
                            "   ⚠️ 当前是 health-wx 死状态（多半点过刷新）：BACK 重进多半救不回同" +
                                "一个死 WebView，但仍按顺序把 $BACK_TRIES_PER_GROUP 次试满"
                        )
                    }
                    recoverLoop@ for (group in 1..COLD_GROUPS) {
                        for (tryIdx in 1..BACK_TRIES_PER_GROUP) {
                            Logger.log(
                                this,
                                "   第 $group/$COLD_GROUPS 组 · BACK 重进（第 $tryIdx/" +
                                    "$BACK_TRIES_PER_GROUP 次）：BACK 退回后重新点开卡片"
                            )
                            if (!recoverFromStuckCard(steps, i)) {
                                // ⚠️ 这里【不能 break】。
                                //
                                //   找不到卡片往往只是「H5 里还压着一层没退干净」，
                                //   下一轮 recoverFromStuckCard 会先 BACK 再找，多半就好了。
                                //   之前一 break 等于「页面状态稍乱就一次都不多试」，
                                //   和用户「一直 BACK 再点，直到加载成功」的思路相悖 ——
                                //   真机反馈的「一遍就停住」有这部分原因。
                                Logger.log(this, "   退回后暂未见卡片，下一轮再退一层试试")
                                continue
                            }
                            // 重进后是热缓存，等待短一些
                            node = awaitPageReady(step, RETRY_WAIT_MS)
                            if (node != null) {
                                Logger.log(
                                    this,
                                    "   ✓ 第 $group 组第 $tryIdx 次 BACK 重进后加载成功"
                                )
                                break@recoverLoop
                            }
                        }

                        // 本组 BACK 试满仍未出现 → 升级为进程级冷启动
                        Logger.log(
                            this,
                            "   ⟳ 第 $group 组：BACK 重进 $BACK_TRIES_PER_GROUP 次均无效，" +
                                "改用「进程级冷启动 + 重走入口」"
                        )
                        coldRestartAndReenter(steps, i)
                        node = awaitPageReady(step, COLD_RESTART_WAIT_MS)
                        if (node != null) {
                            Logger.log(this, "   ✓ 第 $group 组冷启动后加载成功")
                            break@recoverLoop
                        }
                        Logger.log(
                            this,
                            "   第 $group 组冷启动后仍未加载出来" +
                                (if (group < COLD_GROUPS) "，进入下一组" else "，本页兜底已用尽")
                        )
                    }
                }
                if (node != null) {
                    Logger.log(this, "   ✓ 已出现：${step.value}")
                } else {
                    Logger.log(
                        this,
                        if (step.kind == "uniwait") {
                            "   ✗ 仍未出现「${step.value}」，页面内部状态没变"
                        } else {
                            "   ✗ 仍未见「${step.value}」，本页重进与冷启动均无效"
                        }
                    )
                    // 两级兜底都用尽了 —— 把此刻的界面拍进日志。
                    // 否则回头只看到一句「仍未见」，屏幕上是哪一屏、是加载中还是报错页
                    // 全都无从判断，白瞎一次现场。
                    dumpWindowsNow("wait=${step.value} 重进与冷启动均未就绪")
                    if (!step.optional) return false
                }
                Thread.sleep(300)
                continue
            }

            // 静置步骤：什么都不做，只等一会儿。
            // 「文案出现」≠「组件就绪」—— H5 里的地图 / 定位 / 相机都是异步初始化的，
            // 页面文字都渲染出来了，此刻点里面的按钮仍可能石沉大海。
            if (step.kind == "sleep") {
                val ms = step.value.toLongOrNull()?.coerceIn(200L, 30000L) ?: 1000L
                Logger.log(this, "   · 静置 ${ms}ms（等页面内部组件初始化）")
                Thread.sleep(ms)
                continue
            }

            // 滚动步骤：把列表滚到底，让最新一条内容可见（value = 容器 id 或 auto）
            if (step.kind == "scroll") {
                val ok = StepEngine.scrollToBottom(this, step.value)
                Logger.log(this, if (ok) "   ✓ 已滚动到底部" else "   ✗ 未找到可滚动容器")
                if (!ok && !step.optional) return false
                Thread.sleep(800)
                continue
            }

            // 节点查找步骤。uni 是字体图标，同样要求真的渲染出来了才算数。
            val node = StepEngine.awaitNode(this, step, step.waitMs, requireVisible = step.kind == "uni")
            if (node == null) {
                Logger.log(this, "   ✗ 未找到控件")
                // 自动留现场：把此刻的活动窗口 + 节点树打进日志。
                // 「页面明明看得见、就是找不到控件」这类问题，光靠回头猜浪费时间，
                // 直接把节点树拍下来最快。
                dumpWindowsNow("步骤 ${i + 1} ${step.kind}=${step.value} 未找到控件")
                if (!step.optional) return false
                continue
            }
            val ok = StepEngine.click(this, node)
            Logger.log(this, if (ok) "   ✓ 已点击" else "   ✗ 点击未生效")
            if (!ok && !step.optional) return false

            Thread.sleep(700)
        }
        return true
    }

    /**
     * 等页面就绪：分片轮询，既等目标出现，也能及时发现卡死。
     *
     * 一次性等满 30 秒的话，卡死场景要白等半分钟。这里每 2 秒回看一次，
     * 一旦「已进页面超过 8 秒」且屏幕还挂着「加载中...」，就提前判死返回 null，
     * 把恢复动作交给上层。
     *
     * 8 秒这个宽限期是必要的：刚点进卡片时页面本来就会短暂显示「加载中...」，
     * 不能一见就判死。
     */
    /**
     * 等页面就绪：分片轮询，直到目标出现或超时。
     *
     * @param timeoutMs 覆盖步骤自带的 waitMs。重进后可用更短的时长，
     *                  因为此时页面资源已在缓存里。
     */
    private fun awaitPageReady(step: Step, timeoutMs: Long = step.waitMs): AccessibilityNodeInfo? {
        val slice = 2000L
        var waited = 0L
        while (waited < timeoutMs) {
            // ⚠️ 只有 uiwait 才要求「真的渲染出来了」。
            //
            //   uiwait 等的是 H5 里可能尚未渲染的元素（如「提交签到」按钮，
            //   它在照片上传完成前 bounds 恒为 [0,0][0,0]），必须判尺寸。
            //
            //   而普通 wait 等的是原生文案节点（如签到页的「签到要求」），
            //   它天然有 bounds；若这里也强判尺寸，页面慢加载期间节点
            //   刚好没布局好就会被自己挡掉 —— v1.9/v2.0 就是这么把
            //   本来能成的步骤判死的。
            val node = StepEngine.awaitNode(this, step, slice, requireVisible = step.kind == "uniwait")
            if (node != null) return node
            waited += slice
        }
        // 等满超时后再回头看一眼是不是卡在「加载中...」，纯粹为了日志好读：
        // 「页面慢」和「页面死了」是两回事，日志里分开讲才不会误判。
        if (StepEngine.hasLoadingOverlay(this)) {
            Logger.log(this, "   等满 ${timeoutMs / 1000}s 仍未就绪，此刻页面还挂着「加载中...」")
        }
        return null
    }

    /**
     * 解析一个坐标分量：支持绝对像素（"540"）与屏幕比例（"50%"）。
     *
     * 比例写法是为「读不到 id 的裸控件」准备的 —— 比如签到页内嵌相机的快门
     * cb_capture，它是个既无文字、又不可点击、resource-id 还拿不到的 View，
     * 按 id 永远找不到，只能按位置点。用比例写就跨分辨率了。
     *
     * ⚠️ 比例的分母是 `resources.displayMetrics` 那一套尺寸，
     *    不是物理屏幕分辨率。真机实测（华为 JEF-AN00 / 2026-09-25）：
     *    物理屏 1080x2400，而 displayMetrics.heightPixels = **2273**
     *    （扣掉了导航栏）。写 86% 会算成 y=1954，落在快门上方点空；
     *    快门的实际范围是 2010~2273，中心 2141，也就是 **94% 附近**。
     *    换机型时若坐标对不上，先想这一层。
     */
    private fun parseCoord(raw: String?, total: Int): Float? {
        val s = raw?.trim() ?: return null
        if (s.isEmpty()) return null
        return if (s.endsWith("%")) {
            s.dropLast(1).trim().toFloatOrNull()?.let { total * it / 100f }
        } else {
            s.toFloatOrNull()
        }
    }

    /**
     * 卡死恢复：退掉卡住的 H5 页，重新点一次签到卡片再进来。
     *
     * 真机实测（2026-09-25）：卡死时一次 BACK 就能回到通知中心，
     * 不必退到 App 首页、更不必重走 deeplink，几秒即可完成。
     *
     * @param steps     完整步骤表，用来回放「进卡片」那一步
     * @param waitIndex 当前 wait 步骤的下标；只回放它【之前】的点击步骤，避免误点后续操作
     */
    private fun recoverFromStuckCard(steps: List<Step>, waitIndex: Int): Boolean {
        // 取「打开签到页」那一步：锚定到【第一个 wait 之前】最后一条点击类步骤。
        // 判据抽在 firstWaitIndex() 里，冷启动重进共用同一套逻辑，避免两处走岔。
        val limit = firstWaitIndex(steps, waitIndex)
        val before = steps.take(limit).filter {
            it.kind == "id" || it.kind == "text" || it.kind == "desc"
        }
        // 优先取 id：进卡片用的是资源 id，最靠谱；text/desc 仅作兜底。
        val cardStep = before.lastOrNull { it.kind == "id" } ?: before.lastOrNull()
        if (cardStep == null) {
            Logger.log(this, "   步骤表里没有「进入卡片」的步骤，无法原路重进")
            return false
        }

        performGlobalAction(GLOBAL_ACTION_BACK)
        Thread.sleep(1800)

        // 注意这里都用短超时：重进是循环动作，单轮必须快，
        // 否则「多试几次」会变成几分钟的空等（用户明确嫌慢）。
        var card = StepEngine.awaitNode(this, cardStep, 4000)
        if (card == null) {
            // H5 里可能还压着一层历史，再退一层
            Logger.log(this, "   一次 BACK 后仍未回到列表，再退一层")
            performGlobalAction(GLOBAL_ACTION_BACK)
            Thread.sleep(1500)
            card = StepEngine.awaitNode(this, cardStep, 5000)
        }
        if (card == null) {
            Logger.log(this, "   退回后没找到签到卡片（${cardStep.value}）")
            return false
        }

        if (!StepEngine.click(this, card)) {
            Logger.log(this, "   重新点击签到卡片失败")
            return false
        }
        Logger.log(this, "   已重新点开卡片，重新等待页面加载")
        Thread.sleep(800)
        return true
    }

    /**
     * 进程级冷启动：把目标 App 真正做掉，而不是一路按返回键。
     *
     * 为什么非它不可：签到页是 H5，「加载中」卡死和 health-wx 死状态都发生在
     * WebView **实例**内部。只要进程还活着，重新点卡片打开的就是同一个实例 ——
     * 页面级 BACK 重进跑 8 次、外层整轮重来再跑 3 轮，全是空转。
     * 唯有让进程死掉，下次 deeplink 才会拉起一个干净的 WebView。
     *
     * 三步走：
     *   ① 先回桌面。killBackgroundProcesses 只能杀【后台】进程，
     *      目标 App 还在前台时调用等于空转（系统直接忽略）。
     *   ② 杀后台进程。
     *   ③ 回读前台窗口验证。部分 ROM 会拦这条请求，所以不假装成功 ——
     *      杀没杀掉如实写进日志。
     *
     * 权限：KILL_BACKGROUND_PROCESSES 是 normal 级，普通应用声明即可。
     *
     * @return 是否确认已清掉目标进程（返回 false 不代表流程终止，调用方照旧往下走）
     */
    private fun hardResetTargetApp(reason: String): Boolean {
        val pkg = ConfigStore.targetPkg(this)
        if (pkg.isEmpty()) {
            Logger.log(this, "   ⚠️ 未配置目标包名，跳过进程级重启（$reason）")
            return false
        }
        Logger.log(this, "   ⟳ 进程级冷启动：$reason")

        // ① 回桌面 —— 目标 App 必须退到后台，杀进程请求才会被系统受理
        performGlobalAction(GLOBAL_ACTION_HOME)
        Thread.sleep(1500)

        // ② 杀进程。WebView 实例随进程一起消失，下次进来才是干净的
        try {
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            am.killBackgroundProcesses(pkg)
        } catch (t: Throwable) {
            Logger.log(this, "   ✗ 杀进程请求失败：${t.message}（继续按原路重来）")
            return false
        }

        Thread.sleep(2500)

        // ③ 验证：还在前台说明没杀掉
        val now = rootInActiveWindow?.packageName?.toString().orEmpty()
        if (now == pkg) {
            Logger.log(this, "   ⚠️ $pkg 仍在运行，系统可能拦截了杀进程请求")
            return false
        }
        Logger.log(this, "   ✓ 已清掉 $pkg 进程（当前前台：${now.ifEmpty { "未知" }}）")
        return true
    }

    /**
     * 冷启动后重新回到签到页。
     *
     * 进程被杀 → App 回到桌面 → 必须从 deeplink 重新进 App，再把
     * 「切 Tab / 滚动 / 点卡片」这些前置动作重放一遍，才能回到签到页。
     * runOnce 里本来也有这套动作，但那是「从零开始」的语境；
     * 这里是【流程中途折返】，得一比一复刻同样的路子才能落回同一个位置。
     *
     * 只回放能改变页面位置的步骤（tab / scroll / 点击），
     * sleep 和 wait 一律跳过 —— 它们是给「第一次进入」排的节奏，
     * 这里只需要尽快把界面挪回签到页。
     */
    private fun coldRestartAndReenter(steps: List<Step>, waitIndex: Int) {
        hardResetTargetApp("页面级重进无效，改用冷启动")

        val direct = launchEntry()

        // 冷启动是全新进程：App 启动 + WebView 内核初始化 + H5 首屏，
        // 比热缓存重进慢得多。先等目标 App 真的回到前台再往下走，
        // 否则后面的前置步骤全是对着桌面空点。
        val pkg = ConfigStore.targetPkg(this)
        var waited = 0
        while (waited < 15000) {
            val now = rootInActiveWindow?.packageName?.toString().orEmpty()
            if (pkg.isEmpty() || now == pkg) break
            Thread.sleep(1000)
            waited += 1000
        }
        Thread.sleep(3000)

        val limit = firstWaitIndex(steps, waitIndex)
        for (s in steps.take(limit)) {
            when (s.kind) {
                // 入口已直达通知中心时，切 Tab 反而是把界面切走的多余动作
                "tab" -> {
                    if (direct) continue
                    StepEngine.awaitNode(this, s, 4000)?.let { StepEngine.click(this, it) }
                    Thread.sleep(900)
                }
                "scroll" -> {
                    StepEngine.scrollToBottom(this, s.value)
                    Thread.sleep(700)
                }
                "id", "text", "desc", "cls", "uni" -> {
                    StepEngine.awaitNode(this, s, 5000)?.let { StepEngine.click(this, it) }
                    Thread.sleep(900)
                }
                else -> Unit
            }
        }
        Logger.log(this, "   已重走入口与前置步骤，回到签到页")
    }

    /**
     * 取「打开签到页」那一步的下标 —— 即第一个等待步骤的位置。
     *
     * ⚠️ 不能用调用方传来的当前下标截断：步骤表里等待步骤不止一个
     *    （「签到要求」是签到页加载完、「已经进入签到要求范围」是定位完成），
     *    按第二个截断会把「重新定位」这种【签到页内部】按钮算进来 ——
     *    退回列表后当然找不到它，表现就是「退出去就再也回不来」。
     */
    private fun firstWaitIndex(steps: List<Step>, fallback: Int): Int {
        val i = steps.indexOfFirst {
            it.kind == "wait" || it.kind == "uniwait" || it.kind == "textwait"
        }
        return if (i >= 0) i else fallback
    }

    /**
     * 点亮屏幕，并等它真正进入 interactive 状态；顺带处理滑动锁屏。
     *
     * ⚠️ 时序很关键（2026-09-25 实测教训）：
     *    Android 12 的「后台启动 Activity」判定发生在 startActivity() 那一刻，
     *    判据之一就是「调用方 uid 有没有可见窗口」。而 1×1 悬浮窗在屏幕点亮前
     *    不算可见 —— 所以「唤醒屏幕」和「发起跳转」之间必须留出余量。
     *    早先版本从「已唤醒屏幕」到「发起跳转」只隔 1 秒，跳转照样被拦。
     *
     * 另外 WakeLock 改为持有整条流程（不再 acquire 完立刻 release）：
     *    中途熄屏会让无障碍抓不到窗口，后续所有点击都会落空。
     */
    private fun wakeScreen() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isInteractive) {
                screenLock?.let { if (it.isHeld) it.release() }
                @Suppress("DEPRECATION")
                val wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "autocheckin:wake"
                )
                wl.acquire(120_000L)   // 2 分钟足够跑完整个流程，超时自动释放
                screenLock = wl
                Logger.log(this, "已唤醒屏幕")
            }

            // 等屏幕真正 interactive
            var waited = 0
            while (!pm.isInteractive && waited < 5000) {
                Thread.sleep(300)
                waited += 300
            }
            if (waited > 0) Logger.log(this, "屏幕已就绪（等待 ${waited}ms）")

            // 豁免探针必须在跳转前处于生效状态；若服务连接时没挂上（权限缺失），这里补一次
            if (!OverlayProbe.show(this)) {
                Logger.log(this, "⚠️ 无后台跳转豁免探针，deeplink 很可能被系统丢弃")
            }

            Thread.sleep(800)   // 给 WindowManager 一点时间把窗口真正铺出来

            val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            if (km.isKeyguardLocked) {
                Logger.log(this, "检测到锁屏，尝试上滑解锁（仅滑动锁屏有效，密码锁屏需人工处理）")
                val dm = resources.displayMetrics
                StepEngine.swipeUp(this, dm.widthPixels, dm.heightPixels)
                Thread.sleep(1500)
            }
        } catch (t: Throwable) {
            Logger.log(this, "唤醒屏幕失败: ${t.message}")
        }
    }

    /**
     * 打开签到入口。
     *
     * 降级链：配置里的入口 → 扫码入口（SCAN_INTENT）→ 主页入口（HOME_INTENT）。
     * 用户 2026-09-25 已拍定「扫码优先」，故默认配置就是扫码链接（直达通知中心）；
     * 扫码凭证一旦失效，会自动降级到主页 scheme，再由步骤表切「消息」Tab 进入。
     *
     * @return 是否「直达通知中心」，用于决定步骤表要不要跳过切 Tab 那一步
     */
    private fun launchEntry(): Boolean {
        val raw = ConfigStore.intentUrl(this).trim()
        val target = ConfigStore.targetPkg(this)

        val candidates = ArrayList<String>(3)
        if (raw.isNotEmpty()) candidates.add(raw)
        for (extra in listOf(ConfigStore.SCAN_INTENT, ConfigStore.HOME_INTENT)) {
            if (extra.isNotEmpty() && !candidates.contains(extra)) candidates.add(extra)
        }

        if (candidates.isEmpty()) {
            Logger.log(this, "未配置签到入口链接，跳过跳转（直接执行步骤表）")
            return false
        }

        for ((idx, entry) in candidates.withIndex()) {
            val label = if (idx == 0) "主入口" else "备用入口$idx"
            if (!tryLaunch(entry)) continue

            Thread.sleep(5000)
            val now = rootInActiveWindow?.packageName?.toString().orEmpty()
            if (now.isEmpty()) {
                // 跳转指令发出去了，但前台窗口读不到 —— 典型的「后台启动被拦」症状。
                // 判定细节见 OverlayProbe 类注释：系统日志里会出现
                //   activity start fail: Background activity start ... allowBackgroundActivityStart: false
                Logger.log(
                    this,
                    "$label 跳转未生效（取不到前台窗口）—— App 在后台时这是被 " +
                        "「后台启动 Activity」拦下的典型症状。豁免探针生效=${OverlayProbe.active}；" +
                        "若为 false，请到 设置 → 应用 → FAFU签到 → 显示在其他应用上层 打开开关"
                )
                continue
            }
            if (target.isNotEmpty() && now != target) {
                Logger.log(this, "$label 未进入目标 App（当前 $now），尝试下一个入口")
                continue
            }
            val cls = lastWindowClass
            val direct = cls.contains("CategoryChatActivity", ignoreCase = true)
            val page = cls.substringAfterLast('.').ifEmpty { "未知" }
            Logger.log(
                this,
                "$label 已进入目标 App（$now）当前页 $page" + if (direct) " → 已直达通知中心 ✅" else ""
            )
            return direct
        }
        Logger.log(this, "⚠️ 所有入口均未确认进入目标 App，仍继续执行步骤表")
        return false
    }

    /** 解析并启动单个入口链接，返回是否成功发出启动请求 */
    private fun tryLaunch(raw: String): Boolean {
        // 归一化：兼容用户从 deeplink 里只截到 "#Intent;...;end" 的情况
        val url = if (raw.startsWith("#Intent;", ignoreCase = true)) "intent://$raw" else raw

        val intent: Intent? = try {
            if (url.startsWith("intent:", ignoreCase = true)) {
                Intent.parseUri(url, 0)
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
            }
        } catch (t: Throwable) {
            Logger.log(this, "标准解析失败（${t.message}），改用手动解析")
            parseIntentManually(raw)
        }

        if (intent == null) {
            Logger.log(this, "❌ 无法构造跳转 Intent")
            return false
        }

        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            val desc = intent.component?.flattenToShortString() ?: (url.take(48) + "…")
            Logger.log(this, "已发起跳转：$desc")
            true
        } catch (t: Throwable) {
            Logger.log(this, "启动 intent 失败: ${t.message}")
            false
        }
    }

    /**
     * 手动解析 "#Intent;...;end" 片段（parseUri 失败或含厂商私有键时兜底）。
     * 支持 component / package / action / scheme / S. B. i. l. 前缀的 extras。
     */
    private fun parseIntentManually(raw: String): Intent? {
        val body = raw.substringAfter("#Intent;", "")
            .removeSuffix("end")
            .trimEnd(';')
        if (body.isEmpty()) return null

        val intent = Intent()
        var component: String? = null

        for (seg in body.split(';')) {
            if (seg.isBlank()) continue
            val eq = seg.indexOf('=')
            if (eq <= 0) continue
            val key = seg.substring(0, eq)
            val value = seg.substring(eq + 1)

            when {
                key == "component" -> component = value
                key == "package" -> intent.setPackage(value)
                key == "action" -> intent.action = value
                key == "scheme" -> intent.data = Uri.parse(value)
                key.startsWith("S.") -> intent.putExtra(key.substring(2), value)
                key.startsWith("i.") -> value.toIntOrNull()?.let { intent.putExtra(key.substring(2), it) }
                key.startsWith("l.") -> value.toLongOrNull()?.let { intent.putExtra(key.substring(2), it) }
                key.startsWith("b.") -> intent.putExtra(key.substring(2), value.toBoolean())
                // launchFlags / extendedLaunchFlags 等非必需项直接忽略
            }
        }

        component?.let {
            val parts = it.split('/')
            if (parts.size == 2) intent.setClassName(parts[0], parts[1])
        }

        Logger.log(
            this,
            "手动解析结果：component=${component ?: "无"} package=${intent.`package` ?: "无"}"
        )

        return if (intent.component != null || intent.`package` != null || intent.action != null) {
            intent
        } else {
            null
        }
    }
}
