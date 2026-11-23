package com.xynu.campus.library

import com.xynu.campus.data.net.AppCookieJar
import com.xynu.campus.library.graphql.SeatGraphQlClient
import com.xynu.campus.library.model.SeatSession
import com.xynu.campus.library.net.LibraryHostAllowList
import com.xynu.campus.library.net.LibraryHttpClient
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 用户反馈的两个真实缺陷的回归测试：
 *
 * 1. 「选座助手给了 Cookie 但还是无法读取数据」
 * 2. 「一旦给 Cookie，删后台再次打开直接闪退」
 *
 * 缺陷 1 的根因是 OkHttp 的 cookieJar 会**整段替换**手写的 Cookie 头。
 * 这里用真的 OkHttp 栈 + 真的 cookieJar 复现，确保以后不会有人
 * 又把 `cookieJar` 加回选座客户端。
 *
 * 缺陷 2 的根因是 `SeatViewModel` 里 `refreshThrottle` 声明在 `init` 之后，
 * 冷启动时 init → loadLibraries 读到 null → NPE。那条链依赖 Android 的
 * Application/DataStore，只能在 instrumentation 里测；这里改为守住
 * 「冷启动第一屏不该崩」的等价约束：构造 VM 之前必须先声明好节流器，
 * 由 `SeatViewModelInitOrderTest` 用反射校验（见另一个文件）。
 */
class SeatClientCookieAndFailureTest {

    private lateinit var server: MockWebServer

    private val session = SeatSession(
        wechatSessId = "SESS_TOKEN_VALUE",
        authorization = "AUTH_JWT_VALUE",
        fromCode = "FROMCODE1",
    )

    @Before
    fun setUp() {
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        server.shutdown()
        AppCookieJar.clearAll()
    }

    /**
     * 把 traceint 域名改写到 MockWebServer，同时让白名单也认这台 mock。
     *
     * 这里有**两件**必须同时做的事，少一件测试就红：
     *
     * 1. **改写 URL**：`SeatGraphQlClient` 里硬编码 `https://wechat.v2.traceint.com`，
     *    不改写就会真的去打外网（单测环境没网 → 5 秒超时）。
     * 2. **白名单注入**：改写发生在 chain 内部，而 network 白名单看的是改写**之后**
     *    的 URL，mock 的 `127.0.0.1:随机端口` 会被拒、请求发不出去，
     *    `takeRequest()` 就永远等不到东西（实测挂死 26 分钟）。
     *
     * 上一版只做了 2 没做 1，结果 15 个测试全红在"5 秒内没收到请求"——
     * 这个错误信息本身就是我加超时时写下的诊断，省了一次猜谜。
     */
    private fun rewrite(forJar: Boolean): OkHttpClient {
        val allowed: (okhttp3.HttpUrl) -> Boolean = { url ->
            // mock 放行；其余仍走生产白名单。
            (url.host == server.hostName && url.port == server.port) ||
                LibraryHostAllowList.isAllowed(url)
        }
        val base = if (forJar) {
            LibraryHttpClient.createForTest(allowed)
        } else {
            LibraryHttpClient.createForSeatGraphQlForTest(allowed)
        }
        return base.newBuilder()
            .addInterceptor { chain ->
                val req = chain.request()
                val newUrl = req.url.newBuilder()
                    .scheme("http")
                    .host(server.hostName)
                    .port(server.port)
                    .build()
                chain.proceed(req.newBuilder().url(newUrl).build())
            }
            .build()
    }

    /** 带超时的 `takeRequest`：挂死比失败更难查，宁可 5 秒报错。 */
    private fun takeRequestOrFail(): RecordedRequest =
        server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
            ?: throw AssertionError(
                "MockWebServer 在 5 秒内没收到任何请求 —— " +
                    "通常是白名单把改写后的 mock host 拒了，或请求根本没发出去。",
            )

    private fun okBody(json: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(json)

    // ---------- 缺陷 1：Cookie 必须真的发出去 ----------

    /**
     * 选座客户端**不能**带 cookieJar。
     *
     * 这是回归的正面断言：如果谁把 `LibraryHttpClient.create()` 换回来，
     * 这个断言会立刻失败，因为那个 client 一定挂着 AppCookieJar。
     */
    @Test
    fun `seat client has no cookie jar`() {
        val client = LibraryHttpClient.createForSeatGraphQl()
        // OkHttp 的默认 CookieJar 是 NO_COOKIES，不会覆盖请求头
        assertEquals(
            "选座客户端必须不挂 cookieJar，否则手写的 Cookie 头会被整段替换",
            okhttp3.CookieJar.NO_COOKIES,
            client.cookieJar,
        )
    }

    /**
     * 最关键的复现：**cookieJar 里有同域 cookie 时，手写的 Cookie 头会被吞掉**。
     *
     * 前半段（有 jar）证明这个陷阱真实存在；后半段（无 jar，即修复后的形态）
     * 证明用户粘贴的会话确实到达了上游。两段缺一不可 —— 只写后半段的话，
     * 以后有人把 jar 加回来这个测试也照样过，因为 jar 是空的。
     */
    @Test
    fun `cookie jar overwrites explicit header but seat client keeps it`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse = okBody(
                """{"data":{"userAuth":{"reserve":{"libs":[]}}}}""",
            )
        }
        server.start()

        // 往 jar 里塞一条同域 cookie —— 模拟「网页版登录过」或上一版会话的残留。
        // 注意这里必须用真实 host，因为改写后 OkHttp 看到的 URL 就是 MockWebServer 的域名。
        val stubbed = rewrite(forJar = true)
        val jarUrl = okhttp3.HttpUrl.Builder()
            .scheme("http").host(server.hostName).port(server.port).build()
        AppCookieJar.saveFromResponse(
            jarUrl,
            listOf(
                okhttp3.Cookie.Builder()
                    .name("wechatSESS_ID")
                    .value("STALE_FROM_JAR")
                    .domain(jarUrl.host)
                    .path("/")
                    .build(),
            ),
        )

        runBlocking {
            // 用带 jar 的 client 发一次带显式 Cookie 头的请求
            val withJar = SeatGraphQlClient(stubbed)
            withJar.verifySession(session)
        }
        val seenByJar = takeRequestOrFail()
        val jarCookie = seenByJar.getHeader("Cookie").orEmpty()
        assertTrue(
            "陷阱前提：带 cookieJar 时手写的 Cookie 会被替换成 jar 里的内容。实际发出：$jarCookie",
            jarCookie.contains("STALE_FROM_JAR"),
        )
        assertFalse(
            "陷阱前提：带 cookieJar 时用户粘贴的会话根本发不出去",
            jarCookie.contains("SESS_TOKEN_VALUE"),
        )

        // 修复后的形态：无 jar 的选座 client
        val fixed = rewrite(forJar = false)
        runBlocking {
            val seat = SeatGraphQlClient(fixed)
            seat.verifySession(session)
        }
        val seenByFixed = takeRequestOrFail()
        val goodCookie = seenByFixed.getHeader("Cookie").orEmpty()
        assertTrue(
            "修复后用户粘贴的 wechatSESS_ID 必须出现在请求头里，实际：$goodCookie",
            goodCookie.contains("wechatSESS_ID=SESS_TOKEN_VALUE"),
        )
        assertTrue(
            "修复后 Authorization 也必须带上，实际：$goodCookie",
            goodCookie.contains("Authorization=AUTH_JWT_VALUE"),
        )
        assertTrue(
            "修复后 FROM_CODE 也必须带上，实际：$goodCookie",
            goodCookie.contains("FROM_CODE=FROMCODE1"),
        )
        assertFalse("修复后不该再被 jar 里的旧 cookie 污染", goodCookie.contains("STALE_FROM_JAR"))
    }

    // ---------- 缺陷 2 的相关面：上游变化不能把页面带崩 ----------

    /**
     * 上游返回 HTML（风控页 / 门禁页 / 改版）时，必须返回可展示的 Failed，
     * 而不是让 JSONObject 抛异常穿透到 UI 层。
     */
    @Test
    fun `html response is reported as failed instead of throwing`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse = MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("<html><body>请先登录</body></html>")
        }
        server.start()

        val client = SeatGraphQlClient(rewrite(forJar = false))
        val r = runBlocking { client.verifySession(session) }
        assertTrue("HTML 响应必须被识别成 Failed，实际：$r", r is SeatGraphQlClient.Res.Failed)
        val msg = (r as SeatGraphQlClient.Res.Failed).message
        assertTrue("要给中文可读原因，实际：$msg", msg.contains("不是 JSON"))
    }

    /**
     * 空的 200 响应仍然报「空响应」，保持原来的可读提示。
     */
    @Test
    fun `blank body is reported as failed`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(200).setBody("")
        }
        server.start()

        val client = SeatGraphQlClient(rewrite(forJar = false))
        val r = runBlocking { client.verifySession(session) }
        assertTrue(r is SeatGraphQlClient.Res.Failed)
        assertTrue((r as SeatGraphQlClient.Res.Failed).message.contains("空响应"))
    }

    /**
     * 正常 JSON 仍然是 Ok，且能把 libraries 解析出来 —— 确认修复没把主链路改坏。
     */
    @Test
    fun `valid json still parses libraries`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse = okBody(
                """{"data":{"userAuth":{"reserve":{"libs":[
                    {"lib_id":7,"lib_name":"第一图书馆","is_open":true,"lib_type":1,
                     "lib_rt":{"seats_total":200,"seats_used":50,"seats_booking":3}}
                ]}}}}""",
            )
        }
        server.start()

        val client = SeatGraphQlClient(rewrite(forJar = false))
        val r = runBlocking { client.listLibraries(session) }
        assertTrue("期望 Ok，实际：$r", r is SeatGraphQlClient.Res.Ok)
        val libs = (r as SeatGraphQlClient.Res.Ok).value
        assertEquals(1, libs.size)
        assertEquals("第一图书馆", libs[0].libName)
        assertEquals(7, libs[0].libId)
        assertTrue("总座位数应被解析出来", libs[0].seatsTotal > 0)
    }
}
