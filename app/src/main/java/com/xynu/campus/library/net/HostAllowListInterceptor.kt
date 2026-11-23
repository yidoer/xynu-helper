package com.xynu.campus.library.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 图书馆助手模块的独立网络白名单。
 *
 * 红线要求：白名单**只在本模块的 OkHttp client 上生效**，不改全局
 * `network_security_config`（现有功能的 host 配置保持原样）。
 *
 * 白名单四个 host：
 * - `mobilelib.wx.chaoxing.com`、`mobilelibwx.chaoxing.com`：http 允许
 * - `wechat.v2.traceint.com`：https
 * - `libopac.xynu.edu.cn`：http 允许
 *
 * 关于 `libopac`（2026-11 追加，用户明确「功能性至上」后放开）：
 * 实测借阅正文与馆藏检索都在 `libopac.xynu.edu.cn:8080`（阿法迪系统），
 * 而 `mobilelib` **无法代理** `/sms/opac/` 下的页面（实测 404）。若不放开这个 host，
 * 借阅明细只能丢给系统浏览器、馆藏检索在内网才可用。
 * 该域名本来就已存在于基线提交的 `network_security_config.xml` 明文名单里，
 * 故此改动不涉及全局配置。
 *
 * `web.traceint.com` **已移除**：它当初被加进来，只是当 `Origin`/`Referer`
 * 的取值来源。但那个域在上游全部 25 份 fixture 里出现 **0 次**，是臆造的，
 * 两个头删掉之后它就没有任何用途了 —— 留着只会扩大攻击面。
 * 见 docs/probe_findings.md §G-11（已作废）与 §K。
 */
object LibraryHostAllowList {

    /** http 允许的 host（超星移动图书馆 + 本校阿法迪 OPAC）。 */
    val HTTP_HOSTS: Set<String> = setOf(
        "mobilelib.wx.chaoxing.com",
        "mobilelibwx.chaoxing.com",
        "libopac.xynu.edu.cn",
    )

    /** https 允许的 host（超星 https 等价 host + traceint + 本校 OPAC）。 */
    val HTTPS_HOSTS: Set<String> = setOf(
        "mobilelib.wx.chaoxing.com",
        "mobilelibwx.chaoxing.com",
        "wechat.v2.traceint.com",
        "libopac.xynu.edu.cn",
    )

    /** 全部允许的 host，用于单测与提示文案。 */
    val ALL_HOSTS: Set<String> = HTTP_HOSTS + HTTPS_HOSTS

    fun isAllowed(url: HttpUrl): Boolean = when (url.scheme) {
        "http" -> url.host in HTTP_HOSTS
        "https" -> url.host in HTTPS_HOSTS
        else -> false
    }

    /**
     * 便捷重载：给文案/日志/测试用。
     *
     * 解析失败（不是合法 URL）一律视为不允许——安全性上必须 fail-closed。
     */
    fun isAllowed(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return isAllowed(parsed)
    }
}

/** 白名单外请求直接抛出，绝不落到网络层。 */
class HostNotAllowedException(val host: String, val scheme: String) :
    java.io.IOException("host 不在图书馆功能白名单内：$scheme://$host")

/**
 * 模块级白名单拦截器。
 *
 * 面试点：为什么用拦截器而不是 `network_security_config`？
 * 因为 `network_security_config` 是全局的，改它会影响现有教务/OPAC 功能；
 * 而白名单是模块级安全策略，放在模块自己的 client 上，作用域精确。
 *
 * ## 它为什么必须同时注册成 application 拦截器**和** network 拦截器
 *
 * 这是本模块最容易想错的一处，曾经写错过（文档和测试注释都断言过错误结论）。
 *
 * OkHttp 的拦截器链顺序是：
 * ```
 * [application interceptors..., RetryAndFollowUpInterceptor, BridgeInterceptor,
 *  CacheInterceptor, ConnectInterceptor, [network interceptors...], CallServerInterceptor]
 * ```
 * 重定向**不是**新的 `RealCall`：`RetryAndFollowUpInterceptor` 在**同一个** chain
 * 对象上再次调用 `proceed(newRequest)`，而 `RealInterceptorChain.proceed()` 只做
 * `copy(index + 1)` 往后走，**index 不会退回 0**。
 *
 * 结论：
 * - 只注册 application 拦截器 ⇒ **第二跳起白名单完全失效**，
 *   上游一个 302 就能把整套请求（含手写 `Cookie` 里的 JWT）带到任意 host。
 *   OkHttp 的 `buildRedirectRequest` 会复制**全部**原请求头，只在
 *   `!canReuseConnectionFor(old, new)` 时删 `Authorization` —— **不删 `Cookie`**。
 * - 注册 network 拦截器（`ConnectInterceptor` 之后）⇒ 每一跳都会重新执行，
 *   因为重定向循环每次 `proceed` 都会重新经过 network 拦截器段。
 *
 * 所以两个都保留：application 那份负责"发出前就 fail-fast"（连 DNS 都不做），
 * network 那份负责"每一跳都卡住"。少任何一个都不完整。
 *
 * ## 为什么判定逻辑是可注入的
 *
 * [allowed] 默认就是生产白名单 [LibraryHostAllowList.isAllowed]，行为不变。
 * 它存在是为了让**测试能用真实 client 栈**（`LibraryHttpClient.create()` 派生的
 * 那一条），只把白名单换成"也认识 MockWebServer 临时端口"。
 *
 * 这个参数是必要的：原来的测试靠 `.newBuilder().addInterceptor { 改 url }` 把
 * 请求重写到 MockWebServer，但改写发生在 chain 里，而 network 拦截器看到的是
 * **改写后**的 URL —— 于是 mock 的 `127.0.0.1:随机端口` 被拒，
 * `MockWebServer.takeRequest()` 永远等不到请求，整个测试进程挂死（实测 26 分钟）。
 *
 * 换句话说：这个洞是我加 network 拦截器时**引入**的，不是既有缺陷；
 * 而它的正确解法不是"让测试绕过白名单"，是"让测试替换白名单"——
 * 绕过会连真实链路一起跳过，替换则保住了除白名单外的全部行为。
 */
class HostAllowListInterceptor(
    private val allowed: (HttpUrl) -> Boolean = LibraryHostAllowList::isAllowed,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (!allowed(url)) {
            throw HostNotAllowedException(url.host, url.scheme)
        }
        return chain.proceed(request)
    }
}
