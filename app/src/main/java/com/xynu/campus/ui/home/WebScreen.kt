package com.xynu.campus.ui.home

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.xynu.campus.data.net.AppCookieJar
import com.xynu.campus.ui.common.AppTopBar
import java.net.URLDecoder

/**
 * 通用应用内 WebView 页。
 * 用于承载新闻详情、校园卡平台、校园资讯、校区地图等所有外链内容，
 * 统一应用内阅读体验；右上角提供"浏览器打开"选项。
 *
 * @param encodedUrl URL（经 Navigation 传参时已编码，此处解码）
 * @param title 页面标题
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebScreen(
    encodedUrl: String,
    title: String?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val url = remember(encodedUrl) {
        // 兼容 Uri.encode 和 URLEncoder.encode 两种来源
        try { android.net.Uri.decode(encodedUrl) } catch (_: Exception) {
            try { URLDecoder.decode(encodedUrl, "UTF-8") } catch (_: Exception) { encodedUrl }
        }
    }
    // 诊断：打印最终 URL 到 logcat
    LaunchedEffect(url) {
        android.util.Log.i("WebScreen", "decoded url = [$url], encodedUrl = [$encodedUrl]")
    }
    // 按域名区分浏览模式：教务处官网/教务系统用电脑版，其他用手机版
    val host = remember(url) { android.net.Uri.parse(url).host ?: "" }
    val isDesktop = remember(host) {
        host.contains("jwc.xynu.edu.cn") || host.contains("news.xynu.edu.cn") ||
            host.contains("jwgl.xynu.edu.cn")
    }
    val desktopUA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    val mobileUA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    var isLoading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }

    Scaffold(
        containerColor = Color(0xFFF5F6FA),
        topBar = {
            AppTopBar(
                title = title ?: "浏览",
                onBack = onBack,
                action = {
                    IconButton(
                        onClick = {
                            try {
                                val intent = android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(url)
                                )
                                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            } catch (_: Exception) { }
                        },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(Icons.Filled.OpenInBrowser, "浏览器打开", tint = Color(0xFF3A3A3C), modifier = Modifier.size(20.dp))
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            // reloadKey 变化时重建 WebView 实现重载
            key(reloadKey) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            @SuppressLint("SetJavaScriptEnabled")
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            // 电脑版站点：桌面 UA + 自适应；手机版站点：移动 UA
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            settings.setSupportZoom(true)
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            settings.userAgentString = if (isDesktop) desktopUA else mobileUA

                            // 同步 App 内已登录会话 Cookie，教务/图书馆网页版免登录
                            try {
                                val h = android.net.Uri.parse(url).host
                                if (h != null) {
                                    val jar = CookieManager.getInstance()
                                    jar.setAcceptCookie(true)
                                    jar.setAcceptThirdPartyCookies(this, true)
                                    val cookieHeader = AppCookieJar.cookieHeaderFor(h)
                                    if (!cookieHeader.isNullOrBlank()) {
                                        // 带 path=/ 逐条同步，确保 domain/path 正确
                                        val scheme = android.net.Uri.parse(url).scheme ?: "http"
                                        val base = "$scheme://$h"
                                        cookieHeader.split(";").forEach { pair ->
                                            val kv = pair.trim()
                                            if (kv.contains("=")) {
                                                jar.setCookie("$base/jwglxt/", kv, null)
                                                jar.setCookie(base, kv, null)
                                            }
                                        }
                                        jar.flush()
                                    }
                                }
                            } catch (_: Exception) { }

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, f: String?, favicon: Bitmap?) {
                                    isLoading = true
                                    loadFailed = false
                                }

                                override fun onPageFinished(view: WebView?, f: String?) {
                                    isLoading = false
                                    // 桌面版 UA 才需要强制移动视口；手机版/SPA 用自带 viewport，
                                    // 避免覆盖单页应用根节点样式导致空白
                                    if (isDesktop) {
                                        view?.evaluateJavascript(
                                            "var m=document.querySelector('meta[name=viewport]');" +
                                                "if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}" +
                                                "m.content='width=device-width, initial-scale=1.0, maximum-scale=1.0';", null
                                        )
                                    }
                                }

                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    // 站内链接继续在应用内打开
                                    view?.loadUrl(request?.url?.toString() ?: url)
                                    return true
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    errorCode: Int,
                                    description: String,
                                    failingUrl: String?
                                ) {
                                    loadFailed = true
                                    isLoading = false
                                }
                            }
                            loadUrl(url)
                        }
                    },
                    onRelease = { it.destroy() },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 加载指示：细进度条 + 中心提示
            if (isLoading) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(30.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.height(10.dp))
                    Text("加载中…", fontSize = 13.sp, color = Color(0xFF8E8E93))
                }
            }

            // 加载失败
            if (loadFailed) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFFF5F6FA)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("😕", fontSize = 36.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("页面加载失败", color = Color(0xFF8E8E93))
                        Spacer(Modifier.height(4.dp))
                        Text("可能是服务器限制或页面已失效", fontSize = 12.sp, color = Color(0xFFB0B0B4))
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = {
                            reloadKey++
                            isLoading = true
                            loadFailed = false
                        }) {
                            Text("重新加载")
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = {
                            try {
                                val intent = android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(url)
                                )
                                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            } catch (_: Exception) { }
                        }) {
                            Text("用浏览器打开")
                        }
                    }
                }
            }
        }
    }
}