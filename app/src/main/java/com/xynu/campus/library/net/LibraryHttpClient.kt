package com.xynu.campus.library.net

import android.content.Context
import com.xynu.campus.data.net.AppCookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 图书馆助手模块专属 OkHttp client。
 *
 * 三条硬约束：
 * 1. 白名单拦截器必须**第一个**加，白名单外请求在发出前就抛异常；
 * 2. UA 统一注入（对齐生产微信 UA，注释里说明它不是服务端硬约束）；
 * 3. 绝不复用 `HttpClientFactory.create()`——那是全局 client，会带上教务 Cookie
 *    与全局 UA，污染模块边界。
 */
object LibraryHttpClient {

    /**
     * 生产微信 UA。实测超星侧 UA 非硬约束（普通 Chrome UA 全通），
     * 但 traceint 门禁页与 OAuth 链路会校验，故统一注入以对齐真实环境。
     */
    const val WECHAT_UA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Mobile/15E148 MicroMessenger/8.0.42(0x18002a23) NetType/WIFI"

    const val BASE_MOBILELIB = "http://mobilelib.wx.chaoxing.com"
    const val BASE_TRACEINT = "https://wechat.v2.traceint.com"

    /**
     * traceint H5 的 `App-Version`。
     *
     * 取值来自上游 bundle（`ti_version` 模块）：`{"version":"2.2.9"}`，
     * 并且 `uni.request` 的公共方法会把它作为唯一的显式请求头发出。
     * 之前这里写的是 `2.0.11`——那是凭感觉填的旧值，上游那边没有这个版本号。
     * 版本号对不上时，服务端可能按老版本协议返回或者直接拒绝，
     * 属于「看着能用但偶发失败」的典型来源。
     */
    const val TRACEINT_APP_VERSION = "2.2.9"

    /**
     * 本校阿法迪 OPAC（`222.21.62.155:8080`）。
     *
     * 借阅业务页与馆藏检索**都在这台机器上**，实测**内外网均可达**
     * （外网 HTTP 200，约 100~200ms）。这与老的
     * `222.21.62.201:8011`（内网 only）是两台不同的机器，别混。
     */
    const val BASE_LIBOPAC = "http://libopac.xynu.edu.cn:8080"

    const val PID = "1512"
    const val SCHOOL_ID = "968"
    const val AREA_ID = "274"

    /** 借阅查询入口 itemId（实测 bind=1）。 */
    const val ITEM_BORROW_INFO = 20302
    const val ITEM_BIND_CARD = 20301
    const val ITEM_SEAT_RESERVE = 28277

    /**
     * 模块内所有 client 的共同根：不带任何拦截器/cookieJar，只用来共享
     * 连接池与线程池（`newBuilder()` 派生出的 client 会复用这两样）。
     * 拦截器链、cookieJar、重定向策略仍由 [baseBuilder] 及各工厂方法各自配置，
     * 白名单拦截器依旧是第一个拦截器，模块边界不变。
     */
    private val root: OkHttpClient by lazy { OkHttpClient() }

    /** 带 cookieJar 的默认 client 单例，避免每个仓库/WebView 各建一套连接池。 */
    private val defaultClient: OkHttpClient by lazy { baseBuilder().cookieJar(AppCookieJar).build() }

    private fun baseBuilder(
        allowed: (HttpUrl) -> Boolean = LibraryHostAllowList::isAllowed,
    ): OkHttpClient.Builder =
        root.newBuilder()
            // ① 白名单最先，任何后续拦截器都不会看到非法 host。
            //    这一份是 application 级：负责在**发出前**就 fail-fast（连 DNS 都不做），
            //    但它**管不到重定向的第二跳** —— 详见 ②。
            .addInterceptor(HostAllowListInterceptor(allowed))
            // ② 白名单再来一份，network 级。
            //
            //    这是必须的，不是重复。OkHttp 的重定向在 RetryAndFollowUpInterceptor
            //    内部循环，它拿着**同一个** chain 再次 proceed，而
            //    RealInterceptorChain.proceed() 只 copy(index+1) 往后走，index 不回退到 0
            //    ⇒ application 拦截器在第二跳起根本不会执行。
            //    而 network 拦截器位于 ConnectInterceptor 之后，每一跳都会重新经过。
            //
            //    为什么必须堵这个洞：buildRedirectRequest 会复制**全部**原请求头，
            //    只在 !canReuseConnectionFor(old,new) 时删掉 Authorization ——
            //    **不删 Cookie**。而选座会话正是靠手写 Cookie 头传的
            //    （SeatGraphQlClient 的 wechatSESS_ID + JWT + FROM_CODE）。
            //    上游一个 302 就能把整套凭据送到任意 host。
            .addNetworkInterceptor(HostAllowListInterceptor(allowed))
            // ③ UA 与通用头
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", WECHAT_UA)
                    .header("Accept", "*/*")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .build()
                chain.proceed(req)
            }
            // ④ traceint 的 GraphQL 需要版本头，超星侧无害
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("App-Version", TRACEINT_APP_VERSION)
                    .build()
                chain.proceed(req)
            }
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

    fun create(@Suppress("UNUSED_PARAMETER") ctx: Context? = null): OkHttpClient =
        defaultClient

    /**
     * 测试专用：派生一个 client，保留**全部**生产行为（UA、App-Version、
     * 两份白名单、重定向策略、超时、cookieJar），只把白名单判定换成 [allowed]。
     *
     * 为什么不给测试直接 `newBuilder().addInterceptor { 改 url }`：
     * 那样改写发生在 chain 内部，而 network 白名单看的是**改写后**的 URL，
     * mock 的 `127.0.0.1:随机端口` 会被拒，请求根本发不出去 ——
     * 测试要么挂死（`takeRequest()` 无超时），要么测到的不是真实链路。
     *
     * 这个入口让测试替换的是"白名单认不认这个 host"，而不是"要不要白名单"。
     * 后者（直接摘掉拦截器）会让测试失去对真实链路的覆盖。
     */
    fun createForTest(allowed: (HttpUrl) -> Boolean): OkHttpClient =
        baseBuilder(allowed).cookieJar(AppCookieJar).build()

    /** 测试专用：无 cookieJar 版本，语义同 [createForSeatGraphQl]。 */
    fun createForSeatGraphQlForTest(allowed: (HttpUrl) -> Boolean): OkHttpClient =
        baseBuilder(allowed).build()

    /**
     * 选座 GraphQL 专用 client：**故意不挂 cookieJar**。
     *
     * OkHttp 的 BridgeInterceptor 在发出请求前会做这件事：
     *
     * ```
     * val cookies = cookieJar.loadForRequest(url)
     * if (cookies.isNotEmpty()) userRequestBuilder.header("Cookie", cookieHeader(cookies))
     * ```
     *
     * 也就是说 jar 里**只要有任何一条同域 cookie**，就会把我们手写的
     * `Cookie` 头整段**替换**掉（不是合并）。选座会话是靠 `Cookie` 头显式传的，
     * 一旦 jar 里残留了 traceint 的旧 cookie（网页版登录过、上一版会话的
     * `Set-Cookie`、或者别的模块串进来的），用户粘贴的会话就会被静默顶掉。
     *
     * 症状正是「Cookie 明明填了、也验证通过了，却一直读不到数据」——
     * 因为第一次验活之后 jar 里就存下了响应带来的 cookie，后续查询全被替换。
     *
     * 所以这里不给 jar：选座会话的唯一来源就是 [com.xynu.campus.library.model.SeatSession]。
     */
    fun createForSeatGraphQl(): OkHttpClient = baseBuilder().build()

    /** 不跟随重定向的 client：用于精确识别 302 语义（缺 openid / 落地地址）。 */
    fun createNoRedirect(): OkHttpClient =
        create().newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

    fun itemUrl(itemId: Int, openid: String?): String = buildString {
        append(BASE_MOBILELIB)
        append("/weixin/userBind/openItemNeedBind.action")
        append("?wxProductId=").append(PID)
        append("&itemId=").append(itemId)
        if (openid != null) append("&openid=").append(openid)
    }
}
