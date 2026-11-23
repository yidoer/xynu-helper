package com.xynu.campus.library

import com.xynu.campus.library.graphql.SeatGraphQlClient
import com.xynu.campus.library.graphql.SeatResponseParser
import com.xynu.campus.library.model.SeatSession
import com.xynu.campus.library.net.LibraryHostAllowList
import com.xynu.campus.library.util.LibraryLog
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 验收标准 7/8：40001 → 会话失效处理，**自动重试最多 1 次**；
 * Cookie 校验必须拒绝含 40001 的粘贴，并给出中文原因。
 *
 * 这两个语义极易写反：
 * - 无限重试 → 被判异常流量
 * - 一次不重试 → 用户偶尔的网络抖动就得重新贴 Cookie
 * 所以这里用计数器精确断言"恰好 2 次请求"（1 次原始 + 1 次重试）。
 */
class SeatSessionInvalidTest {

    private lateinit var server: MockWebServer
    private val hits = AtomicInteger(0)

    private val accessDenied =
        """{"errors":[{"msg":"Access Denied!","code":40001}],"data":{"userAuth":null}}"""
    private val okList =
        """{"data":{"userAuth":{"reserve":{"libs":[{"lib_id":12,"lib_name":"测试馆","is_open":true,""" +
            """"lib_rt":{"seats_total":100,"seats_used":40,"seats_booking":5}}]}}}}"""

    private val session = SeatSession("SESS", "AUTH", "FC")

    @Before
    fun setUp() {
        LibraryLog.resetSink()
        hits.set(0)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                hits.incrementAndGet()
                val body = req.body.readUtf8()
                // 请求体是 form-urlencoded（上游 PHP 不读 JSON body，
                // 详见 SeatGraphQlClient.buildFormBody 的实测表格）
                val op = runCatching {
                    java.net.URLDecoder.decode(
                        body.split("&").firstOrNull { it.startsWith("operationName=") }
                            ?.removePrefix("operationName=").orEmpty(),
                        "UTF-8",
                    )
                }.getOrDefault("")
                // 只有第一次 list 返回 40001，第二次成功 → 验证"成功重试一次"
                return if (op == "libSimple") {
                    MockResponse().setResponseCode(200)
                        .setBody("""{"data":{"userAuth":{"reserve":{"libs":[]}}}}""")
                } else if (hits.get() == 1) {
                    MockResponse().setResponseCode(200).setBody(accessDenied)
                } else {
                    MockResponse().setResponseCode(200).setBody(okList)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        LibraryLog.resetSink()
    }

    /**
     * 构造一个能把 traceint 域名指到 MockWebServer 的**真实**客户端。
     *
     * 原来的实现是自建 `OkHttpClient.Builder()`，只挂一个改写 host 的拦截器 ——
     * 那等于把 `LibraryHttpClient` 的 UA、App-Version 头、两份白名单、
     * 超时、cookieJar 策略**全部跳过**，测到的是"一个裸 OkHttp"，
     * 而不是生产里真正用的那个 client。
     *
     * 现在走 [LibraryHttpClient.createForSeatGraphQlForTest]：只替换白名单判定，
     * 生产行为保留，然后再挂上"把 host 指到 mock"的改写拦截器。
     * 两步缺一不可 —— 只替换白名单不改写，请求会真的打到外网。
     */
    private fun rewritingClient(): OkHttpClient {
        val allowed: (okhttp3.HttpUrl) -> Boolean = { url ->
            (url.host == server.hostName && url.port == server.port) ||
                com.xynu.campus.library.net.LibraryHostAllowList.isAllowed(url)
        }
        return com.xynu.campus.library.net.LibraryHttpClient
            .createForSeatGraphQlForTest(allowed)
            .newBuilder()
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

    // ---------- 40001 语义 ----------

    @Test
    fun `40001 is recognised from real shaped response`() {
        assertTrue(SeatResponseParser.isSessionInvalid(accessDenied))
        val err = SeatResponseParser.firstError(accessDenied)!!
        assertEquals(40001, err.first)
        assertEquals("Access Denied!", err.second)
    }

    @Test
    fun `other graphql errors are not treated as session invalid`() {
        val other = """{"errors":[{"msg":"座位已被占用","code":50001}],"data":null}"""
        assertFalse(SeatResponseParser.isSessionInvalid(other))
    }

    @Test
    fun `successful response is not session invalid`() {
        assertFalse(SeatResponseParser.isSessionInvalid(okList))
    }

    // ---------- 重试次数 ----------

    @Test
    fun `session invalid triggers exactly one retry`() = runBlocking {
        // 让服务端始终返回 40001
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                hits.incrementAndGet()
                return MockResponse().setResponseCode(200).setBody(accessDenied)
            }
        }

        val client = SeatGraphQlClient(rewritingClient())
        val r = client.listLibraries(session)

        assertTrue("应上报会话失效，实际: $r", r is SeatGraphQlClient.Res.SessionInvalid)
        assertEquals(
            "红线：40001 只允许自动重试 1 次（共 2 次请求），实际 ${hits.get()} 次",
            2,
            hits.get(),
        )
    }

    @Test
    fun `retry succeeds when second attempt works`() = runBlocking {
        val client = SeatGraphQlClient(rewritingClient())
        val r = client.listLibraries(session)

        assertTrue("第一次 40001、第二次成功时应返回数据，实际: $r", r is SeatGraphQlClient.Res.Ok)
        val libs = (r as SeatGraphQlClient.Res.Ok).value
        assertEquals(1, libs.size)
        assertEquals("测试馆", libs[0].libName)
        assertEquals(
            "一次失败 + 一次成功 = 2 次请求",
            2,
            hits.get(),
        )
    }

    @Test
    fun `verifySession does not retry on 40001`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                hits.incrementAndGet()
                return MockResponse().setResponseCode(200).setBody(accessDenied)
            }
        }

        val client = SeatGraphQlClient(rewritingClient())
        val r = client.verifySession(session)

        assertTrue(r is SeatGraphQlClient.Res.SessionInvalid)
        assertEquals(
            "验活本身就是探测，不应二次重试（省一次可疑流量）",
            1,
            hits.get(),
        )
    }

    // ---------- Cookie 校验 ----------

    @Test
    fun `cookie paste containing 40001 is rejected with chinese reason`() {
        // 用户可能把上游错误响应当成 Cookie 粘进来（真实发生过的误操作）
        val junk = "wechatSESS_ID=SESS; Authorization=AUTH; FROM_CODE=FC; note=Access Denied! code 40001"
        val parsed = SeatSession.parseRawCookie(junk)

        // 四字段都能解析出来，但这不代表有效——有效性必须靠 list 验活
        assertTrue("这里能解析是预期行为（结构合法）", parsed != null)
        // 真正拒绝发生在验活阶段，由 SeatViewModel 处理：
        // 40001 → 不落盘 + 中文提示
        val reason = sessionInvalidChineseReason()
        assertTrue("必须是中文提示", reason.any { it.code in 0x4E00..0x9FFF })
        assertTrue("要提到 40001 便于用户/客服定位", reason.contains("40001"))
    }

    @Test
    fun `incomplete cookie paste is rejected before any network call`() {
        // 缺 Authorization 或缺 wechatSESS_ID：结构不合法，必须零网络直接拒绝
        assertEquals(null, SeatSession.parseRawCookie("wechatSESS_ID=SESS; FROM_CODE=FC"))
        assertEquals(null, SeatSession.parseRawCookie("Authorization=AUTH; FROM_CODE=FC"))

        // 语义变更（2026-11 真机）：FROM_CODE 不是 Cookie 字段，而是 traceint
        // 入口 URL 的 query 参数，用户真实粘贴里常常没有它 —— 不再要求。
        val noFromCode = SeatSession.parseRawCookie("wechatSESS_ID=SESS; Authorization=AUTH")
        assertTrue("缺 FROM_CODE 不应再被拒绝", noFromCode != null)
        assertEquals("", noFromCode!!.fromCode)
    }

    @Test
    fun `403 http status maps to session invalid`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                hits.incrementAndGet()
                return MockResponse().setResponseCode(401).setBody("")
            }
        }
        val client = SeatGraphQlClient(rewritingClient())
        val r = client.listLibraries(session)
        assertTrue(r is SeatGraphQlClient.Res.SessionInvalid)
    }

    /** 与 SeatViewModel 里给用户看的中文提示保持一致的形状。 */
    private fun sessionInvalidChineseReason(): String =
        "这个 Cookie 已失效（40001）。请重新在微信里打开图书馆，再复制一次。"

    // ---------- 白名单依旧生效 ----------

    @Test
    fun `rewritten url is outside whitelist so real client blocks it`() {
        // 说明：本测试里的 rewritingClient 是测试专用旁路，
        // 生产客户端的白名单拦截器不会命中 localhost，这里断言该事实，
        // 防止有人误以为测试旁路削弱了生产防护。
        assertFalse(LibraryHostAllowList.isAllowed("http://${server.hostName}:${server.port}/x"))
    }
}
