package com.xynu.campus.library.web

import android.webkit.JavascriptInterface
import com.xynu.campus.library.crypto.OpenIdExtractor
import com.xynu.campus.library.model.SeatSession
import com.xynu.campus.library.store.IdentityStore
import com.xynu.campus.library.util.LibraryLog
import org.json.JSONObject

/**
 * JSBridge：本地 HTML 壳与原生层之间的**唯一**通道。
 *
 * 红线约束（验收标准 3）：只暴露四个 `@JavascriptInterface` 方法，一个不多：
 * 1. [readClipboard]  —— 读剪贴板（openid 提取用，前台触发）
 * 2. [saveIdentity]   —— 存身份（openid / 选座 Cookie）
 * 3. [requestUpstream] —— 走超星移动图书馆（借阅域）的网络请求
 * 4. [requestSeat]    —— 走 traceint 选座域的网络请求
 *
 * 安全设计：
 * - 所有网络请求都经 [LibraryHttpClient] 的 whitelist 拦截器，HTML 侧拿不到任意 URL 的能力
 * - 返回给 JS 的永远是脱敏后的结构，**绝不回传存储里的 cookie 原文**
 *   （例外只有 [readClipboard]：它回的是用户刚复制的剪贴板文本原文，
 *   里面可能就含一串 Cookie。这是需要保留的——用户点「我复制好了」就是要它。
 *   但别把"不回传 cookie"当成这条路径也安全）
 * - 任何方法都不抛异常到 JS（异常会让 WebView 白屏），统一返回 `{ok:false, reason}` JSON
 *
 * ## ⚠️ 目前未接线
 *
 * 这个桥**从未被注册到任何 WebView 上**——唯一的 `addJavascriptInterface`
 * 在 [LibraryWebView] 里，而那个 composable 没有调用方。
 * 详见 [LibraryWebView] 的注释与 `docs/review-notes.md` §9.16。
 */
class LibraryJsBridge(
    private val deps: Deps,
) {

    /**
     * 依赖注入点。
     *
     * 之所以用 `Deps` 而不是直接拿 Context/Repository：
     * 这样单元测试可以塞假实现，不必起 WebView。
     */
    interface Deps {
        /** 读取剪贴板文本（调用方需保证在前台且已获焦点）。 */
        fun readClipboardText(): String?

        /** 身份存储。 */
        val store: IdentityStore

        /**
         * 借阅域上游请求（超星移动图书馆）。
         *
         * @param pathAndQuery 相对路径，例如
         *   `weixin/userBind/openItemNeedBind.action?wxProductId=1512&itemId=20302&openid=...`
         * @return 响应体文本；网络失败返回 null
         */
        fun requestUpstream(pathAndQuery: String, method: String, body: String?): String?

        /**
         * 选座域 GraphQL 请求（traceint）。
         *
         * @param operationName GraphQL operationName
         * @param query 文档原文
         * @param variablesJson variables 的 JSON 字符串
         * @return 响应体文本；失败返回 null
         */
        fun requestSeat(operationName: String, query: String, variablesJson: String): String?
    }

    // ==================== 四个 @JavascriptInterface（仅此四个）====================

    /**
     * 1. 读剪贴板。
     *
     * 只在用户点了「我复制好了」之后由 JS 调用（Android 10+ 后台读剪贴板被禁），
     * 返回结构：`{ok:true, text:"..."}` 或 `{ok:false, reason:"..."}`。
     */
    @JavascriptInterface
    fun readClipboard(): String = safe("readClipboard") {
        val text = deps.readClipboardText()
        if (text.isNullOrBlank()) {
            json(ok = false, reason = "剪贴板是空的。请先在微信里复制链接。")
        } else {
            // 顺手做一次提取，让 HTML 侧不必再懂正则
            when (val r = OpenIdExtractor.extract(text)) {
                is OpenIdExtractor.Result.Success -> {
                    LibraryLog.i(TAG, "readClipboard ok openid=${OpenIdExtractor.mask(r.openid)}")
                    json(
                        ok = true,
                        extra = mapOf(
                            "openid" to r.openid,
                            "masked" to OpenIdExtractor.mask(r.openid),
                        ),
                        payload = text,
                    )
                }

                is OpenIdExtractor.Result.Failed -> {
                    LibraryLog.i(TAG, "readClipboard failed reason=${r.reason.name}")
                    json(
                        ok = false,
                        reason = r.reason.message,
                        extra = mapOf("code" to r.reason.name),
                    )
                }
            }
        }
    }

    /**
     * 2. 保存身份。
     *
     * 入参是 JSON 字符串：
     * - `{"type":"openid","openid":"o..."}` 
     * - `{"type":"seat","cookie":"wechatSESS_ID=...; Authorization=...; FROM_CODE=..."}`
     */
    @JavascriptInterface
    fun saveIdentity(payloadJson: String): String = safe("saveIdentity") {
        val obj = runCatching { JSONObject(payloadJson) }.getOrNull()
            ?: return@safe json(ok = false, reason = "参数不是合法 JSON。")

        when (obj.optString("type")) {
            "openid" -> {
                val openid = obj.optString("openid", "")
                if (!OpenIdExtractor.isWellFormed(openid)) {
                    return@safe json(ok = false, reason = "openid 格式不对，应以 o 开头的长串。")
                }
                deps.store.saveBorrowIdentity(openid)
                LibraryLog.i(TAG, "saveIdentity openid saved ${OpenIdExtractor.mask(openid)}")
                json(ok = true, extra = mapOf("masked" to OpenIdExtractor.mask(openid)))
            }

            "seat" -> {
                val raw = obj.optString("cookie", "")
                val session = SeatSession.parseRawCookie(raw)
                    ?: return@safe json(
                        ok = false,
                        reason = "Cookie 里没认出 wechatSESS_ID 和 Authorization 这两项。",
                    )
                deps.store.saveSeatSession(session)
                LibraryLog.i(TAG, "saveIdentity seat saved (cookie=***)")
                json(ok = true)
            }

            else -> json(ok = false, reason = "未知的身份类型。")
        }
    }

    /**
     * 3. 借阅域上游请求。
     *
     * 入参 JSON：`{"path":"weixin/userBind/openItemNeedBind.action?...","method":"GET","body":null}`
     * 返回：`{ok:true, body:"<html>..."}` / `{ok:false, reason:"..."}`
     */
    @JavascriptInterface
    fun requestUpstream(payloadJson: String): String = safe("requestUpstream") {
        val obj = runCatching { JSONObject(payloadJson) }.getOrNull()
            ?: return@safe json(ok = false, reason = "参数不是合法 JSON。")

        val path = obj.optString("path", "")
        if (path.isBlank()) return@safe json(ok = false, reason = "path 不能为空。")
        // 只接受"干净的相对路径"。原来的判据是 `path.contains("://")`，
        // 这漏掉了一整类构造：
        //   //evil.example/x      —— 协议相对 URL，OkHttp 会当绝对地址解析
        //   \\evil.example\x      —— 反斜杠，部分解析器等价于 //
        //   /\evil.example/x      —— 同上
        //   /a/../../x            —— 路径穿越
        //   含 CR/LF 或 NUL       —— 请求行注入 / 头部注入
        // 白名单形态比黑名单可靠：必须 / 开头，且只能由
        // 字母数字和 `/?&=%._-~+,:;@!$'()*[]` 组成（覆盖上游实际使用的
        // `userBind/openItemNeedBind.action?x=1&y=2` 这类路径）。
        val pathError = validateRelativePath(path)
        if (pathError != null) return@safe json(ok = false, reason = pathError)

        val method = obj.optString("method", "GET").uppercase()
        val body = if (obj.isNull("body")) null else obj.optString("body", "").ifBlank { null }

        val text = deps.requestUpstream(path, method, body)
            ?: return@safe json(ok = false, reason = "上游请求失败（网络不通或被域名白名单拒绝）。")

        json(ok = true, payload = text)
    }

    /**
     * 4. 选座域 GraphQL 请求。
     *
     * 入参 JSON：`{"operationName":"list","query":"query list {...}","variables":"{}"}`
     * 返回：`{ok:true, body:"{\"data\":...}"}` / `{ok:false, reason:"..."}`
     */
    @JavascriptInterface
    fun requestSeat(payloadJson: String): String = safe("requestSeat") {
        val obj = runCatching { JSONObject(payloadJson) }.getOrNull()
            ?: return@safe json(ok = false, reason = "参数不是合法 JSON。")

        val op = obj.optString("operationName", "")
        val query = obj.optString("query", "")
        if (op.isBlank() || query.isBlank()) {
            return@safe json(ok = false, reason = "operationName 和 query 都不能为空。")
        }
        val variables = obj.optString("variables", "{}").ifBlank { "{}" }

        val text = deps.requestSeat(op, query, variables)
            ?: return@safe json(ok = false, reason = "选座请求失败（网络不通、未配置 Cookie 或被域名白名单拒绝）。")

        json(ok = true, payload = text)
    }

    // ==================== 内部工具 ====================

    /**
     * 校验 `path` 是一个安全的相对路径，通过返回 null，否则返回给 JS 的理由。
     *
     * 白名单而不是黑名单，理由见 [requestUpstream] 调用处的注释。
     *
     * 注意：上游实际用的 path 是**不带前导斜杠**的
     * （`weixin/userBind/openItemNeedBind.action?...`），所以不能要求 `/` 开头，
     * 只能要求"不是绝对路径"。
     */
    private fun validateRelativePath(path: String): String? {
        if (path.contains('\\')) return "path 不能包含反斜杠。"
        // 协议相对地址 `//host/x` 会被解析成绝对 URL，必须单独拦。
        if (path.startsWith("//")) return "path 不能以 // 开头（会被当成绝对地址）。"
        // 含 scheme 的一律拒绝（`http:` `https:` `javascript:` `data:` `file:` …）。
        // 用"第一个 / 或 ? 之前出现冒号"来判定，比 contains("://") 严：
        // `javascript:alert(1)` 没有 `//`，但同样是绝对地址。
        val head = path.substringBefore('/').substringBefore('?')
        if (head.contains(':')) return "path 不能包含 scheme（只接受相对路径）。"
        if (path.contains("..")) return "path 不能包含 ..（路径穿越）。"
        // 控制字符（含 CR/LF/TAB/NUL）一律拒绝：否则能构造请求行或头部注入。
        if (path.any { it.code < 0x20 || it.code == 0x7f }) return "path 不能包含控制字符。"
        if (!ALLOWED_PATH_CHARS.matches(path)) {
            return "path 含不允许的字符（只允许相对路径与常见 query 字符）。"
        }
        return null
    }

    /** 允许出现在相对路径里的字符集。故意不含反斜杠、空格与引号。 */
    private val ALLOWED_PATH_CHARS = Regex("""^[A-Za-z0-9/?&=%._~+,:;@!$'()*\[\]-]+$""")

    /**
     * 统一包装：任何异常都转成 `{ok:false}`，绝不让异常穿透到 JS 层。
     *
     * `@JavascriptInterface` 方法抛异常会直接导致 WebView 崩溃/白屏，
     * 而 HTML 壳是本地资源，崩了用户只能看到一片空白，很难自查。
     */
    private inline fun safe(method: String, block: () -> String): String = try {
        block()
    } catch (e: Throwable) {
        LibraryLog.e(TAG, "$method threw ${e.javaClass.simpleName}: ${e.message}")
        json(ok = false, reason = "内部错误：${e.javaClass.simpleName}")
    }

    private fun json(
        ok: Boolean,
        reason: String? = null,
        payload: String? = null,
        extra: Map<String, String> = emptyMap(),
    ): String = JSONObject().apply {
        put("ok", ok)
        reason?.let { put("reason", it) }
        payload?.let { put("body", it) }
        if (extra.isNotEmpty()) {
            put("extra", JSONObject(extra as Map<*, *>))
        }
    }.toString()

    /** JS 侧注册用的名字，固定不可改。 */
    companion object {
        const val JS_NAME = "XynuLibrary"
        private const val TAG = "LibraryJsBridge"

        /** 供测试断言：必须恰好是这四个方法名。 */
        val EXPOSED_METHODS = listOf(
            "readClipboard",
            "saveIdentity",
            "requestUpstream",
            "requestSeat",
        )
    }
}
