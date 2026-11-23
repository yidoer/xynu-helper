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

// H5 容器的 WebView 配置、页面超时/白屏检测、Cookie 同步与导航判定（从 WebScreen.kt 拆出，代码未改）

private const val WECHAT_MOBILE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
        "Version/4.0 Chrome/120.0.0.0 Mobile Safari/537.36 " +
        "MicroMessenger/8.0.43(0x28002B36) NetType/WIFI Language/zh_CN"
private const val MOBILE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
private const val PAGE_TIMEOUT_MS = 20_000L
private const val BLANK_CHECK_DELAY_MS = 1_500L
private const val TAG = "WebScreen"

internal class PageLoadTimers(
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
internal fun WebView.configureH5WebView(
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
