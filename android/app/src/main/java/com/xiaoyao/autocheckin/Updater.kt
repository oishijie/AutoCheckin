package com.xiaoyao.autocheckin

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 版本检查：取 GitHub Releases 上的最新 tag，与本机 versionName 比大小。
 *
 * 不引 OkHttp / Gson —— [HttpURLConnection] 与 [org.json] 都是 framework 自带，
 * 守住「零第三方依赖、APK 不过百 KB」这条底线。
 *
 * 私有仓库对匿名请求一律返回 404（GitHub 故意不区分「不存在」和「没权限」），
 * 所以这种情况单独给一句能看懂的话，而不是笼统的「网络错误」。
 */
object Updater {

    const val OWNER = "oishijie"
    const val REPO = "AutoCheckin"

    const val URL_REPO = "https://github.com/$OWNER/$REPO"
    const val URL_LATEST = "$URL_REPO/releases/latest"
    const val URL_ISSUES = "$URL_REPO/issues"

    private const val API_LATEST = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    private const val TIMEOUT_MS = 8000

    /** 检查结果。失败也带上人能看懂的原因。 */
    sealed class Result {
        /** 远端更新，[version] 形如 v2.10 */
        data class Newer(val version: String, val pageUrl: String) : Result()

        /** 已是最新 */
        data class Latest(val version: String) : Result()

        /** 查不到：仓库未公开 / 还没发过 Release / 网络不通 */
        data class Failed(val reason: String) : Result()
    }

    private val ui = Handler(Looper.getMainLooper())

    /**
     * 后台线程发请求，回调保证在主线程 —— 调用方可以直接碰 View。
     *
     * @param current 本机 versionName，如 "2.9"
     */
    fun check(current: String, onResult: (Result) -> Unit) {
        Thread {
            val result = try {
                request(current)
            } catch (t: Throwable) {
                Result.Failed(t.message ?: t.javaClass.simpleName)
            }
            ui.post { onResult(result) }
        }.apply { isDaemon = true }.start()
    }

    private fun request(current: String): Result {
        val conn = (URL(API_LATEST).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            // GitHub API 强制要求 UA，缺了直接 403
            setRequestProperty("User-Agent", "$REPO/$current")
        }

        try {
            when (val code = conn.responseCode) {
                200 -> Unit
                404 -> return Result.Failed("仓库为私有或尚未发布 Release，无法匿名查询")
                else -> return Result.Failed("服务返回 HTTP $code")
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = json.optString("tag_name").takeIf { it.isNotBlank() }
                ?: return Result.Failed("发布里没有版本号")
            val pageUrl = json.optString("html_url").takeIf { it.isNotBlank() } ?: URL_LATEST

            return if (isNewer(tag, current)) Result.Newer(tag, pageUrl) else Result.Latest(tag)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 逐位比数字，而不是直接比字符串 ——
     * 字符串比较下 "2.10" < "2.9"（因为 '1' < '9'），版本号一进两位就判错。
     */
    private fun isNewer(remote: String, local: String): Boolean {
        val a = numbers(remote)
        val b = numbers(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun numbers(s: String): List<Int> =
        Regex("\\d+").findAll(s).map { it.value.toInt() }.toList()
}
