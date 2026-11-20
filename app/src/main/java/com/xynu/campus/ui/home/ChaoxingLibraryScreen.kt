package com.xynu.campus.ui.home

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.xynu.campus.ui.common.AppTopBar

private const val RequestedChaoxingUrl =
    "https://mobilelib.wx.chaoxing.com/weixin/hall/showNew?pid=1512&type=8"

/**
 * 超星移动图书馆入口。
 *
 * 超星的登录、检索、借阅和续借都在站点内完成，不把账号密码交给 App。
 * 仅允许 chaoxing.com 相关页面继续留在 WebView，普通外链交给系统浏览器。
 */
@Composable
fun ChaoxingLibraryScreen(
    onBack: () -> Unit,
    onFallback: (() -> Unit)? = null,
    initialTitle: String = "超星图书馆"
) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf(RequestedChaoxingUrl) }
    var pageTitle by remember { mutableStateOf(initialTitle) }
    var isLoading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }

    val goBack: () -> Unit = {
        val view = webView
        if (view?.canGoBack() == true) view.goBack() else onBack()
    }
    // 即使 WebView 还没有历史记录，系统返回也应回到应用上一页。
    BackHandler(enabled = true, onBack = goBack)

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
                        onClick = { openExternal(context, currentUrl) },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(Icons.Filled.OpenInBrowser, "浏览器打开", tint = Color(0xFF3A3A3C), modifier = Modifier.size(20.dp))
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        configureChaoxingWebView(
                            onUrlChanged = { currentUrl = it },
                            onTitleChanged = { title ->
                                if (!title.isNullOrBlank()) pageTitle = title
                            },
                            onLoadingChanged = { loading ->
                                isLoading = loading
                                if (loading) loadFailed = false
                            },
                            onLoadFailed = {
                                loadFailed = true
                                isLoading = false
                            },
                            onOpenExternal = { openExternal(ctx, it) },
                            onDownload = { url, userAgent, contentDisposition, mimeType ->
                                enqueueDownload(ctx, url, userAgent, contentDisposition, mimeType)
                            }
                        )
                        webView = this
                        loadUrl(RequestedChaoxingUrl)
                    }
                },
                onRelease = {
                    webView = null
                    it.stopLoading()
                    it.destroy()
                },
                modifier = Modifier.fillMaxSize()
            )

            if (isLoading && !loadFailed) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(30.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.height(10.dp))
                    Text("正在打开超星图书馆…", fontSize = 13.sp, color = Color(0xFF8E8E93))
                }
            }

            if (loadFailed) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("页面加载失败", color = Color(0xFF8E8E93))
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = {
                        loadFailed = false
                        isLoading = true
                        webView?.reload()
                    }) {
                        Text("重新加载")
                    }
                    onFallback?.let { fallback ->
                        Spacer(Modifier.height(4.dp))
                        androidx.compose.material3.TextButton(onClick = fallback) {
                            Text("使用原生空教室查询")
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    androidx.compose.material3.TextButton(onClick = { openExternal(context, currentUrl) }) {
                        Text("用浏览器打开")
                    }
                }
            }
        }
    }
}

private fun WebView.configureChaoxingWebView(
    onUrlChanged: (String) -> Unit,
    onTitleChanged: (String?) -> Unit,
    onLoadingChanged: (Boolean) -> Unit,
    onLoadFailed: () -> Unit,
    onOpenExternal: (String) -> Unit,
    onDownload: (String, String?, String?, String?) -> Unit
) {
    @Suppress("SetJavaScriptEnabled")
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowContentAccess = true
    settings.allowFileAccess = false
    settings.javaScriptCanOpenWindowsAutomatically = true
    settings.setSupportMultipleWindows(false)
    settings.mediaPlaybackRequiresUserGesture = false
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    settings.loadWithOverviewMode = true
    settings.useWideViewPort = true
    settings.setSupportZoom(true)
    settings.builtInZoomControls = true
    settings.displayZoomControls = false

    val cookieManager = CookieManager.getInstance()
    cookieManager.setAcceptCookie(true)
    cookieManager.setAcceptThirdPartyCookies(this, true)

    webChromeClient = object : WebChromeClient() {
        override fun onReceivedTitle(view: WebView?, title: String?) {
            onTitleChanged(title)
        }
    }
    webViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            onUrlChanged(url ?: RequestedChaoxingUrl)
            onLoadingChanged(true)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            onUrlChanged(url ?: RequestedChaoxingUrl)
            onLoadingChanged(false)
            cookieManager.flush()
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
            return handleNavigation(request.url.toString(), onOpenExternal)
        }

        @Suppress("DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String): Boolean {
            return handleNavigation(url, onOpenExternal)
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            if (request?.isForMainFrame != false) onLoadFailed()
        }
    }
    setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
        onDownload(url, userAgent, contentDisposition, mimeType)
    })
}

private fun handleNavigation(url: String, onOpenExternal: (String) -> Unit): Boolean {
    val uri = Uri.parse(url)
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") {
        onOpenExternal(url)
        return true
    }
    val host = uri.host?.lowercase().orEmpty()
    return if (host == "chaoxing.com" || host.endsWith(".chaoxing.com")) {
        false
    } else {
        onOpenExternal(url)
        true
    }
}

private fun enqueueDownload(
    context: Context,
    url: String,
    userAgent: String?,
    contentDisposition: String?,
    mimeType: String?
) {
    val uri = Uri.parse(url)
    if (uri.scheme != "http" && uri.scheme != "https") {
        openExternal(context, url)
        return
    }

    try {
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val request = DownloadManager.Request(uri).apply {
            setMimeType(mimeType)
            setTitle(fileName)
            setDescription("超星图书馆下载")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            addRequestHeader("User-Agent", userAgent ?: WebSettings.getDefaultUserAgent(context))
            CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
        }
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.enqueue(request)
        Toast.makeText(context, "已开始下载，可在通知或下载目录查看", Toast.LENGTH_SHORT).show()
    } catch (_: Exception) {
        Toast.makeText(context, "下载失败，请尝试用浏览器打开", Toast.LENGTH_SHORT).show()
        openExternal(context, url)
    }
}

private fun openExternal(context: Context, url: String) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
        Toast.makeText(context, "没有可打开此链接的应用", Toast.LENGTH_SHORT).show()
    }
}
