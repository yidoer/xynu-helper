package com.xynu.campus.library.web

import android.annotation.SuppressLint
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 承载本地 HTML 壳的 WebView 容器。
 *
 * 关键安全设置（红线 1 的要求：页面在 assets 里，数据走原生）：
 * - `allowFileAccess=false` / `allowContentAccess=false`：页面不需要读本地文件
 * - 只加载 `file:///android_asset/` 下的页面，不加载远程 URL
 * - 不开启 `addJavascriptInterface` 之外的任何桥
 * - 禁用 `setAllowUniversalAccessFromFileURLs`（默认 false，这里显式确认）
 *
 * 为什么还需要 WebView：任务书要求「Compose 页面 + 内嵌 WebView 承载本地 HTML」，
 * 用它来放 F1 的三步图文引导与富文本渲染，比纯 Compose 排版更接近原有网页观感。
 *
 * ---
 *
 * ## ⚠️ 当前未接线（重要，改前必读）
 *
 * 这个 composable **没有任何调用方**。全仓库 `addJavascriptInterface` 只出现在
 * 下面第 62 行，而 `LibraryWebView(...)` 只在这里定义、从未被调用
 * ⇒ `[LibraryJsBridge]` 的四个方法在真实运行时**从未注册到任何 WebView 上**，
 * JS 侧的 `XynuLibrary` 对象不存在。`androidDeps(...)` 同样零调用方。
 *
 * 真正在跑的引导页是 `BorrowWizardScreen`（纯 Compose，不用 WebView），
 * `assets/library/wizard.html` 因此也从未被加载。
 *
 * 也就是说：下面这一整套安全配置**目前保护的是一个不适用的对象**。
 * 它写得是对的，但不能作为"安全机制已验证"的证据——
 * 就像一条从没跑过 R8 的 `proguard-rules.pro`。
 * 详见 `docs/review-notes.md` §9.16。
 *
 * ## ⚠️ 接线前必须先修的已知缺陷
 *
 * [androidDeps] 里的 `requestSeat()` 用的是 `LibraryHttpClient.create()`，
 * 而那个 client **带 `AppCookieJar`**；它却手写了 `Cookie` 头。
 * OkHttp 的 `BridgeInterceptor` 会用 jar 内容**整段替换**手写的 `Cookie` 头
 * （不是合并），用户粘贴的选座会话会被静默顶掉——
 * 这正是 [com.xynu.campus.library.net.LibraryHttpClient.createForSeatGraphQl]
 * 的注释花 20 行警告的那件事。接线时把 `create()` 换成 `createForSeatGraphQl()`。
 *
 * 同理 `requestUpstream` 用带 jar 的 client 是**对的**（借阅域本来就要 cookie），
 * 不要一起改。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LibraryWebView(
    assetPath: String,
    bridge: LibraryJsBridge,
    modifier: Modifier = Modifier,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current

    val webView = remember {
        WebView(ctx).apply {
            settings.apply {
                javaScriptEnabled = true
                // 本地壳只需要 JS 做交互，不需要访问 file:// 其它文件
                allowFileAccess = false
                allowContentAccess = false
                // 显式关闭：file:// 页面不得发起跨域 XHR（所有网络都走 JSBridge）
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
                // 不落地缓存，避免把上游响应写进可被其它进程读的位置
                cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                domStorageEnabled = true
                // 页面全是本地资源，禁掉缩放避免排版抖动
                setSupportZoom(false)
                builtInZoomControls = false
            }
            // 强制所有跳转留在本 WebView 内并返回 true，杜绝页面自己跑去远程 URL
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                ): Boolean = true
            }
            addJavascriptInterface(bridge, LibraryJsBridge.JS_NAME)
        }
    }

    // 页面退出时必须摘掉桥并销毁，否则 WebView 泄漏且桥仍可被残留 JS 调用
    DisposableEffect(webView) {
        onDispose {
            runCatching {
                webView.removeJavascriptInterface(LibraryJsBridge.JS_NAME)
                webView.stopLoading()
                webView.destroy()
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        AndroidView(
            factory = { webView },
            update = { it.loadUrl("file:///android_asset/$assetPath") },
        )
    }
}

/**
 * 把真实的 Deps 接到 Android 环境上。
 *
 * 网络请求全部走 [com.xynu.campus.library.net.LibraryHttpClient] 的
 * 白名单客户端；这里不做任何 URL 拼接之外的加工。
 */
fun androidDeps(
    ctx: Context,
    readClipboard: () -> String?,
): LibraryJsBridge.Deps = object : LibraryJsBridge.Deps {

    private val appContext = ctx.applicationContext
    private val libraryStore: com.xynu.campus.library.store.IdentityStore by lazy {
        com.xynu.campus.library.store.IdentityStore.get(appContext)
    }
    private val client by lazy { com.xynu.campus.library.net.LibraryHttpClient.create() }

    override fun readClipboardText(): String? = readClipboard()

    override val store: com.xynu.campus.library.store.IdentityStore get() = libraryStore

    override fun requestUpstream(pathAndQuery: String, method: String, body: String?): String? {
        val url = "${com.xynu.campus.library.net.LibraryHttpClient.BASE_MOBILELIB}/$pathAndQuery"
        val builder = okhttp3.Request.Builder().url(url)
        if (method == "POST") {
            builder.post(
                (body ?: "").toRequestBody(
                    "application/x-www-form-urlencoded; charset=utf-8".toMediaTypeOrNull(),
                ),
            )
        }
        val req = runCatching { builder.build() }.getOrNull() ?: return null

        return runCatching {
            client.newCall(req).execute().use { it.body?.string() }
        }.getOrNull()
    }

    override fun requestSeat(
        operationName: String,
        query: String,
        variablesJson: String,
    ): String? {
        val session = store.seatSession() ?: return null

        // 上游是 PHP，只按字段名从 $_POST 取 query，**不解析 JSON body**。
        // 发 JSON 时它解析出空 query，返回 `Syntax Error: Unexpected <EOF>`，
        // 而错误文案完全不会提到"格式不对" —— 表现就是列表永远为空。
        // 真机四组对照见 docs/probe_findings.md §K。
        //
        // 这里曾经和 SeatGraphQlClient 一样发 JSON，还额外发了臆造的
        // `Origin`/`Referer`（web.traceint.com 在上游所有 fixture 里出现 0 次）
        // 和硬编码的 `App-Version: 2.0.11`（真实值是 2.2.9）。
        val form = buildString {
            append("operationName=").append(java.net.URLEncoder.encode(operationName, "UTF-8"))
            append("&query=").append(java.net.URLEncoder.encode(query, "UTF-8"))
            append("&variables=")
                .append(java.net.URLEncoder.encode(variablesJson, "UTF-8"))
        }

        val req = okhttp3.Request.Builder()
            .url("${com.xynu.campus.library.net.LibraryHttpClient.BASE_TRACEINT}/index.php/graphql/")
            .post(form.toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaTypeOrNull()))
            .header("App-Version", com.xynu.campus.library.net.LibraryHttpClient.TRACEINT_APP_VERSION)
            .header("Cookie", session.toCookieHeader())
            .build()

        return runCatching {
            client.newCall(req).execute().use { it.body?.string() }
        }.getOrNull()
    }
}
