package com.xynu.campus.data.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

object HttpClientFactory {

    const val UA_PC = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    fun create(): OkHttpClient {
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