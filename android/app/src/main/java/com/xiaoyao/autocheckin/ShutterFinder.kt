package com.xiaoyao.autocheckin

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.view.Display
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
 * 算法（刻意做得简单，因为信噪比本来就高）：
 *   - 只看屏幕下方 72% 以下（快门外不可能跑到上半屏）；
 *   - 逐行扫描，找【连续白段】。快门白盘宽约 163px，
 *     而「v」收起箭头只有 ~30px、文字笔画更窄，靠段长阈值就能一次滤干净；
 *   - 取全场最长的那一段，它的中心就是快门中心。
 *
 * 真机验证（1080x2400 截图）：
 *   下半屏白像素 25849 个 → 最长段 163px（正是白盘直径）→ 中心 (539.5, 2189.5)。
 */
object ShutterFinder {

    /** 判定为「白」的阈值。快门白盘接近纯白（#FFFFFF），取 225 留有抗锯齿余量。 */
    private const val WHITE_TH = 225

    /** 连续白段的最短长度。低于它的（箭头、文字、细边）一律不算。 */
    private const val MIN_RUN = 60

    /** 只扫屏幕下方这个比例以下，避开上半屏预览画面。 */
    private const val Y_START_RATIO = 0.72f

    /** 行采样步长。每行 1080 像素已足够，纵向每 4 行取一行，够快也够准。 */
    private const val SAMPLE_STEP = 4

    /** 截图超时（毫秒）。takeScreenshot 是异步回调，卡住不能拖死整条流程。 */
    private const val CAPTURE_TIMEOUT_MS = 4000L

    /**
     * 定位快门，返回【屏幕像素坐标】。找不到（或系统不支持）返回 null。
     */
    fun locate(svc: AccessibilityService): PointF? {
        // takeScreenshot 需要 Android 11 (API 30)。低版本直接放弃，由调用方回退坐标。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val bmp = capture(svc) ?: return null
        return try {
            scan(bmp)
        } finally {
            bmp.recycle()
        }
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

    private fun scan(bmp: Bitmap): PointF? {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return null

        val yStart = (h * Y_START_RATIO).toInt()
        val row = IntArray(w)

        var bestRun = 0
        var bestX = 0
        var bestY = -1

        var y = yStart
        while (y < h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)

            var x = 0
            while (x < w) {
                if (isWhite(row[x])) {
                    var x2 = x
                    while (x2 < w && isWhite(row[x2])) x2++
                    val run = x2 - x
                    if (run > bestRun) {
                        bestRun = run
                        bestX = (x + x2 - 1) / 2   // 取段中点，而不是端点
                        bestY = y
                    }
                    x = x2
                } else {
                    x++
                }
            }
            y += SAMPLE_STEP
        }

        // 段长不达标 = 没找到快门（而不是「碰巧找到个小白块」）
        if (bestRun < MIN_RUN || bestY < 0) return null
        return PointF(bestX.toFloat(), bestY.toFloat())
    }

    private fun isWhite(c: Int): Boolean {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return r >= WHITE_TH && g >= WHITE_TH && b >= WHITE_TH
    }
}
