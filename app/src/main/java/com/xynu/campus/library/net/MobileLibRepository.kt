package com.xynu.campus.library.net

import com.xynu.campus.library.model.BindState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * 允许作为借阅落地页的 host（与 [HostAllowListInterceptor] 的 HTTPS 白名单一致）。
 *
 * 放在文件级而不是实例字段：嵌套的 [MobileLibRepository.RedirectTarget]
 * 无法引用外层实例成员，而 `isBorrowLanding` 需要它。
 */
private val BORROW_LANDING_HOSTS = setOf(
    "libopac.xynu.edu.cn",
    "mobilelib.wx.chaoxing.com",
    "mobilelibwx.chaoxing.com",
)

/**
 * 超星移动图书馆上游（借阅模块）。
 *
 * 只做三件事：
 * 1. 拉 hall 页 → 解析入口表 + from_code
 * 2. 按 itemId + openid 请求 openItemNeedBind，判定 landing 状态（缺openid/未绑/已绑）
 * 3. 取落地页原文供 WebView 展示
 *
 * **不做**：绑定表单提交、换绑、解绑（任务书 §1 明确砍掉）。
 */
class MobileLibRepository(
    private val client: OkHttpClient = LibraryHttpClient.create(),
) {

    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>

        /** 上游不可达 / 超时 / 非 2xx。 */
        data class NetworkError(val message: String) : Result<Nothing>
    }

    /**
     * 一次身份探测的结果。
     *
     * @param state     三态判定结果
     * @param html      mobilelib 直接返回的正文（302 时为空串，不是错误）
     * @param landingUrl 302 指向的业务页地址；带**服务端刚签发的全新 sn**，
     *                   交给系统浏览器打开即可看到借阅明细
     */
    data class Probe(
        val state: BindState,
        val html: String,
        val landingUrl: String?,
    )

    /**
     * 请求 `openItemNeedBind` 并判定身份状态。
     *
     * 注意：**不跟随重定向**，因为我们需要的正是 302 语义：
     * - 302 → open.weixin.qq.com ⇒ 缺 openid
     * - 302 → showUserBind.action ⇒ openid 已带但未绑读者证
     * - 302 → /sms/opac/... ⇒ 已绑定（附一条新 sn 的业务页地址）
     * - 200 + 门禁页 ⇒ 缺 openid
     * - 200 + 业务页 ⇒ 已绑定
     *
     * 判定为已绑定后，会**再发一次请求**把业务页正文取回来（[fetchLandingHtml]），
     * 这样借阅明细可以直接在应用内渲染，不必跳到外部浏览器。
     */
    suspend fun probeBindState(itemId: Int, openid: String?): Result<Probe> =
        withContext(Dispatchers.IO) {
            val url = LibraryHttpClient.itemUrl(itemId, openid)
            val req = Request.Builder().url(url).get().build()
            try {
                val probe = client.newBuilder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build()
                    .newCall(req).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        val location = resp.header("Location")
                        val state = when {
                            resp.isRedirect -> classifyRedirect(location, body)
                            else -> LandingPageClassifier.classify(body)
                        }
                        Probe(
                            state = state,
                            html = body,
                            landingUrl = borrowLandingUrl(location),
                        )
                    }

                // 已绑定但正文为空（302 场景）：主动取一次业务页正文。
                // sn 是服务端刚签发的，此刻取一定有效。
                if (probe.state == BindState.BOUND && probe.html.isBlank()) {
                    val html = probe.landingUrl?.let { fetchLandingHtml(it) }
                    if (!html.isNullOrBlank()) {
                        return@withContext Result.Ok(probe.copy(html = html))
                    }
                }
                Result.Ok(probe)
            } catch (e: IOException) {
                Result.NetworkError("上游不可达：${e.message}")
            } catch (e: HostNotAllowedException) {
                Result.NetworkError("host 不在白名单：${e.host}")
            }
        }

    /**
     * 取借阅业务页正文（`libopac.xynu.edu.cn:8080/sms/opac/...`）。
     *
     * 失败不抛异常：正文只是「渲染优化」，拿不到就退回按摘要/提示展示，
     * 不能因为这一步失败就让用户以为身份无效。
     */
    suspend fun fetchLandingHtml(landingUrl: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(landingUrl).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    /**
     * 302 语义（按真实抓包结果实测，见 docs/probe_findings.md §I）：
     *
     * | Location 特征 | 含义 | 判定 |
     * |---|---|---|
     * | `open.weixin.qq.com` | 没带 openid，被踢去 OAuth | 缺 openid |
     * | `showUserBind.action` | openid 有效但读者证没绑 | 未绑读者证 |
     * | `/sms/opac/...` | **换到新 sn 的借阅业务页＝已绑定** | 已绑定 |
     *
     * 第三条是 2026-11 真机复现「图书馆助手没办法识别链接」的根因：
     * 该学校的借阅数据实际由阿法迪（libopac.xynu.edu.cn:8080/sms/opac）
     * 出，mobilelib 只负责把 openid 换成一个全新的 sn 再 302 过去。
     * 旧实现只认前两种，成功的 302 落到空 body 上被判成 UNKNOWN。
     */
    internal fun classifyRedirect(location: String?, body: String): BindState {
        // 不能对 Location 做 `contains` 判定。
        //
        // 原来的写法 `location.contains("/sms/opac/")` 之类，在
        // `https://evil.example/?next=/sms/opac/` 上也会命中 ——
        // 攻击者（或上游被插一个跳转）就能把任意 URL 判成「已绑定」，
        // 而 [borrowLandingUrl] 还会把这条 Location 原样交给
        // BorrowScreen 的「在浏览器打开」，等于把 ACTION_VIEW
        // 的入参交给了响应头。
        //
        // 正确判据是**结构化**的：先解析出 host + path，再比 host 是不是
        // 我们认识的那几台、path 是不是那几个端点。
        val parsed = location?.let { parseRedirect(it) }
            ?: return LandingPageClassifier.classify(body)
        return when {
            parsed.host == "open.weixin.qq.com" -> BindState.MISSING_OPENID
            parsed.path.endsWith("/showUserBind.action") -> BindState.UNBOUND_CARD
            // 借阅业务页：说明 openid 通过了服务端校验，这是成功路径
            parsed.isBorrowLanding -> BindState.BOUND
            else -> LandingPageClassifier.classify(body)
        }
    }

    /** 解析过的重定向目标。 */
    internal data class RedirectTarget(
        val host: String,
        val path: String,
    ) {
        /** 是否是我们认识的借阅业务页（host 必须白名单内，path 必须匹配端点）。 */
        val isBorrowLanding: Boolean
            get() = host in BORROW_LANDING_HOSTS && (
                path.contains("/sms/opac/") ||
                    path.endsWith("/lendStatus.action")
                    )
    }    /**
     * 解析 Location，只保留 scheme/host/path。
     *
     * 解析失败（相对地址、畸形 URL）一律返回 null，由调用方退回 body 分类。
     * 注意：**相对 Location 不做拼接推断**，因为基地址是上游给的，
     * 拼出来再判断等于又引入一次不可信输入。
     */
    internal fun parseRedirect(location: String): RedirectTarget? = runCatching {
        val u = java.net.URI(location.trim())
        val host = u.host?.lowercase() ?: return@runCatching null
        val scheme = u.scheme?.lowercase() ?: return@runCatching null
        if (scheme != "http" && scheme != "https") return@runCatching null
        RedirectTarget(host = host, path = u.path.orEmpty())
    }.getOrNull()

    /**
     * 从 302 的 Location 里抠出带**全新 sn** 的业务页地址。
     *
     * 为什么必须用服务端刚发的这一条：
     * 用户从微信「复制链接」得到的那条 `sn` 有 TTL，过期后页面标题变成
     * 「登录失效」；而 mobilelib 每次请求都会重新签发 sn（实测同一 sn
     * 可重复使用，但签发新的一定有效）。
     *
     * 返回 null 的条件比以前严：只有**解析成功且 host 在白名单内**的
     * 借阅业务页才放行。这条返回值最终会进 `ACTION_VIEW` 打开外部浏览器，
     * 不能让一个畸形响应头决定打开哪个地址。
     */
    internal fun borrowLandingUrl(location: String?): String? {
        val loc = location?.trim().orEmpty()
        if (loc.isEmpty()) return null
        val target = parseRedirect(loc) ?: return null
        return if (target.isBorrowLanding) loc else null
    }

    /** 允许作为借阅落地页的 host（与 HTTP 白名单保持一致）。 */
    /** 拉 hall 页原文（用于解析入口表与 from_code）。 */
    suspend fun fetchHallPage(): Result<String> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${LibraryHttpClient.BASE_MOBILELIB}/weixin/hall/showNew" +
                "?pid=${LibraryHttpClient.PID}&type=8")
            .get()
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use Result.NetworkError("hall 页返回 ${resp.code}")
                Result.Ok(resp.body?.string().orEmpty())
            }
        } catch (e: IOException) {
            Result.NetworkError("hall 页不可达：${e.message}")
        }
    }
}
