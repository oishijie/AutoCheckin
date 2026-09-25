package com.xiaoyao.autocheckin

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 步骤执行引擎：在无障碍节点树上查找控件并点击。
 * 所有方法均设计为可在后台线程调用。
 */
object StepEngine {

    /**
     * 系统手势导航在屏幕底部保留的「手势区」高度（dp）。
     *
     * 落在这条带子里的无障碍手势会被系统当成导航手势吞掉。
     * 真机实测（华为 JEF-AN00 / 1080x2400 / 480dpi / 手势导航）：
     * 目标 App 底部 Tab 图标 bounds=[72,2253][144,2273]，中心 y=2263，
     * 距屏幕底边 2400-2263=137px，整块都压在 48dp(≈144px) 的手势区里，
     * 所以「点消息 Tab」怎么都点不动。而 adb 的 input tap 走 shell 注入通道、
     * 优先级高于手势识别，同一个坐标用 adb 手点却能成功 —— 这正是排查时
     * 最容易把人带偏的地方。
     */
    private const val SYSTEM_GESTURE_DP = 48f

    /**
     * 阻塞等待控件出现，直到超时。返回 null 表示没找到。
     *
     * @param requireVisible 是否要求节点【真的占据屏幕区域】。
     *
     * H5（WebView）里的元素会被挂进无障碍树，理论上存在「挂上了但还没渲染完」
     * 的中间态（bounds 为 [0,0][0,0]）。viswait / uiwait 这类工具型等待会打开
     * 这个开关，把这种节点排除掉 —— 对未渲染的节点谈「找到了」没有意义。
     *
     * ⚠️ 判据只认【无障碍服务读到的 bounds】。**别拿 `uiautomator dump` 的结果
     *    下结论**：它对 WebView 虚拟节点的上报有缺陷 —— 2026-09-25 实测，
     *    一个明明渲染在屏幕底部、显示为可点蓝色的「提交签到」按钮
     *    （无障碍服务读到 [69,2278][1011,2374] 942x96），
     *    uiautomator dump 却报成 [0,0][0,0]。截屏核对后才确认是工具假象。
     */
    fun awaitNode(
        svc: AccessibilityService,
        step: Step,
        timeoutMs: Long,
        requireVisible: Boolean = false
    ): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val node = findOnce(svc, step, requireVisible)
            if (node != null) return node
            if (System.currentTimeMillis() >= deadline) return null
            Thread.sleep(250)
        }
    }

    /** 节点是否真的占据屏幕区域（未渲染的元素 bounds 为 [0,0][0,0]） */
    fun hasSize(node: AccessibilityNodeInfo): Boolean {
        val r = Rect().also { node.getBoundsInScreen(it) }
        return r.width() > 0 && r.height() > 0
    }

    private fun findOnce(
        svc: AccessibilityService,
        step: Step,
        requireVisible: Boolean = false
    ): AccessibilityNodeInfo? {
        val root = svc.rootInActiveWindow ?: return null
        val hits = ArrayList<AccessibilityNodeInfo>(8)
        collect(root, step, hits)
        if (hits.isEmpty()) return null

        // 等待类步骤额外剔除「找到了但没渲染」的元素，理由见 awaitNode 注释
        val usable = if (requireVisible) hits.filter { hasSize(it) } else hits
        if (usable.isEmpty()) return null

        // 命中多个时，优先选「自身或祖先可点击」的那个。
        //
        // 例：停在通知中心页时，页面顶部【标题栏】就有「通知中心」四个字（不可点），
        // 而会话列表项也叫「通知中心」（其祖先 recent_item_lay 可点）。
        // DFS 会先遍历到标题栏，只取 first() 就会点到标题栏 ——
        // 表现为「点击未生效」，且坐标落在 y=171 的顶部，看着像点击被吞。
        val clickableOnes = usable.filter { clickableAncestor(it) != null }
        val pool = if (clickableOnes.isNotEmpty()) clickableOnes else usable

        // last=true 时从后往前找：目标 App 的消息列表是「旧在上、最新在下」，
        // 取最后一个才能命中当天最新的签到提醒。
        return if (step.last) pool.last() else pool.first()
    }

    /** 沿父链向上找第一个可点击节点；找不到返回 null */
    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 8) {
            if (cur.isClickable) return cur
            val p = cur.parent
            cur = if (p === cur) null else p
            depth++
        }
        return null
    }

    private fun collect(
        node: AccessibilityNodeInfo,
        step: Step,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (matches(node, step)) out.add(node)
        val n = node.childCount
        for (i in 0 until n) {
            val child = node.getChild(i) ?: continue
            collect(child, step, out)
        }
    }

    private fun matches(node: AccessibilityNodeInfo, step: Step): Boolean {
        return when (step.kind) {
            "id" -> {
                val id = node.viewIdResourceName ?: return false
                id == step.value || id.endsWith("/" + step.value)
            }
            // tab 步骤在「入口已直达通知中心」时会被调用方跳过。
            // 它同时接受 contentDescription 与 text 两种匹配：
            // 底部 Tab 靠 desc（"消息"），而会话列表项靠 text（"通知中心"）。
            "tab" -> hit(node.contentDescription?.toString(), step) ||
                hit(node.text?.toString(), step)
            "desc" -> {
                val d = node.contentDescription?.toString() ?: return false
                if (step.contains) d.contains(step.value) else d == step.value
            }
            // wait / text / textwait / viswait 同源：match 规则一致，区别只在于
            // 「等超时之后怎么办」和「要不要判尺寸」—— 那部分在 Service 里，不在匹配层。
            "wait", "text", "textwait", "viswait" -> {
                val t = node.text?.toString() ?: return false
                if (step.contains) t.contains(step.value) else t == step.value
            }
            // uni / uiwait：按字体图标的 Unicode 码点匹配 text（value 写十六进制，如 e62b）。
            //
            // 签到页的「拍照」入口是个【私有字体图标】：没有 id、没有 contentDescription，
            // text 就是一个私用区码点（\ue62b），在编辑框里看着像空白方块。
            // 但它 clickable=true、跨分辨率位置稳定，比坐标可靠得多。
            //
            // ⛔ 注意：曾经以为「拍照成功后该码点会变成 \ue605」，并用它当⑮步的回传判据 ——
            //    **这个假设是错的**（2026-09-25 23:19 真机坐实）：照片回传后渲染的是真实
            //    <img> 缩略图，对无障碍就是 text 为空的节点，根本没有 e605 这个码点。
            //    该判据会让流程每轮空等 40 秒后判失败，永远到不了提交。
            //    正确判据是「提交签到」按钮渲染出尺寸（viswait），详见 Step.kt ⑯。
            "uni", "uniwait" -> {
                val t = node.text?.toString() ?: return false
                if (t.isEmpty()) return false
                val target = parseCodePoint(step.value) ?: return false
                t.any { it.code == target }
            }
            "cls" -> node.className?.toString()?.contains(step.value) == true
            else -> false
        }
    }

    /**
     * 解析十六进制码点，兼容 "e62b" / "U+e62b" / "0xe62b" / "\ue62b" 几种写法。
     * 认不出来返回 null（该步骤直接判为不匹配，不会误点别的控件）。
     */
    private fun parseCodePoint(raw: String): Int? {
        val s = raw.trim()
            .removePrefix("\\u")
            .removePrefix("U+").removePrefix("u+")
            .removePrefix("0x").removePrefix("0X")
        return s.toIntOrNull(16)
    }

    /** 单值匹配：contains=true 走包含判断，否则要求精确相等 */
    private fun hit(s: String?, step: Step): Boolean {
        if (s == null) return false
        return if (step.contains) s.contains(step.value) else s == step.value
    }

    /**
     * 页面是否停在「加载中...」遮罩上（H5 卡死的判据）。
     *
     * 真机实测（华为 JEF-AN00 / 签到页 BrowserActivity / 2026-09-25）：
     * 卡死时标题栏 mainTitle 会从「签到」变成「health-wx」，
     * WebView 里只剩一个「加载中...」的 TextView，位置 [465,1366][615,1426]。
     */
    fun hasLoadingOverlay(svc: AccessibilityService): Boolean {
        val root = svc.rootInActiveWindow ?: return false
        return containsText(root, "加载中")
    }

    /**
     * H5 死状态判据：标题栏变成 `health-wx`。
     *
     * 真机实测（2026-09-25）：这个页面一旦变成这样，WebView 里就只剩一个
     * 「加载中...」，**且不会自愈** —— BACK 退回、重新点卡片打开的还是同一个
     * 死掉的 WebView 实例，重试多少次都在转圈。
     * 唯一的解法是彻底重启目标 App（force-stop 后重开）。
     *
     * 所以识别出它就别再浪费轮次重进了，直接判失败让外层走「退出 App 重来」。
     */
    fun isH5Dead(svc: AccessibilityService): Boolean {
        val root = svc.rootInActiveWindow ?: return false
        return containsText(root, "health-wx")
    }

    private fun containsText(node: AccessibilityNodeInfo, keyword: String): Boolean {
        val t = node.text?.toString()
        if (t != null && t.contains(keyword)) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (containsText(child, keyword)) return true
        }
        return false
    }

    /**
     * 点击节点。
     *
     * ① 先沿父链向上找可点击祖先，走无障碍原生 ACTION_CLICK（最可靠、不经触摸注入）；
     * ② 若整条父链都 clickable=false（目标 App 的底部 Tab 正是如此），
     *    退化为坐标手势，落点取「比自身稍大的最近祖先容器」内部靠上的位置，
     *    尽量远离屏幕底边的手势区。
     */
    fun click(svc: AccessibilityService, node: AccessibilityNodeInfo): Boolean {
        // ⚠️ 未渲染的节点（bounds 为 [0,0][0,0]）一律不点。
        //
        // 对 0×0 节点派发 ACTION_CLICK 是【最坏的情况】：无障碍框架不校验节点尺寸，
        // 可能照样返回 true，于是日志打出「✓ 已点击」、屏幕上却什么都没发生 —— 假阳性。
        // 签到这种事必须确认真的点下去了，宁可明确失败、由上层重试。
        //
        // 这是【防御性】检查，正常流程不会触发：2026-09-25 实测过签到页的
        // 「提交签到」按钮，无障碍服务读到的一直是 [69,2278][1011,2374]（942x96）、
        // visibleToUser=true，截屏核对也确认它就好好地渲染在屏幕底部。
        // （当时一度以为它是 0×0，那是 uiautomator dump 的误报，详见 hasSize 上方注释。）
        if (!hasSize(node)) {
            Logger.log(svc, "   · 目标控件尺寸为 0（疑似未渲染），放弃点击以免假阳性")
            return false
        }

        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 8) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = if (cur.parent === cur) null else cur.parent
            depth++
        }

        val r = pickClickBounds(node)
        if (r.width() <= 0 || r.height() <= 0) return false
        // 水平中心 + 垂直靠上 1/3：既落在容器内部，又离底边更远
        val x = r.centerX().toFloat()
        val y = r.top + r.height() / 3f
        return tap(svc, x, y)
    }

    /**
     * 挑选用于坐标落点的范围。
     *
     * 叶子节点常常非常小（底部 Tab 图标只有 20px 高），直接取它的中心点，
     * 落点会贴到屏幕最底边。这里改取「比自身更大的最近祖先容器」，
     * 让落点落在容器内部、离边缘更远。
     */
    private fun pickClickBounds(node: AccessibilityNodeInfo): Rect {
        val self = Rect().also { node.getBoundsInScreen(it) }
        var best = Rect(self)
        var cur: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (cur != null && depth < 4) {
            // 取局部 val：cur 是 var，直接丢进 lambda 会丢失智能转换
            val c = cur
            val r = Rect()
            c.getBoundsInScreen(r)
            val bigger = r.width() > best.width() || r.height() > best.height()
            // 别一路放大到整屏：容器高度超过自身 6 倍就认为太泛，丢弃
            val usable = r.height() < self.height() * 6
            if (bigger && usable) best = r
            val p = c.parent
            cur = if (p === c) null else p
            depth++
        }
        return best
    }

    /**
     * 坐标点击（手势派发，API 24+）。
     *
     * ⚠️ 关键：dispatchGesture 的返回值只表示「手势已成功派发」，
     * **不代表点击真的生效**。必须用 GestureResultCallback 拿到
     * onCompleted / onCancelled 才能判断真实结果，否则会出现
     * 「日志全是 ✓ 已点击、界面纹丝未动」的假阳性（这个坑已经踩过）。
     *
     * 一旦手势被系统取消（典型原因：落点位于屏幕底部的手势导航区），
     * 依次把落点上移重试。
     */
    fun tap(svc: AccessibilityService, x: Float, y: Float): Boolean {
        if (dispatchAndWait(svc, x, y)) return true

        // 被取消 → 先按「手势区安全线」上移
        val dm = svc.resources.displayMetrics
        val safeBottom = dm.heightPixels - (SYSTEM_GESTURE_DP * dm.density)
        if (y > safeBottom) {
            val shifted = safeBottom - 16f
            if (shifted > 0f && dispatchAndWait(svc, x, shifted)) {
                Logger.log(svc, "   · 落点在系统手势区内，上移至 y=$shifted 后点击成功")
                return true
            }
        }

        // 再退一步：沿垂直方向逐步上移，找一个系统肯放行的落点
        for (dy in intArrayOf(24, 48, 72, 96)) {
            val ny = y - dy
            if (ny <= 0f) break
            if (dispatchAndWait(svc, x, ny)) return true
        }
        Logger.log(svc, "   · 手势点击始终未生效（x=$x, y=$y）")
        return false
    }

    /** 派发一次点击手势并等待系统回调；只有 onCompleted 才算真正点下去 */
    private fun dispatchAndWait(svc: AccessibilityService, x: Float, y: Float): Boolean {
        val latch = CountDownLatch(1)
        val completed = booleanArrayOf(false)
        val cb = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gesture: GestureDescription?) {
                completed[0] = true
                latch.countDown()
            }

            override fun onCancelled(gesture: GestureDescription?) {
                latch.countDown()
            }
        }
        return try {
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            if (!svc.dispatchGesture(gesture, cb, null)) return false
            latch.await(1500, TimeUnit.MILLISECONDS)
            completed[0]
        } catch (t: Throwable) {
            Logger.log(svc, "手势点击异常: ${t.message}")
            false
        }
    }

    /** 从下往上滑动（用于滑动锁屏解锁 / 列表滚动兜底） */
    fun swipeUp(svc: AccessibilityService, w: Int, h: Int): Boolean {
        return try {
            val path = Path().apply {
                moveTo(w / 2f, h * 0.85f)
                lineTo(w / 2f, h * 0.15f)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 250L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            svc.dispatchGesture(gesture, null, null)
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 把页面滚到底部。
     *
     * @param targetId 容器资源 id（可写短名 service_chat_list）；填 "auto" 则自动挑选
     * @return 是否执行了滚动动作
     *
     * 注意：不能无脑取第一个 isScrollable 的节点——ViewPager 也满足该条件，
     * 对它执行 ACTION_SCROLL_FORWARD 会变成【横向翻页】。故优先按 id 精确定位，
     * 退而求其次只认 ListView / RecyclerView / ScrollView 这类纵向容器。
     */
    fun scrollToBottom(svc: AccessibilityService, targetId: String = "auto", maxTimes: Int = 12): Boolean {
        val root = svc.rootInActiveWindow ?: return false

        val target = resolveScrollTarget(root, targetId)
        if (target != null) {
            var moved = false
            for (i in 0 until maxTimes) {
                if (!target.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) break
                moved = true
                Thread.sleep(350)
            }
            if (moved) return true
        }

        // 手势兜底：直接向上滑
        val dm = svc.resources.displayMetrics
        for (i in 0 until 3) {
            swipeUp(svc, dm.widthPixels, dm.heightPixels)
            Thread.sleep(450)
        }
        return true
    }

    private fun resolveScrollTarget(root: AccessibilityNodeInfo, idHint: String): AccessibilityNodeInfo? {
        if (idHint.isNotEmpty() && !idHint.equals("auto", ignoreCase = true)) {
            findById(root, idHint)?.let { return it }
        }
        return findVerticalScrollable(root)
    }

    /** 按资源 id 短名/全名查找 */
    private fun findById(node: AccessibilityNodeInfo, shortId: String): AccessibilityNodeInfo? {
        val rn = node.viewIdResourceName
        if (rn != null && (rn == shortId || rn.endsWith("/$shortId"))) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findById(child, shortId)?.let { return it }
        }
        return null
    }

    /** 只认纵向列表容器，避开 ViewPager */
    private fun findVerticalScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val names = listOf("ListView", "RecyclerView", "ScrollView")
        val cn = node.className?.toString() ?: ""
        if (node.isScrollable && names.any { cn.contains(it) }) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findVerticalScrollable(child)?.let { return it }
        }
        return null
    }
}
