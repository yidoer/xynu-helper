package com.xynu.campus.library.graphql

import com.xynu.campus.library.model.CurrentReservation
import com.xynu.campus.library.model.SeatLayout
import com.xynu.campus.library.model.SeatLibrary
import com.xynu.campus.library.model.SeatRule
import com.xynu.campus.library.model.SeatSession
import com.xynu.campus.library.net.LibraryHttpClient
import com.xynu.campus.library.net.HostNotAllowedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * traceint「我去图书馆」GraphQL 适配器。
 *
 * **请求约定（每一条都是真机实测出来的，别凭感觉改）**：
 *
 * - **请求体必须是 form-urlencoded**。上游是 PHP，按字段名从 `$_POST` 取
 *   `query`，不解析 JSON body。详见 [buildFormBody]。
 * - `App-Version` 用 [LibraryHttpClient.TRACEINT_APP_VERSION]（真值 `2.2.9`，
 *   来自上游 bundle 模块 `2afd`）。**不要硬编码**。
 * - `Cookie` 由 [SeatSession.toCookieHeader] 生成，只发真实存在的字段。
 * - `User-Agent` 含 `MicroMessenger`，由 [LibraryHttpClient] 统一注入。
 *
 * **不要再加回来的东西**（都验证过是臆造，加了反而可能被判伪造请求）：
 * - `Origin` / `Referer: https://web.traceint.com/...` —— 该域在上游全部
 *   25 份 fixture 里出现 **0 次**。
 * - Cookie 里的 `v=5.5` —— `5.5` 同样出现 0 次；`v` 是可选的，没有就不发。
 *
 * 错误语义：
 * - `errors[0].code == 40001` ⇒ 会话失效，**自动重试最多 1 次**（任务书要求），
 *   仍失败则提示重新贴 Cookie。
 * - 其它任何非空 `errors` ⇒ 直接把上游原文透出来，**不要**当成成功 ——
 *   否则列表会静默变空，看着像「Cookie 失效」。
 */
class SeatGraphQlClient(
    /**
     * 必须是**没有 cookieJar** 的 client。
     *
     * OkHttp 的 BridgeInterceptor 会用 cookieJar 的内容整段替换手写的
     * `Cookie` 头，导致 [SeatSession] 传进来的会话被静默顶掉
     * （见 [LibraryHttpClient.createForSeatGraphQl] 的注释）。
     * 选座会话只从 [SeatSession] 来，不依赖任何 cookie 罐。
     */
    private val client: OkHttpClient = LibraryHttpClient.createForSeatGraphQl(),
) {

    private val formMedia =
        "application/x-www-form-urlencoded; charset=utf-8".toMediaType()

    /** 通用结果封装。 */
    sealed interface Res<out T> {
        data class Ok<T>(val value: T) : Res<T>

        /** 40001：会话失效（已重试过）。 */
        data object SessionInvalid : Res<Nothing>

        /** 网络层失败。 */
        data class NetworkError(val message: String) : Res<Nothing>

        /** 其它业务/解析失败。 */
        data class Failed(val message: String) : Res<Nothing>
    }

    /**
     * 发一次 GraphQL 请求。
     *
     * @param session 选座会话（Cookie 四字段）
     * @param autoRetryOn401 是否在 40001 时自动重试一次（任务书：最多 1 次）
     */
    suspend fun execute(
        session: SeatSession,
        operationName: String,
        query: String,
        variables: Map<String, Any?> = emptyMap(),
        autoRetryOn401: Boolean = true,
    ): Res<String> = withContext(Dispatchers.IO) {
        val first = doExecute(session, operationName, query, variables)
        if (first is Res.Ok && SeatResponseParser.isSessionInvalid(first.value) && autoRetryOn401) {
            com.xynu.campus.library.util.LibraryLog.w(
                TAG,
                "40001 on $operationName, retrying once (session masked)",
            )
            // 只重试一次：不做用户不可见的多次重放，避免被判异常流量
            val second = doExecute(session, operationName, query, variables)
            if (second is Res.Ok && SeatResponseParser.isSessionInvalid(second.value)) {
                Res.SessionInvalid
            } else {
                second
            }
        } else if (first is Res.Ok && SeatResponseParser.isSessionInvalid(first.value)) {
            Res.SessionInvalid
        } else {
            first
        }
    }

    /**
     * 把 GraphQL 请求编码成 **form-urlencoded**。
     *
     * 【真机实测结论，别改回 JSON】
     *
     * 上游是 PHP，只从 `$_POST` 里按字段名取 `query` / `operationName` /
     * `variables`，**不解析 JSON body**。真机上做过四组对照：
     *
     * | 发法                        | 上游返回                                  |
     * |---------------------------|------------------------------------------|
     * | POST + JSON body           | `Syntax Error: Unexpected <EOF>` (code 1) |
     * | POST + 空 body              | `Syntax Error: Unexpected <EOF>` (code 1) |
     * | POST + form-urlencoded     | `Access Denied!` (40001) ← query 被正常解析 |
     * | GET + query 参数            | `Access Denied!` (40001) ← 同上             |
     *
     * 前两行返回**完全相同**，这是关键证据：服务端根本没看我们的 JSON，
     * 所以解析出来的 query 是空串，语法分析器一头撞到 EOF。
     *
     * 这解释了用户看到的现象：Cookie 粘对了、验活还"成功"了，但列表永远是空的。
     * 换成 form 编码后，40001 才正常出现 —— 那说明请求真的被读懂了，
     * 只是没带凭据（这一组故意没发 Cookie）。
     */
    private fun buildFormBody(
        operationName: String,
        query: String,
        variables: Map<String, Any?>,
    ): RequestBody {
        val varsJson = JSONObject(variables).toString()
        val form = buildString {
            append("operationName=").append(URLEncoder.encode(operationName, "UTF-8"))
            append("&query=").append(URLEncoder.encode(query, "UTF-8"))
            append("&variables=").append(URLEncoder.encode(varsJson, "UTF-8"))
        }
        return form.toRequestBody(formMedia)
    }

    private fun doExecute(
        session: SeatSession,
        operationName: String,
        query: String,
        variables: Map<String, Any?>,
    ): Res<String> {
        val req = Request.Builder()
            .url("${LibraryHttpClient.BASE_TRACEINT}/index.php/graphql/")
            .post(buildFormBody(operationName, query, variables))
            // 上游 H5 只显式设置 `App-Version`（见 LibraryHttpClient.TRACEINT_APP_VERSION），
            // 其余头由浏览器按凭据模式自动补齐。
            //
            // 这里原来还手写了 `Origin`/`Referer` = `https://web.traceint.com/...`。
            // 那个域名在全部 25 份上游 fixture 里**一次都没出现过**，是我们自己臆造的；
            // 真实 API 主机是 `wechat.v2.traceint.com`（H5 构建也指向它）。
            // 送一个不存在的来源域，轻则被忽略，重则被判伪造请求 —— 不如不发。
            //
            // Content-Type 由 .post() 的 RequestBody 自带（formMedia），不用再手写一遍。
            .header("App-Version", LibraryHttpClient.TRACEINT_APP_VERSION)
            .header("Cookie", session.toCookieHeader())
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                when {
                    resp.code == 401 -> Res.SessionInvalid
                    text.isBlank() -> Res.Failed("空响应（HTTP ${resp.code}）")
                    // 上游改版 / 风控 / 门禁页都会返回 HTML，此时不是 JSON。
                    // 不拦的话后面 JSONObject 会抛 JSONException 把页面带崩，
                    // 而且用户只会看到"闪退"而不是"上游变了"。
                    !text.trimStart().startsWith("{") -> Res.Failed(
                        "上游返回的不是 JSON（HTTP ${resp.code}），" +
                            "可能是 Cookie 入口变了或触发了风控。",
                    )
                    // 【重要】上游的错误信封必须显式处理。
                    //
                    // 上游前端 `conv()` 的逻辑是：**只要 `errors` 非空就一律判失败**，
                    // 并把 `errors[0].msg` 直接显示给用户。
                    // 我们原来只认 40001（会话失效），其它错误码一律当成功返回，
                    // 于是出现了一种非常坑的状态：
                    //   验活 → 上游返回 errors → 我们当成功 → 提示「配置成功」
                    //   → 接着查列表 → data.userAuth 是 null → 解析出空列表
                    //   → 页面显示「没有取到图书馆列表」
                    // 用户看到的是「Cookie 明明通过了却永远没数据」，而真正的原因
                    // （上游原文，例如「账号未绑定」「不在开放时段」「风控拦截」）
                    // 被整段吞掉了。
                    //
                    // 40001 必须**排除**在外：它是会话失效信号，要原样返回给
                    // execute() 去做「最多重试一次 → SessionInvalid」的处理。
                    else -> {
                        val err = SeatResponseParser.firstError(text)
                        if (err != null && !SeatResponseParser.isSessionInvalid(text)) {
                            val (code, msg) = err
                            Res.Failed(
                                if (msg.isNotBlank()) "上游拒绝：$msg（code $code）"
                                else "上游返回错误（code $code）",
                            )
                        } else {
                            Res.Ok(text)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            Res.NetworkError("上游不可达：${e.message}")
        } catch (e: HostNotAllowedException) {
            Res.Failed("host 不在白名单：${e.host}")
        } catch (e: Exception) {
            // 兜底：绝不让单次请求把整个页面带崩
            Res.Failed("请求失败：${e.javaClass.simpleName} ${e.message.orEmpty()}")
        }
    }

    // ---------- 业务方法 ----------

    /** 验活：只请求轻量查询，判断会话是否有效（40001 会在这里暴露）。 */
    suspend fun verifySession(session: SeatSession): Res<Unit> {
        val r = execute(
            session = session,
            operationName = SeatGraphQlDocuments.OP_LIB_SIMPLE,
            query = SeatGraphQlDocuments.LIB_SIMPLE,
            variables = mapOf("libType" to -1, "libId" to null),
            // 验活本身就是一次探测，不做二次重试以免浪费
            autoRetryOn401 = false,
        )
        return when (r) {
            is Res.Ok -> Res.Ok(Unit)
            is Res.SessionInvalid -> Res.SessionInvalid
            is Res.NetworkError -> r
            is Res.Failed -> r
        }
    }

    /**
     * 图书馆列表（含实时座位数）。
     *
     * 这里额外做一次**结构探测**，而不是直接把 `parseLibraries` 的
     * `emptyList()` 当结果返回：
     *
     * `parseLibraries` 对"结构对不上"和"上游真的返回空数组"都给 `emptyList()`，
     * 两者在类型上不可区分。于是上游一改版（比如 `userAuth` 换了名字），
     * 用户看到的就是「没有取到图书馆列表」——听起来像"你没权限"或"现在没馆开放"，
     * 而真实原因是"客户端不认这份响应了"。排查方向会被完全带偏。
     *
     * 所以：`data.userAuth` 这层不存在 ⇒ 明确报「响应结构不符，可能上游改版」；
     * 存在但列表为空 ⇒ 是**真的**空，返回空列表让 UI 正常说"没有取到"。
     */
    suspend fun listLibraries(session: SeatSession): Res<List<SeatLibrary>> {
        val raw = execute(session, SeatGraphQlDocuments.OP_LIST, SeatGraphQlDocuments.LIST)
        val text = when (raw) {
            is Res.Ok -> raw.value
            is Res.SessionInvalid -> return Res.SessionInvalid
            is Res.NetworkError -> return raw
            is Res.Failed -> return raw
        }
        if (!SeatResponseParser.hasUserAuthEnvelope(text)) {
            return Res.Failed("座位系统响应结构不符（找不到 data.userAuth），可能上游改版了。")
        }
        return Res.Ok(SeatResponseParser.parseLibraries(text))
    }

    /** 图书馆规则。 */
    suspend fun libRule(session: SeatSession, libId: Int): Res<SeatRule?> =
        map(
            execute(
                session,
                SeatGraphQlDocuments.OP_LIB_RULE,
                SeatGraphQlDocuments.LIB_RULE,
                mapOf("libId" to libId),
            ),
        ) { SeatResponseParser.parseRule(it) }

    /**
     * 座位布局（`reserve` 命名空间版本）。
     *
     * 和 [listLibraries] 同样的理由加了一层结构探测，但**判据更严**：
     * 布局是必须有的，`null` 一定意味着"没取到"，不存在"合法的空布局"。
     *
     * 所以这里分两种失败：
     * - 连 `data.userAuth` 都没有 ⇒ 「响应结构不符，可能上游改版」
     * - 有主干但找不到 `lib_layout` ⇒ 「这个馆的座位图取不到」
     *
     * 原来两种情况都返回 `Res.Ok(null)`，UI 只能显示「座位图没有取到。」
     * 一句通用文案，用户和开发者都看不出该等一等还是该改代码。
     */
    suspend fun libLayout(session: SeatSession, libId: Int): Res<SeatLayout?> {
        val raw = execute(
            session,
            SeatGraphQlDocuments.OP_LIB_LAYOUT,
            SeatGraphQlDocuments.LIB_LAYOUT,
            // 上游取座位图只传 libId：`libLayout({libId: t})`
            // （seatMap.chunk.js 的 getList / ti_prereserve.js 的 libLayout 都是这样）。
            // 我们原来多传了 libType = -1，而文档里是
            // `libs(libType: $libType, libId: $libId)` —— -1 会被当成
            // 「全部类型」参与过滤，可能直接过滤成空数组，导致座位图空白。
            mapOf("libId" to libId, "libType" to null),
        )
        val text = when (raw) {
            is Res.Ok -> raw.value
            is Res.SessionInvalid -> return Res.SessionInvalid
            is Res.NetworkError -> return raw
            is Res.Failed -> return raw
        }
        if (!SeatResponseParser.hasUserAuthEnvelope(text)) {
            return Res.Failed("座位系统响应结构不符（找不到 data.userAuth），可能上游改版了。")
        }
        val layout = SeatResponseParser.parseLayout(text, libId)
            ?: return Res.Failed("这个馆的座位图取不到（响应里没有 lib_layout）。")
        return Res.Ok(layout)
    }

    /**
     * 预约座位。
     *
     * `captcha` / `captchaCode` 无验证码时传空串（必填参数，不能省）。
     */
    suspend fun reserveSeat(
        session: SeatSession,
        libId: Int,
        seatKey: String,
    ): Res<SeatResponseParser.ReserveOutcome> {
        val r = execute(
            session,
            SeatGraphQlDocuments.OP_RESERVE_SEAT,
            SeatGraphQlDocuments.RESERVE_SEAT,
            mapOf(
                "libId" to libId,
                "seatKey" to seatKey,
                "captchaCode" to "",
                "captcha" to "",
            ),
        )
        return when (r) {
            is Res.Ok -> Res.Ok(SeatResponseParser.parseReserveOutcome(r.value))
            is Res.SessionInvalid -> Res.SessionInvalid
            is Res.NetworkError -> r
            is Res.Failed -> r
        }
    }

    /** 当前预约（用于显示到期倒计时）。 */
    suspend fun currentReservation(session: SeatSession): Res<CurrentReservation?> =
        map(
            execute(session, SeatGraphQlDocuments.OP_RESERVE, SeatGraphQlDocuments.RESERVE),
        ) { SeatResponseParser.parseCurrentReservation(it) }

    private inline fun <T, R> map(src: Res<T>, crossinline block: (T) -> R): Res<R> = when (src) {
        is Res.Ok -> Res.Ok(block(src.value))
        is Res.SessionInvalid -> Res.SessionInvalid
        is Res.NetworkError -> src
        is Res.Failed -> src
    }

    private companion object {
        const val TAG = "SeatGraphQL"
    }
}
