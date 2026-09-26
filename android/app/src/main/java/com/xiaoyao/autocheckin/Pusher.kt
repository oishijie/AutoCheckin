package com.xiaoyao.autocheckin

import android.content.Context
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 把签到结果推到微信（走用户自建的 Cloudflare Worker）。
 *
 * 为什么需要它：签到是**无人值守**的。定时在后台跑，成功没人知道、
 * 失败更没人知道 —— 唯一能发现的方式是自己想起来去翻日志，而那往往
 * 已经是第二天、当天机会早错过了。有了推送，「签上了 / 没签上」手机
 * 上直接就跳出来，失败了还来得及手动补签。
 *
 * ── 协议 ──
 * POST {url}
 *   Authorization: {token}
 *   Content-Type: application/json
 *   {"title":"…","content":"…"[,"userid":"…"]}
 * 成功返回 2xx + {"msg":"Successfully sent messages to N user(s).…"}
 *
 * 用 POST 而不是 GET：token 是密钥，放 URL 里会进各种日志/历史记录；
 * 放请求头干净得多。服务端两种都支持，这边挑安全的那个。
 *
 * ── 三条设计红线 ──
 *
 *  1. **零依赖**：HttpURLConnection + 手写 JSON 转义。项目硬约束是不引
 *     OkHttp / Gson（APK 要压在 100KB 以内），不能为了发一条消息破例。
 *
 *  2. **绝不影响签到主流程**：推送失败（没网、token 错、服务挂了）只写日志，
 *     绝不让它冒泡成异常、更不能让它改变「签到成功/失败」的判定。
 *     发消息是锦上添花，签到才是本体。
 *
 *  3. **token 只存本机**：不写默认值、不进代码（仓库是公开的）。
 *     设置页填，存 SharedPreferences。
 *
 * ⚠️ 会阻塞（同步请求，最长约 13 秒），调用方必须已经在工作线程上。
 */
object Pusher {

    /** 推送结果，给日志用。[ok] 只表示「这条消息提交成功」，不含业务判断。 */
    data class Outcome(val ok: Boolean, val detail: String)

    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 8000

    /** 返回响应体最多截多少字符写进日志（响应可能很长）。 */
    private const val BODY_LIMIT = 200

    /**
     * 发一条推送。**不会抛异常**，任何问题都以 [Outcome.ok] = false 返回。
     */
    fun send(c: Context, title: String, content: String): Outcome {
        if (!ConfigStore.pushEnabled(c)) return Outcome(false, "未启用推送（设置页开启）")

        val token = ConfigStore.pushToken(c).trim()
        if (token.isEmpty()) return Outcome(false, "未配置推送 token")

        val url = ConfigStore.pushUrl(c).trim().ifEmpty { ConfigStore.DEFAULT_PUSH_URL }
        val userId = ConfigStore.pushUserId(c).trim()

        val fields = LinkedHashMap<String, String>()
        fields["title"] = title
        fields["content"] = content
        if (userId.isNotEmpty()) fields["userid"] = userId

        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", token)
            }
            conn.outputStream.use { it.write(json(fields).toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.let { stream ->
                    BufferedReader(stream.reader(Charsets.UTF_8)).use { it.readText() }
                } ?: ""
            conn.disconnect()

            Outcome(code in 200..299, "HTTP $code ${body.take(BODY_LIMIT)}")
        } catch (t: Throwable) {
            Outcome(false, "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ---------------- 下面都是为了让「零依赖」成立而手写的小工具 ----------------

    /** 手搓一个扁平 JSON 对象。字段全是字符串，没有嵌套，够用。 */
    private fun json(fields: Map<String, String>): String =
        fields.entries.joinToString(",", "{", "}") {
            "\"${escape(it.key)}\":\"${escape(it.value)}\""
        }

    /**
     * JSON 字符串转义。
     *
     * ⚠️ 别省这一步：签到内容里有中文、冒号、感叹号，甚至可能有换行/引号，
     *    不转义直接拼就是一段坏 JSON，服务端会 400 而这边全无线索。
     *    控制字符走 \uXXXX，其余按规范转义。
     */
    private fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') {
                    sb.append("\\u").append(String.format("%04x", ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        return sb.toString()
    }
}
