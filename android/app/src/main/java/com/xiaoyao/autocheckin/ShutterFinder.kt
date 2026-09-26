package com.xiaoyao.autocheckin

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.view.Display
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * 用「图像识别」在相机页里找那颗白色快门圆盘。
 *
 * 为什么非要用图像识别：
 *
 *   1. 快门 cb_capture 是个裸 View —— 无文字、clickable=false，
 *      在无障碍节点树里时有时无（实测同一台机器，手动 dump 有、服务读没有），
 *      按 id 找靠不住。
 *
 *   2. 「按屏幕比例算坐标」看似能跨分辨率，但真机实测（2026-09-25）会踩坐标系坑：
 *      displayMetrics.heightPixels 给的是【应用窗口】高度 2273，
 *      而节点 bounds 用的是【屏幕】坐标（0~2400），两者差 127px（状态栏）。
 *      更麻烦的是这个差值还会随页面变 —— 签到页内容能到 y=2374，
 *      编辑页只到 2273，同一台机器两套基准。
 *      比例算出来的点就会在快门边缘上下游走，导致「时灵时不灵」。
 *
 *   3. 图像识别直接读像素，拿到的就是屏幕物理坐标，不受上面任何影响。
 *
 * ────────────────────────────────────────────────────────────────────
 * 算法（v2.18 重写：从「一根白线」升级为「一个白斑」）
 * ────────────────────────────────────────────────────────────────────
 *
 * 老版本只看【逐行白段】，取全场最长的那一段当中点。它的致命弱点是
 * 「只要底部出现任何够长的白色块就上当」—— Toast 的白底、白色输入框、
 * 白底按钮，随便哪个都比快门宽，一出现就被认成快门（实测踩过）。
 *
 * 新版本改为【连通域（blob）分析】，把「一段线」升级成「一个面」，
 * 用四重判据一起卡（这些数字都是按真机快门的量纲定的，见下）：
 *
 *   ① 段长      ≥ 60px      —— 先筛种子，滤掉箭头、文字笔画
 *   ② 面积      ≥ 3500px²   —— 真机白盘 ≈ 20800px²（163px 直径的实心圆）
 *   ③ 填充率    ≥ 0.45      —— 面积 / 外接矩形；正圆是 π/4 ≈ 0.785，
 *                              白底长条则远低于此（0.1~0.3），一刀切干净
 *   ④ 长宽比    0.62 ~ 1.60 —— 圆形必须接近方形；横条、竖条全部出局
 *   ⑤ 直径占比  0.055~0.34 屏宽 —— 真机 163/1080 ≈ 0.15，卡一个宽松区间
 *
 * 光有形状还不够 —— 单帧再严也可能撞上「恰好长得像圆的白块」（比如某个
 * 白色圆形图标）。所以再加一层【连拍两帧取交集】：
 *
 *   间隔 320ms 连拍两帧各自识别。两帧都命中且位置偏差 ≤ 12% 屏宽 → 采信
 *   两者均值（更准）；只命中一帧 → 采信它但标注置信度低；两帧都有但偏差过大
 *   → 取分高者并如实标注 —— 全部写进返回值的 detail，日志里能一眼看出
 *   这次识别是「双帧一致」还是「勉强凑合」。
 *
 * 真机验证（1080x2400 截图）：下半屏白像素 25849 个 → 最长段 163px
 * （正是白盘直径）→ 中心 (539.5, 2189.5)。
 *
 * ⚠️ 识别不出来时【不要】再退回写死的 50%/91%：那个比例实测偏 112px，
 *    基本必失。回退策略由调用方按「历史成功坐标 → 默认比例」的顺序处理。
 */
object ShutterFinder {

    /**
     * 一次识别结果。[x] / [y] 是**屏幕物理像素**坐标（与截图为同一坐标系）。
     * [detail] 是给日志看的人话说明（命中了几帧、形状指标、偏差多少）。
     */
    data class Hit(val x: Float, val y: Float, val detail: String)

    /** 判定为「白」的阈值。快门白盘接近纯白（#FFFFFF），取 225 留有抗锯齿余量。 */
    private const val WHITE_TH = 225

    /** 种子白段的最短长度（屏幕像素）。低于它的（箭头、文字、细边）一律不种。 */
    private const val MIN_RUN_PX = 60

    /** 网格降采样步长（像素）。4 表示一个格代表 4x4 像素，兼顾速度与精度。 */
    private const val SAMPLE_STEP = 4

    /** 只扫屏幕下方这个比例以下，避开上半屏预览画面。 */
    private const val Y_START_RATIO = 0.70f

    // ---- blob 判据（量纲见文件头说明） ----
    private const val MIN_BLOB_AREA = 3500
    private const val MIN_FILL = 0.45f
    private const val MIN_ASPECT = 0.62f
    private const val MAX_ASPECT = 1.60f
    private const val MIN_DIAM_RATIO = 0.055f
    private const val MAX_DIAM_RATIO = 0.34f

    /**
     * 单个连通域的格子数上限。超过就判定为「一大片白」（白底弹窗、白色面板），
     * 直接作废 —— 否则一次 flood fill 可能扫遍全屏，白耗时间。
     * 7000 格 ≈ 350px 直径的实心圆，比快门（163px ≈ 1300 格）宽裕得多。
     */
    private const val MAX_BLOB_CELLS = 7000

    /** 截图超时（毫秒）。takeScreenshot 是异步回调，卡住不能拖死整条流程。 */
    private const val CAPTURE_TIMEOUT_MS = 4000L

    /** 两帧之间的间隔，等画面稳定（快门就是在这段时间里可能被点的）。 */
    private const val FRAME_GAP_MS = 320L

    /** 两帧被认为是「同一位置」的最大偏差（占屏宽比例）。 */
    private const val FRAME_RADIUS_RATIO = 0.12f

    /** 连拍帧数。2 帧即可显著降低偶发误判，再多是浪费时间。 */
    private const val FRAMES = 2

    /**
     * 定位快门，返回【屏幕像素坐标】。找不到（或系统不支持）返回 null。
     *
     * ⚠️ 会阻塞约 1~2 秒（两帧截图 + 扫描），调用方应保证自己在工作线程。
     */
    fun locate(svc: AccessibilityService): Hit? {
        // takeScreenshot 需要 Android 11 (API 30)。低版本直接放弃，由调用方回退坐标。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        val notes = ArrayList<String>(FRAMES + 1)
        val hits = ArrayList<Blob>(FRAMES)
        var screenW = 0

        for (i in 1..FRAMES) {
            if (i > 1) {
                try {
                    Thread.sleep(FRAME_GAP_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            val bmp = capture(svc)
            if (bmp == null) {
                notes += "第${i}帧截图失败"
                continue
            }
            val blob = try {
                findBlob(bmp)
            } catch (t: Throwable) {
                null
            } finally {
                bmp.recycle()
            }
            if (blob == null) {
                notes += "第${i}帧无合格白斑"
            } else {
                screenW = if (screenW == 0) bmp.width else screenW
                notes += "第${i}帧(${blob.cx.toInt()},${blob.cy.toInt()}) " +
                    "直径${blob.diam.toInt()}px 填充${one(blob.fill)} 长宽比${one(blob.aspect)}"
                hits += blob
            }
        }

        val tail = notes.joinToString("；")
        if (hits.isEmpty()) return null

        // ── 两帧取交集 ──
        if (hits.size >= 2) {
            val a = hits[0]
            val b = hits[1]
            val dx = a.cx - b.cx
            val dy = a.cy - b.cy
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            val limit = (if (screenW > 0) screenW else a.cx.toInt().coerceAtLeast(1)) * FRAME_RADIUS_RATIO
            return if (dist <= limit) {
                // 两帧都认到同一处 → 取均值，比任何单帧都准
                Hit(
                    (a.cx + b.cx) / 2f,
                    (a.cy + b.cy) / 2f,
                    "双帧一致（偏差${dist.toInt()}px，取均值）；$tail"
                )
            } else {
                // 位置打架 —— 说明画面里有两个「像快门的东西」。
                // 不敢瞎猜，取分高的那个，但如实标注，方便回头查。
                val pick = if (a.score >= b.score) a else b
                Hit(
                    pick.cx, pick.cy,
                    "⚠️ 双帧不一致（偏差${dist.toInt()}px，超过${limit.toInt()}px）" +
                        "，采用分高者；$tail"
                )
            }
        }

        val only = hits[0]
        return Hit(only.cx, only.cy, "仅 1 帧命中（置信度较低）；$tail")
    }

    /**
     * 只测「截图找快门」这一个动作，不跑前面任何步骤。
     *
     * 存在的理由：整条流程动辄一两分钟，还常卡在「签到页加载」这种
     * 与快门无关的环节，想单独验一下图像识别得靠运气。
     * 手动把界面开到相机页，然后：
     *   adb shell am start -n com.xiaoyao.autocheckin/.MainActivity --ez testShutter true
     * 就能直接看识别结果、耗时和归一化位置。
     *
     * 找不到时**不会**自动点击任何东西 —— 早先版本会在识别失败时回退比例坐标
     * 并按下去，那是在盲点，容易误触发，现在已经去掉。
     */
    fun testShutter(svc: AccessibilityService) {
        Thread {
            Logger.log(svc, "===== 快门识别测试开始 =====")
            val t0 = System.currentTimeMillis()
            val hit = locate(svc)
            val cost = System.currentTimeMillis() - t0
            val dm = svc.resources.displayMetrics
            Logger.log(svc, "识别耗时 ${cost}ms；displayMetrics=${dm.widthPixels}x${dm.heightPixels}")
            if (hit == null) {
                Logger.log(svc, "✗ 没识别到白色快门（截图被拒？或当前不在相机页？）")
                val hist = ConfigStore.shutterPoint(svc)
                if (hist == null) {
                    Logger.log(svc, "   · 也没有历史成功坐标可用（首次运行）")
                } else {
                    val (sw, sh) = realScreenSize(svc)
                    Logger.log(
                        svc,
                        "   · 历史成功坐标可用：屏幕 ${(hist.xRatio * sw).toInt()}," +
                            "${(hist.yRatio * sh).toInt()}（比例 ${"%.3f".format(hist.xRatio)}," +
                            "${"%.3f".format(hist.yRatio)}）"
                    )
                }
            } else {
                Logger.log(svc, "✓ ${hit.detail}")
                Logger.log(
                    svc,
                    "✓ 识别到快门 (${hit.x.toInt()}, ${hit.y.toInt()})，归一化 " +
                        "(${"%.1f".format(hit.x / dm.widthPixels * 100)}%, " +
                        "${"%.1f".format(hit.y / dm.heightPixels * 100)}%)"
                )
            }
            Logger.log(svc, "===== 快门识别测试结束 =====")
        }.start()
    }

    /**
     * 屏幕的**物理**尺寸（含状态栏与导航条）。
     *
     * 为什么不能用 resources.displayMetrics：它给的是【应用窗口】高度
     * （真机实测 2273），而截图是物理屏（2400）—— 拿窗口高去乘坐标比例，
     * 结果会稳定偏上 5%，正好把点甩到快门边缘。历史坐标回退必须用这个。
     */
    fun realScreenSize(svc: AccessibilityService): Pair<Int, Int> {
        val dm = svc.resources.displayMetrics
        try {
            val wm = svc.getSystemService(android.content.Context.WINDOW_SERVICE)
                as? android.view.WindowManager
            // defaultDisplay / getRealSize 自 API 30 起标记废弃，但替代 API
            // （WindowMetrics）要 API 30+，而本 App 要兼容到 8.0。
            // 这里只用它拿「物理屏幕尺寸」这一个最基础的读数，废弃 API 照常工作。
            @Suppress("DEPRECATION")
            val display = wm?.defaultDisplay
            if (display != null) {
                val p = android.graphics.Point()
                @Suppress("DEPRECATION")
                display.getRealSize(p)
                if (p.x > 0 && p.y > 0) return Pair(p.x, p.y)
            }
        } catch (_: Throwable) {
            // 落到下面的窗口尺寸兜底
        }
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    @Suppress("NewApi")
    private fun capture(svc: AccessibilityService): Bitmap? {
        val latch = CountDownLatch(1)
        val out = AtomicReference<Bitmap?>(null)

        // 回调跑在主线程：takeScreenshot 要求必须在带 Looper 的线程调用，
        // 且我们是在工作线程里 await，两者互不阻塞。
        val executor = Executor { r -> Handler(svc.mainLooper).post(r) }

        val cb = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                try {
                    val hb = result.hardwareBuffer
                    // wrap 出来的是 HARDWARE 位图，getPixels 读不了，必须转一份 ARGB_8888。
                    out.set(Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false))
                    hb.close()
                } catch (t: Throwable) {
                    out.set(null)
                } finally {
                    latch.countDown()
                }
            }

            override fun onFailure(errorCode: Int) {
                latch.countDown()
            }
        }

        return try {
            svc.takeScreenshot(Display.DEFAULT_DISPLAY, executor, cb)
            if (!latch.await(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) null else out.get()
        } catch (t: Throwable) {
            null
        }
    }

    /** 一个白色连通域的形状指标。 */
    private class Blob(
        val cx: Float,
        val cy: Float,
        val diam: Float,
        val fill: Float,
        val aspect: Float,
        val area: Int,
        val score: Float
    )

    /** 连通域的外接矩形统计（单位：网格格）。 */
    private class Stat(var minX: Int, var maxX: Int, var minY: Int, var maxY: Int, var count: Int)

    /**
     * 在整张图里找最像快门的白色斑点。没有合格的返回 null。
     *
     * 步骤：降采样成布尔网格 → 逐行找够长的白段当种子 → 对每个种子做连通域
     * 扩展 → 量形状 → 打分，取最高分。
     */
    private fun findBlob(bmp: Bitmap): Blob? {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return null

        val gw = w / SAMPLE_STEP
        val gh = h / SAMPLE_STEP
        if (gw <= 4 || gh <= 4) return null

        val yStartCell = ((h * Y_START_RATIO) / SAMPLE_STEP).toInt().coerceIn(0, gh - 1)

        // ① 降采样：一个格子取块中心那一个像素定白黑。
        val white = Array(gh) { BooleanArray(gw) }
        val row = IntArray(w)
        for (gy in yStartCell until gh) {
            val py = gy * SAMPLE_STEP + SAMPLE_STEP / 2
            if (py >= h) break
            bmp.getPixels(row, 0, w, 0, py, w, 1)
            val line = white[gy]
            for (gx in 0 until gw) {
                val px = gx * SAMPLE_STEP + SAMPLE_STEP / 2
                line[gx] = px < w && isWhite(row[px])
            }
        }

        // ② 每行取最长白段当种子（够长才种）
        val minRunCells = (MIN_RUN_PX + SAMPLE_STEP - 1) / SAMPLE_STEP
        val seedX = ArrayList<Int>()
        val seedY = ArrayList<Int>()
        for (gy in yStartCell until gh) {
            val line = white[gy]
            var gx = 0
            var bestStart = -1
            var bestLen = 0
            while (gx < gw) {
                if (line[gx]) {
                    val s = gx
                    while (gx < gw && line[gx]) gx++
                    val len = gx - s
                    if (len > bestLen) {
                        bestLen = len
                        bestStart = s
                    }
                } else {
                    gx++
                }
            }
            if (bestLen >= minRunCells && bestStart >= 0) {
                seedX += bestStart + bestLen / 2
                seedY += gy
            }
        }
        if (seedX.isEmpty()) return null

        // ③ 逐个种子做连通域，量形状打分
        val seen = Array(gh) { BooleanArray(gw) }
        var best: Blob? = null
        for (i in seedX.indices) {
            val sx = seedX[i]
            val sy = seedY[i]
            if (sy < 0 || sy >= gh || sx < 0 || sx >= gw) continue
            if (seen[sy][sx] || !white[sy][sx]) continue
            val st = flood(white, seen, sx, sy, gw, gh) ?: continue
            val blob = metricsOf(st, w) ?: continue
            val cur = best
            if (cur == null || blob.score > cur.score) best = blob
        }
        return best
    }

    /** 从 (sx, sy) 出发做 4 邻域连通域扩展，返回外接矩形与格数。 */
    private fun flood(
        white: Array<BooleanArray>,
        seen: Array<BooleanArray>,
        sx: Int,
        sy: Int,
        gw: Int,
        gh: Int
    ): Stat? {
        val q = ArrayDeque<Int>()
        q.add(sy * gw + sx)
        seen[sy][sx] = true
        val st = Stat(sx, sx, sy, sy, 0)
        var overflow = false

        while (q.isNotEmpty()) {
            // poll() 的返回类型是可空的（Java 那边签名如此）；while 已经保证非空，
            // 这里显式接住，免得留一条「Unsafe use of a nullable receiver」警告。
            val cur = q.poll() ?: break
            val cy = cur / gw
            val cx = cur % gw
            st.count++
            if (cx < st.minX) st.minX = cx
            if (cx > st.maxX) st.maxX = cx
            if (cy < st.minY) st.minY = cy
            if (cy > st.maxY) st.maxY = cy
            if (st.count > MAX_BLOB_CELLS) {
                overflow = true
                break
            }
            // 4 邻域：比 8 邻域更保守，不会把斜挨着的两块白（图标+边框）粘成一片
            val nb = intArrayOf(cx - 1, cy, cx + 1, cy, cx, cy - 1, cx, cy + 1)
            var k = 0
            while (k < nb.size) {
                val nx = nb[k]
                val ny = nb[k + 1]
                k += 2
                if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue
                if (seen[ny][nx] || !white[ny][nx]) continue
                seen[ny][nx] = true
                q.add(ny * gw + nx)
            }
        }
        return if (overflow) null else st
    }

    /**
     * 把外接矩形换算成物理量并按四重判据卡，不通过返回 null。
     * 通过时算一个分数（越像正圆、越大越高），供多候选排序。
     */
    private fun metricsOf(st: Stat, screenW: Int): Blob? {
        val wpx = (st.maxX - st.minX + 1) * SAMPLE_STEP
        val hpx = (st.maxY - st.minY + 1) * SAMPLE_STEP
        if (wpx <= 0 || hpx <= 0) return null

        val area = st.count * SAMPLE_STEP * SAMPLE_STEP
        if (area < MIN_BLOB_AREA) return null

        val fill = area.toFloat() / (wpx.toFloat() * hpx.toFloat())
        if (fill < MIN_FILL) return null

        val aspect = wpx.toFloat() / hpx.toFloat()
        if (aspect < MIN_ASPECT || aspect > MAX_ASPECT) return null

        val diam = (wpx + hpx) / 2f
        val diamRatio = diam / screenW
        if (diamRatio < MIN_DIAM_RATIO || diamRatio > MAX_DIAM_RATIO) return null

        // 外接矩形中心 = 白斑中心
        val cx = (st.minX + st.maxX + 1) / 2f * SAMPLE_STEP
        val cy = (st.minY + st.maxY + 1) / 2f * SAMPLE_STEP

        // 打分：形状越接近正圆（fill→0.785, aspect→1）越大，面积也占一点权重。
        // 用途仅在多候选之间排序（比如两帧不一致时挑一个），不做阈值。
        val score = fill * 1.0f +
            (1f - abs(aspect - 1f)) * 0.8f +
            (area.coerceAtMost(22000) / 22000f) * 0.4f

        return Blob(cx, cy, diam, fill, aspect, area, score)
    }

    private fun isWhite(c: Int): Boolean {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return r >= WHITE_TH && g >= WHITE_TH && b >= WHITE_TH
    }

    private fun one(v: Float): String = "%.2f".format(v)
}
