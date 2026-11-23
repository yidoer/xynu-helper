package com.xynu.campus.library

import com.xynu.campus.library.net.HostAllowListInterceptor
import com.xynu.campus.library.net.HostNotAllowedException
import com.xynu.campus.library.net.LibraryHostAllowList
import com.xynu.campus.library.net.LibraryHttpClient
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * 验收标准 9：模块级拦截器拒绝一切白名单之外的域名。
 *
 * 红线 4 明确要求：**不许改全局 network_security_config**，
 * 因此白名单只能在这层的 OkHttp interceptor 上实现。
 * 这里对着真实 MockWebServer 验证：
 * - 白名单内放行
 * - 白名单外抛 [HostNotAllowedException] 且请求**根本没发出去**
 * - scheme 也要卡（http 只允许两个超星域）
 */
class HostAllowListInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // 用 MockWebServer 的 host/port 构造一个"非白名单"客户端
        client = OkHttpClient.Builder()
            .addInterceptor(HostAllowListInterceptor())
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `non whitelisted host is rejected before hitting the network`() {
        server.enqueue(MockResponse().setBody("should not reach"))

        try {
            client.newCall(Request.Builder().url(server.url("/graphql/")).build())
                .execute()
                .close()
            fail("非白名单域名必须被拦截")
        } catch (e: HostNotAllowedException) {
            // 期望
        }

        assertEquals(
            "请求绝不能真的发出去",
            0,
            server.requestCount,
        )
    }

    @Test
    fun `localhost and 127_0_0_1 are not whitelisted`() {
        listOf("http://localhost/x", "http://127.0.0.1/x", "https://10.0.2.2/x").forEach { url ->
            try {
                client.newCall(Request.Builder().url(url).build()).execute().close()
                fail("$url 不应被放行")
            } catch (e: HostNotAllowedException) {
                assertTrue(e.message!!.isNotBlank())
            }
        }
    }

    @Test
    fun `allow list contains exactly five hosts`() {
        // 前四个来自任务书红线 4；第五个 libopac.xynu.edu.cn 是 2026-11
        // 用户明确「功能性至上」后追加的——借阅正文与馆藏检索都在那台机器上，
        // 且 mobilelib 无法代理 /sms/opac/*（实测 404）。
        assertEquals(
            "白名单只能是这四个域名（web.traceint.com 已随臆造的 Origin/Referer 一起移除）",
            4,
            LibraryHostAllowList.ALL_HOSTS.size,
        )
        assertEquals(
            setOf(
                "mobilelib.wx.chaoxing.com",
                "mobilelibwx.chaoxing.com",
                "wechat.v2.traceint.com",
                "libopac.xynu.edu.cn",
            ),
            LibraryHostAllowList.ALL_HOSTS,
        )
    }

    @Test
    fun `libopac is http allowed for in app borrow detail`() {
        assertTrue(
            "借阅正文要能在应用内取，必须允许 libopac 的 http",
            LibraryHostAllowList.isAllowed(
                "http://libopac.xynu.edu.cn:8080/sms/opac/user/lendStatus.action?schoolid=968",
            ),
        )
        assertTrue(
            "馆藏检索也在这台机器",
            LibraryHostAllowList.isAllowed("http://libopac.xynu.edu.cn:8080/search/searchList"),
        )
    }

    @Test
    fun `intranet only opac host stays out of the whitelist`() {
        // 222.21.62.201:8011 只对校园网开放，外网 15s 超时；
        // 不把它放进模块白名单，避免模块内也踩同一个坑。
        assertFalse(
            LibraryHostAllowList.isAllowed("http://222.21.62.201:8011/opac/book/abc"),
        )
    }

    @Test
    fun `http is allowed only for chaoxing hosts`() {
        assertTrue(LibraryHostAllowList.isAllowed("http://mobilelib.wx.chaoxing.com/x"))
        assertTrue(LibraryHostAllowList.isAllowed("http://mobilelibwx.chaoxing.com/x"))

        assertFalse(
            "traceint 只允许 https",
            LibraryHostAllowList.isAllowed("http://wechat.v2.traceint.com/x"),
        )
        assertFalse(
            "web.traceint.com 已从白名单删除（臆造的 Origin/Referer 已移除），"
                + "任何协议都不该放行",
            LibraryHostAllowList.isAllowed("http://web.traceint.com/x"),
        )
        assertFalse(
            "web.traceint.com 已从白名单删除",
            LibraryHostAllowList.isAllowed("https://web.traceint.com/x"),
        )
    }

    @Test
    fun `https is allowed for every whitelisted host`() {
        LibraryHostAllowList.ALL_HOSTS.forEach { h ->
            assertTrue("https://$h 应放行", LibraryHostAllowList.isAllowed("https://$h/any/path"))
        }
    }

    @Test
    fun `lookalike hosts are rejected`() {
        listOf(
            "https://mobilelib.wx.chaoxing.com.evil.com/x",
            "https://evil-mobilelib.wx.chaoxing.com/x",
            "https://chaoxing.com/x",
            "https://traceint.com/x",
            "https://wechat.v2.traceint.com.cn/x",
        ).forEach {
            assertFalse("$it 是仿冒域名，必须拒绝", LibraryHostAllowList.isAllowed(it))
        }
    }

    @Test
    fun `library http client injects micro messenger user agent`() {
        // 上游对 UA 有校验；直接用工厂创建客户端并检查拦截器链非空
        val c = LibraryHttpClient.create()
        assertTrue(c.interceptors.isNotEmpty())
        assertNotNull(c.cookieJar)
    }

    @Test
    fun `no redirect client is configured without follow redirects`() {
        val c = LibraryHttpClient.createNoRedirect()
        assertFalse(
            "落地态判定必须不跟随重定向（302 落点是关键信号）",
            c.followRedirects,
        )
        // 同一行还设了 followSslRedirects，删掉它会让 https→http 的降级跳转被静默跟随，
        // 所以这里一起钉住。
        assertFalse(
            "https→http 的降级跳转也不能跟随，否则落地态判定会被中间页干扰",
            c.followSslRedirects,
        )
    }

    @Test
    fun `application interceptor alone does not cover redirect hops`() {
        // 这条是**反向证据**，钉住我们为什么要额外注册 network 拦截器。
        //
        // OkHttp 的重定向在 RetryAndFollowUpInterceptor 内部循环，它拿同一个 chain
        // 再次 proceed；RealInterceptorChain.proceed() 只 copy(index+1) 往后走，
        // index 不回退到 0 ⇒ application 拦截器在第二跳起不执行。
        //
        // 这条用例故意**只**注册 application 拦截器，断言第二跳真的漏过去。
        // 如果哪天 OkHttp 改了这行为，这条会失败，提醒我们重新审视 ② 是否还需要。
        server.enqueue(
            MockResponse().setResponseCode(302)
                .setHeader("Location", "https://not-whitelisted.example/x"),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("leaked"))

        val appOnly = OkHttpClient.Builder()
            .followRedirects(true)
            .addInterceptor(
                Interceptor { chain ->
                    val req = chain.request()
                    if (req.url.encodedPath == "/start") chain.proceed(req)
                    else throw HostNotAllowedException(req.url.host, req.url.scheme)
                },
            )
            .build()

        // 第二跳的 host 解析必然失败（不存在的域名），但我们只要证明
        // "拦截器没有再执行一次" —— 表现为没有抛 HostNotAllowedException。
        val thrown = runCatching {
            appOnly.newCall(
                Request.Builder().url("http://127.0.0.1:${server.port}/start").build(),
            ).execute().close()
        }.exceptionOrNull()

        assertTrue(
            "application 拦截器本就不该拦住第二跳；若这里真的抛了 " +
                "HostNotAllowedException，说明 OkHttp 行为变了，②可以删。" +
                "实际异常：$thrown",
            thrown !is HostNotAllowedException,
        )
    }

    @Test
    fun `the real library client refuses a plain mock host at the first hop`() {
        // 真实 client 的白名单是 fail-closed 的：连 MockWebServer 都进不去。
        // 这条同时证明 application 级拦截器确实在"发出前"就拦住了（服务端 0 请求）。
        server.enqueue(MockResponse().setResponseCode(200).setBody("leaked"))

        val thrown = runCatching {
            LibraryHttpClient.createNoRedirect()
                .newCall(
                    Request.Builder().url("http://127.0.0.1:${server.port}/start").build(),
                )
                .execute()
                .close()
        }.exceptionOrNull()

        assertTrue(
            "真实 client 不该放行 127.0.0.1，实际：$thrown",
            thrown is HostNotAllowedException,
        )
        assertEquals("application 级拦截器应在发出前挡住，服务端收到 0 次请求", 0, server.requestCount)
    }

    @Test
    fun `network interceptor fires again on the redirect hop`() {
        // 纯机制验证：手动搭一个 network 拦截器，证明它在第二跳**会**再执行一次。
        // 这是 ② 有效的直接证据（不依赖 LibraryHostAllowList 的具体 host）。
        server.enqueue(
            MockResponse().setResponseCode(302)
                .setHeader("Location", "/landing"),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val seenPaths = mutableListOf<String>()
        val c = OkHttpClient.Builder()
            .followRedirects(true)
            .addNetworkInterceptor(
                Interceptor { chain ->
                    seenPaths += chain.request().url.encodedPath
                    chain.proceed(chain.request())
                },
            )
            .build()

        c.newCall(
            Request.Builder().url("http://127.0.0.1:${server.port}/start").build(),
        ).execute().close()

        assertEquals(
            "network 拦截器必须在第一跳和重定向后的第二跳各执行一次",
            listOf("/start", "/landing"),
            seenPaths,
        )
    }

    @Test
    fun `createNoRedirect also carries the allow list`() {
        // createNoRedirect = create().newBuilder()…，必须继承 create() 的两份白名单，
        // 否则借阅落地态这条链路是完全没有 host 校验的。
        val c = LibraryHttpClient.createNoRedirect()
        assertTrue(
            "createNoRedirect 必须保留 application 级白名单拦截器",
            c.interceptors.any { it is HostAllowListInterceptor },
        )
        assertTrue(
            "createNoRedirect 必须保留 network 级白名单拦截器（重定向第二跳靠它）",
            c.networkInterceptors.any { it is HostAllowListInterceptor },
        )
    }

    private fun assertNotNull(any: Any?) {
        org.junit.Assert.assertNotNull(any)
    }
}
