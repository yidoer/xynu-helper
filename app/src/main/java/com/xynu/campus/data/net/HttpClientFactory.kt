package com.xynu.campus.data.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

object HttpClientFactory {

    const val UA_PC = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * 全局共享的 OkHttpClient。
     *
     * OkHttp 官方建议整个应用复用同一个 client：每个实例都有自己的连接池和线程池，
     * 之前每个 ViewModel 都 new 一个，页面反复进出会不断新建连接池/线程，
     * 也无法复用已建立的 TCP/TLS 连接。所有调用方本来就共用 [AppCookieJar]，
     * 配置也完全一致，因此改为单例不改变任何请求行为。
     */
    private val shared: OkHttpClient by lazy { build() }

    fun create(): OkHttpClient = shared

    private fun build(): OkHttpClient {
        return OkHttpClient.Builder()
            .cookieJar(AppCookieJar)
            // 校园系统偶发不可达，避免页面长时间停在空白加载态。
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(SessionInterceptor())
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val orig = chain.request()
                val req = orig.newBuilder()
                    .header("User-Agent", UA_PC)
                    .header("Accept", "*/*")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .build()
                chain.proceed(req)
            }
            .build()
    }
}