package com.xynu.campus.data.net

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** 服务端会话所属的系统。 */
enum class SessionKind {
    JW,
    LIB
}

data class SessionRequirement(val kind: SessionKind)

/**
 * 表示服务端已将一个需要登录的请求重定向/返回为登录页。
 * 这是一个独立的异常类型，ViewModel 可以据此清理本地会话并导航到对应登录页，
 * 而不会把登录失效误显示成空数据。
 */
class SessionExpiredException(
    val kind: SessionKind,
    val host: String,
    message: String = "登录已过期，请重新登录"
) : RuntimeException(message)

fun Request.Builder.requireSession(kind: SessionKind): Request.Builder =
    tag(SessionRequirement::class.java, SessionRequirement(kind))

/**
 * 识别正方和阿法迪 OPAC 的登录页。
 * 只对 API 标记为 requireSession 的请求启用，公共检索/资讯页面不会被误判。
 */
object SessionDetector {

    fun isExpired(kind: SessionKind, finalUrl: String, body: String): Boolean {
        val url = finalUrl.lowercase()
        val html = body.lowercase()
        return when (kind) {
            SessionKind.JW ->
                url.contains("login_slogin") ||
                    html.contains("form") && html.contains("login_slogin")

            SessionKind.LIB ->
                url.contains("/opac/login") ||
                    (html.contains("checklogin.jsp") && html.contains("password"))
        }
    }
}

/**
 * 在 OkHttp 层统一检测会话重定向。
 * ResponseBody 需要读出后重新放回 Response，避免下游 API 读到空 body。
 */
class SessionInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val requirement = chain.request().tag(SessionRequirement::class.java)
            ?: return response
        val responseBody = response.body ?: return response
        val body = responseBody.string()

        if (SessionDetector.isExpired(
                requirement.kind,
                response.request.url.toString(),
                body
            )
        ) {
            val host = response.request.url.host
            response.close()
            throw SessionExpiredException(requirement.kind, host)
        }

        return response.newBuilder()
            .body(body.toResponseBody(responseBody.contentType()))
            .build()
    }
}
