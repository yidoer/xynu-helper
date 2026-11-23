package com.xynu.campus.library

import com.xynu.campus.library.crypto.OpenIdExtractor
import com.xynu.campus.library.model.SeatSession
import com.xynu.campus.library.store.IdentityStore
import com.xynu.campus.library.testutil.FakeSharedPreferences
import com.xynu.campus.library.util.LibraryLog
import com.xynu.campus.library.web.LibraryJsBridge
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 验收标准 3：JSBridge 只暴露**恰好四个** `@JavascriptInterface` 方法。
 *
 * 这条红线用反射硬校验，而不是靠 code review——
 * 因为多暴露一个方法就等于把一项设备能力交给本地 HTML，
 * 而 HTML 一旦被替换（例如被打包工具注入），攻击面立刻放大。
 */
class LibraryJsBridgeContractTest {

    private lateinit var store: IdentityStore
    private lateinit var bridge: LibraryJsBridge
    private var clipboardText: String? = null
    private var lastUpstreamPath: String? = null
    private var lastSeatOp: String? = null
    private var upstreamResponse: String? = "<html>ok</html>"
    private var seatResponse: String? = "{\"data\":{}}"

    @Before
    fun setUp() {
        LibraryLog.resetSink()
        store = IdentityStore.forTest(FakeSharedPreferences())
        bridge = LibraryJsBridge(object : LibraryJsBridge.Deps {
            override fun readClipboardText(): String? = clipboardText
            override val store: IdentityStore get() = this@LibraryJsBridgeContractTest.store
            override fun requestUpstream(
                pathAndQuery: String,
                method: String,
                body: String?,
            ): String? {
                lastUpstreamPath = pathAndQuery
                return upstreamResponse
            }

            override fun requestSeat(
                operationName: String,
                query: String,
                variablesJson: String,
            ): String? {
                lastSeatOp = operationName
                return seatResponse
            }
        })
    }

    @After
    fun tearDown() {
        LibraryLog.resetSink()
    }

    // ---------- 红线：方法数量与名字 ----------

    @Test
    fun `exactly four javascript interface methods are exposed`() {
        val annotated = LibraryJsBridge::class.java.declaredMethods
            .filter { m ->
                m.annotations.any { it.annotationClass.simpleName == "JavascriptInterface" }
            }
            .map { it.name }
            .sorted()

        assertEquals(
            "红线：只能暴露这四个 @JavascriptInterface 方法，实际: $annotated",
            listOf("readClipboard", "requestSeat", "requestUpstream", "saveIdentity"),
            annotated,
        )
    }

    @Test
    fun `exposed method names match the declared constant list`() {
        val annotated = LibraryJsBridge::class.java.declaredMethods
            .filter { m ->
                m.annotations.any { it.annotationClass.simpleName == "JavascriptInterface" }
            }
            .map { it.name }
            .sorted()
        assertEquals(LibraryJsBridge.EXPOSED_METHODS.sorted(), annotated)
    }

    @Test
    fun `no bridge method takes a Context or Activity parameter`() {
        // 若某个方法收 Context，HTML 侧就可能诱导原生层用任意 Context 做事
        LibraryJsBridge::class.java.declaredMethods
            .filter { it.annotations.any { a -> a.annotationClass.simpleName == "JavascriptInterface" } }
            .forEach { m: Method ->
                assertFalse(
                    "${m.name} 不应接收 Context 参数",
                    m.parameterTypes.any {
                        android.content.Context::class.java.isAssignableFrom(it)
                    },
                )
            }
    }

    @Test
    fun `all bridge methods return String so JS gets JSON not objects`() {
        LibraryJsBridge::class.java.declaredMethods
            .filter { it.annotations.any { a -> a.annotationClass.simpleName == "JavascriptInterface" } }
            .forEach { m ->
                assertEquals("${m.name} 必须返回 String（JSON 文本）", String::class.java, m.returnType)
                assertFalse("${m.name} 不应是 static", Modifier.isStatic(m.modifiers))
            }
    }

    // ---------- readClipboard ----------

    @Test
    fun `readClipboard returns extracted openid and mask`() {
        clipboardText = "http://mobilelib.wx.chaoxing.com/weixin/hall/showNew" +
            "?pid=1512&type=8&openid=oABCDEFGHIJKLMNOPQRSTUVWXYZ012345"

        val json = JSONObject(bridge.readClipboard())
        assertTrue(json.getBoolean("ok"))
        assertEquals(
            "oABCDEFGHIJKLMNOPQRSTUVWXYZ012345",
            json.getJSONObject("extra").getString("openid"),
        )
        assertTrue(json.getJSONObject("extra").getString("masked").contains("***"))
    }

    @Test
    fun `readClipboard reports empty clipboard in Chinese`() {
        clipboardText = null
        val json = JSONObject(bridge.readClipboard())
        assertFalse(json.getBoolean("ok"))
        assertTrue(json.getString("reason").contains("剪贴板"))
    }

    @Test
    fun `readClipboard never echoes the raw clipboard content on failure`() {
        clipboardText = "随便一段话没有链接"
        val raw = bridge.readClipboard()
        val json = JSONObject(raw)
        assertFalse(json.getBoolean("ok"))
        assertFalse("失败时不应回传原文", raw.contains("随便一段话"))
    }

    // ---------- saveIdentity ----------

    @Test
    fun `saveIdentity stores openid encrypted partition`() {
        val req = JSONObject().put("type", "openid")
            .put("openid", "oABCDEFGHIJKLMNOPQRSTUVWXYZ012345").toString()
        val json = JSONObject(bridge.saveIdentity(req))
        assertTrue(json.getBoolean("ok"))
        assertEquals(
            "oABCDEFGHIJKLMNOPQRSTUVWXYZ012345",
            store.borrowIdentity()!!.openid,
        )
    }

    @Test
    fun `saveIdentity rejects malformed openid`() {
        val req = JSONObject().put("type", "openid").put("openid", "not-an-openid").toString()
        val json = JSONObject(bridge.saveIdentity(req))
        assertFalse(json.getBoolean("ok"))
        assertNull(store.borrowIdentity())
    }

    @Test
    fun `saveIdentity parses seat cookie into four fields`() {
        val cookie = "wechatSESS_ID=SESS; Authorization=eyJhbGciOi.jwt.sig; FROM_CODE=FC; v=5.5"
        val req = JSONObject().put("type", "seat").put("cookie", cookie).toString()
        val json = JSONObject(bridge.saveIdentity(req))
        assertTrue(json.getBoolean("ok"))

        val s = store.seatSession()!!
        assertEquals("SESS", s.wechatSessId)
        assertEquals("eyJhbGciOi.jwt.sig", s.authorization)
        assertEquals("FC", s.fromCode)
    }

    @Test
    fun `saveIdentity rejects incomplete seat cookie`() {
        val req = JSONObject().put("type", "seat")
            .put("cookie", "wechatSESS_ID=SESS; FROM_CODE=FC").toString()
        val json = JSONObject(bridge.saveIdentity(req))
        assertFalse(json.getBoolean("ok"))
        assertTrue(json.getString("reason").contains("Authorization"))
        assertNull(store.seatSession())
    }

    @Test
    fun `saveIdentity rejects unknown type`() {
        val json = JSONObject(bridge.saveIdentity("""{"type":"password","v":"x"}"""))
        assertFalse(json.getBoolean("ok"))
    }

    @Test
    fun `saveIdentity tolerates malformed json without throwing`() {
        val json = JSONObject(bridge.saveIdentity("{{{not json"))
        assertFalse(json.getBoolean("ok"))
    }

    // ---------- requestUpstream ----------

    @Test
    fun `requestUpstream rejects absolute url to prevent whitelist bypass`() {
        val req = JSONObject().put("path", "http://evil.example.com/steal").toString()
        val json = JSONObject(bridge.requestUpstream(req))
        assertFalse("必须拒绝绝对 URL", json.getBoolean("ok"))
        assertNull("绝不能真的发出去", lastUpstreamPath)
    }

    @Test
    fun `requestUpstream forwards relative path`() {
        val path = "weixin/userBind/openItemNeedBind.action?wxProductId=1512&itemId=20302&openid=oX"
        val req = JSONObject().put("path", path).toString()
        val json = JSONObject(bridge.requestUpstream(req))
        assertTrue(json.getBoolean("ok"))
        assertEquals(path, lastUpstreamPath)
        assertEquals("<html>ok</html>", json.getString("body"))
    }

    /**
     * 旧判据是 `path.contains("://")`，它漏掉了一整类构造。
     * 这些 payload 以前**全部可以发出请求**，现在必须全部被拦下。
     */
    @Test
    fun `requestUpstream rejects every absolute-looking path shape`() {
        val payloads = listOf(
            "http://evil.example.com/steal",       // 原有用例，仍然拦
            "https://evil.example.com/steal",      // 原有用例，仍然拦
            "//evil.example.com/steal",            // 协议相对 URL：OkHttp 当绝对地址
            "\\\\evil.example.com\\steal",         // 反斜杠
            "javascript:alert(1)",                 // 无 `//` 但也是 scheme
            "data:text/html,<script>x</script>",   // 同上
            "file:///data/data/com.xynu.campus/x", // 同上
            "weixin/../../etc/passwd",             // 路径穿越
            "weixin/hall/showNew\r\nX-Injected: 1", // 头部/请求行注入
            "weixin/hall/showNew\u0000",           // NUL 截断
        )
        for (p in payloads) {
            lastUpstreamPath = null
            val req = JSONObject().put("path", p).toString()
            val json = JSONObject(bridge.requestUpstream(req))
            assertFalse("必须拒绝: ${p.replace("\r", "\\r").replace("\u0000", "\\0")}", json.getBoolean("ok"))
            assertNull("拒绝时绝不能真的发出去: $p", lastUpstreamPath)
        }
    }

    @Test
    fun `requestUpstream still accepts the real upstream paths`() {
        // 白名单校验不能把真实调用点拦死 —— 上游用的是不带前导斜杠的相对路径。
        val realPaths = listOf(
            "weixin/hall/showNew?pid=1&type=8",
            "weixin/userBind/openItemNeedBind.action?wxProductId=1512&itemId=20302",
            "/weixin/hall/showNew",
        )
        for (p in realPaths) {
            lastUpstreamPath = null
            val json = JSONObject(bridge.requestUpstream(JSONObject().put("path", p).toString()))
            assertTrue("不该拒绝真实路径: $p", json.getBoolean("ok"))
            assertEquals(p, lastUpstreamPath)
        }
    }

    @Test
    fun `requestUpstream reports network failure without throwing`() {
        upstreamResponse = null
        val req = JSONObject().put("path", "weixin/hall/showNew").toString()
        val json = JSONObject(bridge.requestUpstream(req))
        assertFalse(json.getBoolean("ok"))
        assertTrue(json.getString("reason").contains("白名单"))
    }

    // ---------- requestSeat ----------

    @Test
    fun `requestSeat requires operationName and query`() {
        val json = JSONObject(bridge.requestSeat("""{"operationName":"list"}"""))
        assertFalse(json.getBoolean("ok"))
        assertNull(lastSeatOp)
    }

    @Test
    fun `requestSeat forwards operation and returns body`() {
        val req = JSONObject()
            .put("operationName", "list")
            .put("query", "query list { userAuth { reserve { libs(libType: -1) { lib_id } } } }")
            .put("variables", "{}")
            .toString()
        val json = JSONObject(bridge.requestSeat(req))
        assertTrue(json.getBoolean("ok"))
        assertEquals("list", lastSeatOp)
    }

    // ---------- 异常不穿透 ----------

    @Test
    fun `bridge never throws even when dependencies throw`() {
        val throwing = LibraryJsBridge(object : LibraryJsBridge.Deps {
            override fun readClipboardText(): String? = throw IllegalStateException("boom")
            override val store: IdentityStore
                get() = throw IllegalStateException("boom")
            override fun requestUpstream(p: String, m: String, b: String?): String? =
                throw IllegalStateException("boom")

            override fun requestSeat(o: String, q: String, v: String): String? =
                throw IllegalStateException("boom")
        })

        // 四个方法都不能把异常抛到 JS（会导致 WebView 白屏）
        val results = listOf(
            throwing.readClipboard(),
            throwing.saveIdentity("""{"type":"openid","openid":"oABCDEFGHIJKLMNOPQRSTUVWXYZ01"}"""),
            throwing.requestUpstream("""{"path":"a"}"""),
            throwing.requestSeat("""{"operationName":"x","query":"y"}"""),
        )
        results.forEach { raw ->
            val json = JSONObject(raw)
            assertFalse(json.getBoolean("ok"))
            assertNotNull(json.getString("reason"))
        }
    }

    // ---------- 辅助：openid 校验 ----------

    @Test
    fun `isWellFormed accepts real shaped openid and rejects others`() {
        assertTrue(OpenIdExtractor.isWellFormed("oABCDEFGHIJKLMNOPQRSTUVWXYZ012345"))
        assertFalse(OpenIdExtractor.isWellFormed("oSHORT"))
        assertFalse(OpenIdExtractor.isWellFormed("xABCDEFGHIJKLMNOPQRSTUVWXYZ012345"))
        assertFalse(OpenIdExtractor.isWellFormed(""))
        assertFalse(OpenIdExtractor.isWellFormed(null))
    }

    @Test
    fun `session cookie header round trips through store`() {
        store.saveSeatSession(SeatSession("S", "A", "F"))
        val header = store.seatSession()!!.toCookieHeader()
        assertTrue(header.contains("wechatSESS_ID=S"))
        assertTrue(header.contains("Authorization=A"))
        assertTrue(header.contains("FROM_CODE=F"))
    }
}
