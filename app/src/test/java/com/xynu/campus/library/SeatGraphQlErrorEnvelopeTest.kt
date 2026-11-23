package com.xynu.campus.library

import com.xynu.campus.library.graphql.SeatGraphQlClient
import com.xynu.campus.library.graphql.SeatResponseParser
import com.xynu.campus.library.model.SeatSession
import com.xynu.campus.library.net.LibraryHostAllowList
import com.xynu.campus.library.net.LibraryHttpClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 回归：**「Cookie 给了、也提示配置成功，但永远读不到数据」**。
 *
 * 真正的根因（经上游 bundle 审计确认）不是 Cookie 没发出去，而是
 * **上游的 GraphQL 错误信封被我们当成了成功**：
 *
 * ```
 * 验活 → 上游返回 {"errors":[{"code":40002,"msg":"..."}],"data":null}
 *      → 我们当 Ok → 提示「配置成功」
 *      → 查列表 → data.userAuth 是 null → 解析出空列表
 *      → 页面只显示「没有取到图书馆列表」
 * ```
 *
 * 上游前端 `conv()` 的规则是「只要 errors 非空就一律失败并显示 errors[0].msg」。
 * 这里锁住同样的语义：除 40001（会话失效，要单独走重试/失效）外，
 * 任何错误都必须变成带上游原文的 Failed。
 */
class SeatGraphQlErrorEnvelopeTest {

    private lateinit var server: MockWebServer
    private val session = SeatSession("SESS", "AUTH")

    @Before
    fun setUp() {
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /**
     * 改写 URL 到 MockWebServer，同时让白名单认这台 mock。
     *
     * 两件事都必须做：
     * 1. **改写**：`SeatGraphQlClient` 硬编码 `https://wechat.v2.traceint.com`，
     *    不改写就真的去打外网（单测无网 → 超时）。
     * 2. **白名单注入**：改写发生在 chain 内部，network 白名单看的是改写**之后**的
     *    URL，mock 的 `127.0.0.1:随机端口` 会被拒 ⇒ 请求发不出去 ⇒
     *    `takeRequest()` 永久阻塞（这个组合曾经把整个测试进程挂死 26 分钟）。
     *
     * 只做 2 不做 1 会让 15 个测试全红在"5 秒内没收到请求"。
     */
    private fun rewrite(): OkHttpClient {
        val allowed: (okhttp3.HttpUrl) -> Boolean = { url ->
            (url.host == server.hostName && url.port == server.port) ||
                LibraryHostAllowList.isAllowed(url)
        }
        return LibraryHttpClient.createForSeatGraphQlForTest(allowed)
            .newBuilder()
            .addInterceptor { chain ->
                val req = chain.request()
                val u = req.url.newBuilder()
                    .scheme("http").host(server.hostName).port(server.port).build()
                chain.proceed(req.newBuilder().url(u).build())
            }
            .build()
    }

    private fun client() = SeatGraphQlClient(rewrite())

    /** 带超时的 `takeRequest`：挂死比失败更难查，宁可 5 秒报错。 */
    private fun takeRequestOrFail(): RecordedRequest =
        server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
            ?: throw AssertionError("MockWebServer 在 5 秒内没收到请求（多半是被白名单拒了）")

    private fun serve(json: String) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(json)
        }
        server.start()
    }

    /**
     * 非 40001 的业务错误必须暴露成 Failed，且带上游原文。
     *
     * 这是本文件最重要的一条：它保证用户能看到「为什么不行」，
     * 而不是看到一个永远空的列表。
     */
    @Test
    fun `non 40001 graphql error surfaces upstream message`() {
        serve("""{"errors":[{"code":40002,"msg":"账号未绑定图书馆"}],"data":null}""")

        val r = runBlocking { client().listLibraries(session) }
        assertTrue("必须判失败，不能当成功返回空列表。实际：$r", r is SeatGraphQlClient.Res.Failed)
        val msg = (r as SeatGraphQlClient.Res.Failed).message
        assertTrue("要点出上游原文，实际：$msg", msg.contains("账号未绑定图书馆"))
        assertTrue("要带上错误码方便排查，实际：$msg", msg.contains("40002"))
    }

    /**
     * 40001 仍然必须走 SessionInvalid —— 不能被上面的新逻辑截走。
     *
     * 这条和上一条是矛盾对：`errors` 非空的判断很容易把 40001 一起吞掉，
     * 那样「会话过期」就不再触发重新配置的引导，用户只会看到一条错误文案。
     */
    @Test
    fun `40001 still maps to session invalid not plain failure`() {
        serve("""{"errors":[{"msg":"Access Denied!","code":40001}],"data":{"userAuth":null}}""")

        val r = runBlocking { client().listLibraries(session) }
        assertTrue("40001 必须识别为会话失效，实际：$r", r is SeatGraphQlClient.Res.SessionInvalid)
    }

    /** 正常的成功响应不能被新逻辑误杀。 */
    @Test
    fun `error free response is not treated as failure`() {
        serve(
            """{"data":{"userAuth":{"reserve":{"libs":[
                {"lib_id":3,"lib_name":"馆A","is_open":1,
                 "lib_rt":{"seats_total":80,"seats_used":10,"seats_booking":2}}
            ]}}}}""",
        )

        val r = runBlocking { client().listLibraries(session) }
        assertTrue("正常响应必须成功，实际：$r", r is SeatGraphQlClient.Res.Ok)
        val libs = (r as SeatGraphQlClient.Res.Ok).value
        assertEquals(1, libs.size)
        // is_open 传的是数值 1，必须被识别为开放
        assertTrue("is_open=1 必须是开放状态", libs[0].isOpen)
    }

    /** `is_open: true` 的布尔形态也要认（两种都兼容）。 */
    @Test
    fun `is_open boolean form is also accepted`() {
        serve(
            """{"data":{"userAuth":{"reserve":{"libs":[
                {"lib_id":4,"lib_name":"馆B","is_open":true,"lib_rt":{"seats_total":10}}
            ]}}}}""",
        )

        val r = runBlocking { client().listLibraries(session) }
        val libs = (r as SeatGraphQlClient.Res.Ok).value
        assertTrue("is_open=true 必须是开放状态", libs[0].isOpen)
    }

    /** `is_open: 0` 是关闭，别被当成开放。 */
    @Test
    fun `is_open zero means closed`() {
        serve(
            """{"data":{"userAuth":{"reserve":{"libs":[
                {"lib_id":5,"lib_name":"馆C","is_open":0,"lib_rt":{"seats_total":10}}
            ]}}}}""",
        )

        val r = runBlocking { client().listLibraries(session) }
        val libs = (r as SeatGraphQlClient.Res.Ok).value
        assertFalse("is_open=0 必须是关闭", libs[0].isOpen)
    }

    // ---------- 请求参数对齐上游 ----------

    /**
     * `libLayout` 只能传 `libId`（`libType` 必须是 null）。
     *
     * 上游是 `libLayout({libId: t})`。我们多传 `libType: -1` 会被当成
     * 「全部类型」参与 `libs(libType:, libId:)` 过滤，可能直接过滤成空，
     * 表现为座位图一片空白。
     */
    @Test
    fun `libLayout sends libId only with null libType`() {
        serve("""{"data":{"userAuth":{"reserve":{"libs":[]}}}}""")

        runBlocking { client().libLayout(session, 42) }

        val vars = formFields(takeRequestOrFail().body.readUtf8())["variables"].orEmpty()
        val o = org.json.JSONObject(vars)
        assertEquals("libId 必须传", 42, o.getInt("libId"))
        assertTrue(
            "libType 必须是 null（上游只传 libId），实际：$vars",
            !o.has("libType") || o.isNull("libType"),
        )
    }

    /**
     * 请求体必须是 **form-urlencoded**，不能是 JSON。
     *
     * 这是「Cookie 给了却读不到数据」的**真正**根因，不是 Cookie 本身。
     * 上游是 PHP，只从 `$_POST` 按字段名取 `query`，不解析 JSON body。
     * 真机四组对照（详见 SeatGraphQlClient.buildFormBody 的注释）：
     *
     * - POST + JSON body      → `Syntax Error: Unexpected <EOF>`
     * - POST + 空 body         → `Syntax Error: Unexpected <EOF>` ← 与上面完全相同
     * - POST + form-urlencoded → `Access Denied! 40001` ← query 被解析了
     * - GET  + query 参数       → `Access Denied! 40001`
     *
     * 前两行返回一模一样，证明服务端根本没读我们的 JSON：query 解析成空串，
     * 语法分析器撞到 EOF。所以列表永远为空、验活却"成功"。
     *
     * 这条测试锁住传输格式，避免有人"顺手"改回 application/json。
     */
    @Test
    fun `request body is form encoded not json`() {
        serve("""{"data":{"userAuth":{"reserve":{"libs":[]}}}}""")

        runBlocking { client().listLibraries(session) }

        val req = takeRequestOrFail()
        val contentType = req.getHeader("Content-Type").orEmpty()
        assertTrue(
            "必须是 form-urlencoded，实际 Content-Type：$contentType",
            contentType.contains("application/x-www-form-urlencoded"),
        )
        assertFalse(
            "不能是 JSON —— 上游 PHP 不解析 JSON body：$contentType",
            contentType.contains("application/json"),
        )

        val body = req.body.readUtf8()
        assertFalse(
            "body 不能是 JSON 对象，实际以 ${body.take(20)} 开头",
            body.trimStart().startsWith("{"),
        )
        val fields = formFields(body)
        assertTrue("必须带 operationName 字段", fields.containsKey("operationName"))
        assertTrue("必须带 query 字段", fields.containsKey("query"))
        assertTrue("必须带 variables 字段", fields.containsKey("variables"))
        // query 解开后必须是一条真正的 GraphQL 语句
        assertTrue(
            "query 字段解开后必须是 GraphQL 语句，实际：${fields["query"]?.take(60)}",
            fields["query"].orEmpty().trimStart().startsWith("query "),
        )
    }

    /** 把 form-urlencoded body 解成字段表。 */
    private fun formFields(body: String): Map<String, String> =
        body.split("&")
            .filter { it.contains("=") }
            .associate { pair ->
                val k = pair.substringBefore("=")
                val v = java.net.URLDecoder.decode(pair.substringAfter("="), "UTF-8")
                java.net.URLDecoder.decode(k, "UTF-8") to v
            }

    /** App-Version 必须和上游一致（上游 bundle 里是 2.2.9）。 */
    @Test
    fun `app version header matches upstream`() {
        serve("""{"data":{"userAuth":{"reserve":{"libs":[]}}}}""")

        runBlocking { client().verifySession(session) }

        val req = takeRequestOrFail()
        assertEquals("2.2.9", req.getHeader("App-Version"))
        assertEquals(
            "App-Version 必须来自同一个常量，别在两处各写一遍",
            LibraryHttpClient.TRACEINT_APP_VERSION,
            req.getHeader("App-Version"),
        )
    }

    /**
     * 不能再发臆造的 `Origin` / `Referer`。
     *
     * `web.traceint.com` 在上游全部 fixture 里出现 0 次，是我们编的。
     * 送一个不存在的来源域，轻则被忽略，重则被判伪造。
     */
    @Test
    fun `no fabricated origin or referer headers`() {
        serve("""{"data":{"userAuth":{"reserve":{"libs":[]}}}}""")

        runBlocking { client().verifySession(session) }

        val req = takeRequestOrFail()
        val origin = req.getHeader("Origin")
        val referer = req.getHeader("Referer")
        assertTrue(
            "不该发臆造的 Origin（实际：$origin）",
            origin == null || !origin.contains("web.traceint.com"),
        )
        assertTrue(
            "不该发臆造的 Referer（实际：$referer）",
            referer == null || !referer.contains("web.traceint.com"),
        )
    }

    /** 用户没提供 `v` 时，不能往 Cookie 里塞臆造的值。 */
    @Test
    fun `cookie omits version when user did not provide it`() {
        val noVersion = SeatSession("S", "A")
        assertFalse(
            "用户没给 v 就不该发 v（原来会强塞一个无依据的 5.5）：${noVersion.toCookieHeader()}",
            noVersion.toCookieHeader().contains("v="),
        )

        // 用户真给了就必须原样带上
        val withVersion = SeatSession("S", "A", version = "5.5")
        assertTrue(
            "用户提供了 v 就要带上：${withVersion.toCookieHeader()}",
            withVersion.toCookieHeader().contains("v=5.5"),
        )
    }

    /**
     * `reserueSeat` 的响应两种拼写都要能解析。
     *
     * 上游 schema 里并存两份文档，真正被调用的是拼错的 `reserueSeat`。
     * 解析器对两者都兼容，避免哪天上游改回来就静默失败。
     */
    @Test
    fun `reserve outcome accepts both spellings`() {
        val correctSpelling = """{"data":{"userAuth":{"reserve":{"reserueSeat":"tok123"}}}}"""
        val legacySpelling = """{"data":{"userAuth":{"reserve":{"reserveSeat":"tok456"}}}}"""

        val a = SeatResponseParser.parseReserveOutcome(correctSpelling)
        assertTrue("reserueSeat 形态要能解析，实际：$a", a is SeatResponseParser.ReserveOutcome.Success)
        assertEquals("tok123", (a as SeatResponseParser.ReserveOutcome.Success).token)

        val b = SeatResponseParser.parseReserveOutcome(legacySpelling)
        assertTrue("reserveSeat 形态也要兜底支持，实际：$b", b is SeatResponseParser.ReserveOutcome.Success)
        assertEquals("tok456", (b as SeatResponseParser.ReserveOutcome.Success).token)
    }
}
