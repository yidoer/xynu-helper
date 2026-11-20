package com.xynu.campus.ui.home

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.xynu.campus.data.net.AppCookieJar
import com.xynu.campus.ui.common.AppTopBar
import java.net.URI
import java.net.URLDecoder

const val SEAT_RESERVATION_HALL_URL =
    "http://mobilelib.wx.chaoxing.com/weixin/hall/showNew?pid=1512&type=8"
const val READER_CARD_BINDING_URL =
    "http://mobilelib.wx.chaoxing.com/weixin/userBind/showBindAndCheck.action?wxProductId=1512&schoolId=968"
const val LIBSPACE_RESERVATION_URL = "http://libspace.xynu.edu.cn/h5/#/login"

private const val WECHAT_MOBILE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
        "Version/4.0 Chrome/120.0.0.0 Mobile Safari/537.36 " +
        "MicroMessenger/8.0.43(0x28002B36) NetType/WIFI Language/zh_CN"
private const val MOBILE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
private const val PAGE_TIMEOUT_MS = 20_000L
private const val BLANK_CHECK_DELAY_MS = 1_500L
private const val WECHAT_PACKAGE = "com.tencent.mm"
private const val TAG = "WebScreen"

/**
 * 应用内 H5 容器：所有普通 HTTP(S) 导航都留在 WebView，非网页 scheme 才交给系统。
 * 不代替微信 OAuth；微信授权由站点真实跳转，WebView 只负责承载并如实展示失败。
 */
@Composable
fun WebScreen(
    encodedUrl: String,
    title: String?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val initialUrl = remember(encodedUrl) {
        try {
            Uri.decode(encodedUrl)
        } catch (_: Exception) {
            try { URLDecoder.decode(encodedUrl, "UTF-8") } catch (_: Exception) { encodedUrl }
        }
    }
    val initialHost = remember(initialUrl) { Uri.parse(initialUrl).host.orEmpty().lowercase() }
    val isLibraryH5 = remember(initialHost) {
        initialHost == "mobilelib.wx.chaoxing.com" ||
            initialHost == "libopac.xynu.edu.cn" ||
            initialHost == "wechat.v2.traceint.com" ||
            initialHost == "libspace.xynu.edu.cn" ||
            initialHost.endsWith(".traceint.com")
    }
    val isReservationH5 = remember(initialHost) {
        isLibraryH5 && initialHost != "libopac.xynu.edu.cn"
    }

    var webView by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf(initialUrl) }
    var pageTitle by remember { mutableStateOf(title ?: "浏览") }
    var isLoading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }

    val goBack: () -> Unit = {
        val view = webView
        if (view?.canGoBack() == true) view.goBack() else onBack()
    }
    BackHandler(enabled = true, onBack = goBack)

    fun openBrowser() {
        openExternal(context, currentUrl)
    }

    Scaffold(
        containerColor = Color(0xFFF5F6FA),
        topBar = {
            AppTopBar(
                title = pageTitle,
                onBack = goBack,
                action = {
                    IconButton(
                        onClick = { webView?.reload() },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(Icons.Filled.Refresh, "刷新", tint = Color(0xFF3A3A3C), modifier = Modifier.size(20.dp))
                    }
                    IconButton(
                        onClick = ::openBrowser,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(Icons.Filled.OpenInBrowser, "浏览器打开", tint = Color(0xFF3A3A3C), modifier = Modifier.size(20.dp))
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            key(reloadKey) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            configureH5WebView(
                                initialUrl = currentUrl,
                                useWeChatUserAgent = isLibraryH5,
                                onUrlChanged = { currentUrl = it },
                                onTitleChanged = { value ->
                                    if (!value.isNullOrBlank()) pageTitle = value
                                },
                                onLoadingChanged = { loading ->
                                    isLoading = loading
                                    if (loading) {
                                        loadFailed = false
                                        errorMessage = null
                                    }
                                },
                                onLoadSucceeded = {
                                    isLoading = false
                                    loadFailed = false
                                    errorMessage = null
                                },
                                onLoadFailed = { message ->
                                    loadFailed = true
                                    isLoading = false
                                    errorMessage = message
                                },
                                onOpenExternal = { target -> openExternal(context, target) },
                                onWebViewReady = { webView = this }
                            )
                            loadUrl(currentUrl)
                        }
                    },
                    onRelease = {
                        (it.getTag() as? PageLoadTimers)?.cancel()
                        webView = null
                        it.stopLoading()
                        it.destroy()
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (isLoading && !loadFailed) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(30.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.height(10.dp))
                    Text("正在打开网页…", fontSize = 13.sp, color = Color(0xFF8E8E93))
                }
            }

            if (loadFailed) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("页面无法正常显示", color = Color(0xFF8E8E93))
                    Spacer(Modifier.height(6.dp))
                    Text(
                        errorMessage ?: "请检查网络后重试",
                        fontSize = 12.sp,
                        color = Color(0xFFB0B0B4)
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        loadFailed = false
                        errorMessage = null
                        isLoading = true
                        reloadKey++
                    }) { Text("重新加载") }
                    if (isReservationH5 && currentUrl != LIBSPACE_RESERVATION_URL) {
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = {
                            currentUrl = LIBSPACE_RESERVATION_URL
                            loadFailed = false
                            errorMessage = null
                            isLoading = true
                            reloadKey++
                        }) { Text("打开备用空间预约") }
                    }
                    if (isReservationH5) {
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = {
                            openInWeChat(context, currentUrl) {
                                errorMessage = "未检测到微信，请先安装微信，或使用浏览器打开"
                            }
                        }) { Text("用微信打开") }
                    }
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = ::openBrowser) { Text("用浏览器打开") }
                }
            }
        }
    }
}

private class PageLoadTimers(
    private val handler: Handler,
    private val timeout: Runnable,
    private val blankCheck: Runnable,
    private val invalidate: () -> Unit
) {
    private var canceled = false

    fun cancel() {
        if (canceled) return
        canceled = true
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(blankCheck)
        invalidate()
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configureH5WebView(
    initialUrl: String,
    useWeChatUserAgent: Boolean,
    onUrlChanged: (String) -> Unit,
    onTitleChanged: (String?) -> Unit,
    onLoadingChanged: (Boolean) -> Unit,
    onLoadSucceeded: () -> Unit,
    onLoadFailed: (String) -> Unit,
    onOpenExternal: (String) -> Unit,
    onWebViewReady: () -> Unit
) {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowContentAccess = true
    settings.allowFileAccess = false
    settings.javaScriptCanOpenWindowsAutomatically = true
    settings.setSupportMultipleWindows(true)
    settings.mediaPlaybackRequiresUserGesture = false
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    settings.loadWithOverviewMode = true
    settings.useWideViewPort = true
    settings.setSupportZoom(true)
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    settings.userAgentString = if (useWeChatUserAgent && !isReaderCardBindingUrl(initialUrl)) WECHAT_MOBILE_USER_AGENT else MOBILE_USER_AGENT

    val cookieManager = CookieManager.getInstance()
    cookieManager.setAcceptCookie(true)
    cookieManager.setAcceptThirdPartyCookies(this, true)
    syncCookies(cookieManager, initialUrl)

    val handler = Handler(Looper.getMainLooper())
    var pageGeneration = 0L
    var activeUrl = initialUrl
    lateinit var timeout: Runnable
    lateinit var blankCheck: Runnable

    fun failPage(message: String) {
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(blankCheck)
        pageGeneration++
        onLoadFailed(message)
    }

    timeout = Runnable {
        failPage("页面加载超时，请检查网络后重试")
    }
    blankCheck = Runnable {
        val generation = pageGeneration
        val pageUrl = url.orEmpty()
        evaluateJavascript(
            "(function(){var b=document.body;return !!b && " +
                "(b.innerText.trim().length>0 || b.querySelector('img,iframe,canvas,svg')!==null || " +
                "b.innerHTML.length>256);})()"
        ) { result ->
            if (generation == pageGeneration && result == "false") {
                Log.w(TAG, "blank page url=$pageUrl")
                failPage("页面为空白，可能是脚本或网络未完成")
            }
        }
    }
    setTag(PageLoadTimers(handler, timeout, blankCheck) { pageGeneration++ })

    val hostWebView = this
    webChromeClient = object : WebChromeClient() {
        override fun onReceivedTitle(view: WebView?, title: String?) {
            onTitleChanged(title)
        }

        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message
        ): Boolean {
            // 超星大厅的读者证入口可能使用 target=_blank/window.open。
            // 不创建第二个不可见 WebView，直接把新窗口导航回当前页面。
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            transport.webView = hostWebView
            resultMsg.sendToTarget()
            return true
        }
    }
    webViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            pageGeneration++
            activeUrl = url ?: initialUrl
            handler.removeCallbacks(timeout)
            handler.removeCallbacks(blankCheck)
            handler.postDelayed(timeout, PAGE_TIMEOUT_MS)
            onUrlChanged(activeUrl)
            onLoadingChanged(true)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            val finishedUrl = url ?: initialUrl
            if (url != null && url != activeUrl) return
            handler.removeCallbacks(timeout)
            handler.removeCallbacks(blankCheck)
            onUrlChanged(finishedUrl)
            onLoadSucceeded()
            onLoadingChanged(false)
            cookieManager.flush()
            if (isOAuthFailureUrl(finishedUrl)) {
                failPage("微信授权未完成，请在微信中打开或稍后重试")
                return
            }
            // open.weixin.qq.com 对非微信客户端通常返回 HTTP 200 的提示页，
            // 不能只依赖 HTTP 错误码，否则用户会看到“加载成功”但无法绑定。
            if (isWeChatOAuthUrl(finishedUrl)) {
                val generation = pageGeneration
                evaluateJavascript(
                    "(function(){var t=(document.body&&document.body.innerText||'');" +
                        "return /请在微信客户端打开|请在微信中打开|微信客户端访问/.test(t);})()"
                ) { result ->
                    if (generation == pageGeneration && result == "true") {
                        failPage("微信授权需要在微信客户端完成，请点击“用微信打开”")
                    }
                }
            }
            handler.postDelayed(blankCheck, BLANK_CHECK_DELAY_MS)
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean =
            handleNavigation(request.url.toString(), onOpenExternal)

        @Suppress("DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String): Boolean =
            handleNavigation(url, onOpenExternal)

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            if (request?.isForMainFrame != false) {
                failPage("网络错误：${error?.description ?: "无法连接服务器"}")
            }
        }

        @Suppress("DEPRECATION")
        override fun onReceivedError(
            view: WebView?,
            errorCode: Int,
            description: String?,
            failingUrl: String?
        ) {
            failPage("网络错误：${description ?: "无法连接服务器"}")
        }

        override fun onReceivedHttpError(
            view: WebView?,
            request: WebResourceRequest?,
            errorResponse: android.webkit.WebResourceResponse?
        ) {
            if (request?.isForMainFrame == true && errorResponse != null && errorResponse.statusCode >= 400) {
                failPage("服务器返回 HTTP ${errorResponse.statusCode}，请稍后重试")
            }
        }

        override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: SslError?) {
            Log.e(TAG, "SSL error canceled url=${error?.url} primary=${error?.primaryError}")
            handler?.cancel()
            failPage("超星站点证书异常，请稍后重试或用微信打开")
        }
    }
    onWebViewReady()
}

private fun syncCookies(cookieManager: CookieManager, url: String) {
    try {
        val host = Uri.parse(url).host ?: return
        val cookieHeader = AppCookieJar.cookieHeaderFor(host).orEmpty()
        if (cookieHeader.isBlank()) return
        val base = "${Uri.parse(url).scheme ?: "http"}://$host"
        cookieHeader.split(';').forEach { pair ->
            val value = pair.trim()
            if (value.contains('=')) cookieManager.setCookie(base, value, null)
        }
        cookieManager.flush()
    } catch (_: Exception) {
        // Cookie 同步失败不应阻止网页自身建立会话。
    }
}

private fun handleNavigation(url: String, onOpenExternal: (String) -> Unit): Boolean {
    if (isInAppWebUrl(url)) return false
    onOpenExternal(url)
    return true
}

internal fun isInAppWebUrl(url: String): Boolean {
    return try {
        val scheme = URI(url).scheme?.lowercase()
        scheme == "http" || scheme == "https" || scheme == "about"
    } catch (_: Exception) {
        false
    }
}

internal fun isWeChatOAuthUrl(url: String): Boolean {
    return try {
        URI(url).host?.lowercase() == "open.weixin.qq.com" &&
            URI(url).path?.contains("/connect/oauth2/") == true
    } catch (_: Exception) {
        false
    }
}

internal fun isOAuthFailureUrl(url: String): Boolean {
    return try {
        val uri = URI(url)
        isWeChatOAuthUrl(url) &&
            uri.rawQuery?.split('&')?.any { it.substringBefore('=').equals("error", ignoreCase = true) } == true
    } catch (_: Exception) {
        false
    }
}

internal fun isReaderCardBindingUrl(url: String): Boolean {
    return try {
        val uri = URI(url)
        uri.host?.lowercase() == "mobilelib.wx.chaoxing.com" &&
            uri.path?.contains("/weixin/userBind/showBindAndCheck") == true
    } catch (_: Exception) {
        false
    }
}

private fun openInWeChat(
    context: android.content.Context,
    url: String,
    onFailure: () -> Unit = {}
) {
    try {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))
                .setPackage(WECHAT_PACKAGE)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
        Toast.makeText(context, "未安装微信，请使用浏览器打开", Toast.LENGTH_SHORT).show()
        onFailure()
    }
}

private fun openExternal(context: android.content.Context, url: String) {
    try {
        val uri = Uri.parse(url)
        val intent = if (uri.scheme.equals("intent", ignoreCase = true)) {
            android.content.Intent.parseUri(url, android.content.Intent.URI_INTENT_SCHEME)
        } else {
            android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
        }
        context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        Toast.makeText(context, "没有可打开此链接的应用，请先安装微信或使用浏览器", Toast.LENGTH_SHORT).show()
    }
}
