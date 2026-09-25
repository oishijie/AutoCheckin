package com.xiaoyao.autocheckin

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 极简日志器：同时输出到 logcat、内存环形缓冲（供界面展示）和文件（供事后排查）。
 */
object Logger {
    private const val TAG = "AutoCheckin"
    private const val MAX_MEM = 400

    private val mem = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun log(ctx: Context?, msg: String) {
        val line = "[${fmt.format(Date())}] $msg"
        Log.i(TAG, line)

        synchronized(mem) {
            mem.addLast(line)
            while (mem.size > MAX_MEM) mem.removeFirst()
        }

        if (ctx != null) {
            try {
                File(ctx.filesDir, "run.log").appendText(line + "\n")
            } catch (_: Throwable) {
                // 日志写盘失败不影响主流程
            }
        }
    }

    fun recent(): List<String> = synchronized(mem) { mem.toList() }

    fun clearMem() = synchronized(mem) { mem.clear() }
}
