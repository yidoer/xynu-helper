package com.xynu.campus.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 诊断工具（不是断言测试）：把 GraphQL 端点的原始 HTTP 往返打出来。
 *
 * 需要它的原因：真机上报了 `Syntax Error: Unexpected <EOF>` —— 服务端
 * 拿到的 query 是空的。可是我们发出去的 JSON body 明明是完整的（单测里
 * 逐字 dump 过）。
 *
 * 已观察到的一条关键对照：**空 body 的请求和带完整 JSON 的请求返回了
 * 完全相同的 62 字节错误**。这基本排除了「body 内容拼错」，指向
 * 「POST body 没被服务端读到」。所以要区分两种可能：
 *
 *   (a) 上游只认 form 编码 / URL 参数 → 我们发错了格式，要改客户端
 *   (b) 链路上的代理把 POST body 吃掉了 → 环境问题，客户端改什么都没用
 *
 * 判据：如果连 form 编码也返回同一个 `<EOF>`，那就是 (b)。
 *
 * 用 `HttpURLConnection` 而不是 OkHttp，是为了不带任何拦截器，
 * 排除我们自己的 header 逻辑干扰。
 *
 * 用法：
 * ```
 * adb shell am instrument -w \
 *   -e class com.xynu.campus.library.SeatEndpointDiagnostic \
 *   com.xynu.campus.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 * 输出走 System.out，用 `adb logcat -d` 抓。
 */
@RunWith(AndroidJUnit4::class)
class SeatEndpointDiagnostic {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val base = "https://wechat.v2.traceint.com/index.php/graphql/"

    /** 无 body 的哨兵值：用空串表示 GET，避免可空类型。 */
    private val NO_BODY = ""

    /**
     * 发一次请求并把请求/响应原文打出来。
     *
     * @param body 空串表示这是 GET（不发 body）
     * @param contentType 空串表示不设 Content-Type
     */
    private fun probe(
        label: String,
        urlStr: String,
        body: String,
        contentType: String,
    ) {
        val isGet = body == NO_BODY
        println("========== $label ==========")
        println("URL: $urlStr")
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false     // 关键：别偷偷跟随
                connectTimeout = 15000
                readTimeout = 15000
                requestMethod = if (isGet) "GET" else "POST"
                setRequestProperty("App-Version", "2.2.9")
                setRequestProperty("User-Agent", "okhttp/4.12.0")
                setRequestProperty("Accept", "*/*")
                if (!isGet) {
                    doOutput = true
                    if (contentType.isNotEmpty()) setRequestProperty("Content-Type", contentType)
                }
            }
            if (!isGet) {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            println("--> HTTP ${conn.responseCode} ${conn.responseMessage}")
            conn.headerFields.forEach { (k, v) ->
                if (k != null && v.isNotEmpty()) println("    $k: ${v.joinToString(",")}")
            }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            println("--- body (${text.length} chars) ---")
            println(text.take(400))
        } catch (e: Exception) {
            println("!!! ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            conn?.disconnect()
        }
        println("========== end $label ==========")
    }

    @Test
    fun probeGraphQlEndpoint() {
        val q = "query libSimple { userAuth { reserve { libs { lib_id } } } }"
        val json = """{"operationName":"libSimple","query":"$q","variables":{}}"""

        // ① 基线：JSON 体
        probe("A POST json", base, json, "application/json;charset=UTF-8")

        // ② 对照：完全空体 —— 如果和 ① 返回一样，说明 body 根本没被读
        //    这里用单个空格，避免被当成 GET
        probe("B POST blank", base, " ", "application/json;charset=UTF-8")

        // ③ 假设 a：上游其实读 form 编码？
        val form = "operationName=libSimple" +
            "&query=" + URLEncoder.encode(q, "UTF-8") +
            "&variables=" + URLEncoder.encode("{}", "UTF-8")
        probe("C POST form", base, form, "application/x-www-form-urlencoded;charset=UTF-8")

        // ④ 假设 a 变体：query 走 URL 参数
        probe(
            "D GET query param",
            "$base?operationName=libSimple&query=" + URLEncoder.encode(q, "UTF-8"),
            NO_BODY,
            "",
        )

        // ⑤ 不带尾斜杠：会不会 301 把 POST 降级成 GET
        probe(
            "E POST no slash",
            "https://wechat.v2.traceint.com/index.php/graphql",
            json,
            "application/json;charset=UTF-8",
        )
    }
}
