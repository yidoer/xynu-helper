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

private const val WECHAT_PACKAGE = "com.tencent.mm"

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
