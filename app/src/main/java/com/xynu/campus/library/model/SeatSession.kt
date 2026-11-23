package com.xynu.campus.library.model

/**
 * 选座（traceint「我去图书馆」）会话。
 *
 * 会话是纯 Cookie 形态，来自用户在微信里的一次性抓包：
 * - `wechatSESS_ID`：会话主键
 * - `Authorization`：JWT
 * - `FROM_CODE`：学校码（也可从 hall 页动态提取）
 * - `v`：接口版本，实测 `5.5`
 *
 * 红线：这些字段等同账号本体，只存 EncryptedSharedPreferences，永不上传。
 */
data class SeatSession(
    val wechatSessId: String,
    val authorization: String,
    /**
     * 学校码，**可以为空**。
     *
     * 重要：`from_code` 是 traceint 入口 URL 的查询参数
     * （`.../reserve/index.html?f=wechat&from_code=xxx`），
     * **不是 Cookie 字段**——实测 traceint 的前端 bundle 里一次都没出现，
     * 微信里也看不到。所以它绝不能作为"粘贴 Cookie"的必填项，
     * 否则用户明明粘了完整 Cookie 也会被判不完整。
     *
     * 真正需要它的地方只有"跳微信"的深链（F4 基础档），
     * 那条路径的值由 hall 页动态解析后传进来。
     */
    val fromCode: String = "",
    val version: String = DEFAULT_VERSION,
    /**
     * 最近一次验活成功的时间戳（毫秒），0 表示从未验活。
     *
     * **注意：这个字段目前没有参与任何判定。**
     * 它只被写入（[com.xynu.campus.library.store.IdentityStore.saveSeatSession] /
     * `markSeatVerified`）、持久化、随迁移包导出/导入，
     * 没有任何一处把它读进 `if` 去决定"要不要重新验活"或"算不算高级档"。
     *
     * 真实的高级档门控是 `SeatViewModel.refreshTier()` 里的
     * `session != null && session.isComplete`（只判两个字段非空）。
     *
     * 曾有一处注释声称 `verifiedAt = 0` 会"让 UI 提示未验证、进选座页主动验活"，
     * 那是没有实现的意图，已撤回。保留此字段是为了**记录事实**
     * （避免把导入的旧凭据标成"刚刚验过"），不是为了驱动行为。
     * 详见 `docs/review-notes.md` §9.15.2。
     */
    val verifiedAt: Long = 0L,
) {
    /** 是否具备高级档所需的最小字段集。from_code 不参与判定。 */
    val isComplete: Boolean
        get() = wechatSessId.isNotBlank() && authorization.isNotBlank()

    /**
     * 拼装成 Cookie 请求头。
     *
     * 只发用户真实提供过的字段：
     * - `from_code` 为空时不发（发空值会污染请求）
     * - `v` 为空时不发
     *
     * 关于 `v`：原来这里会在用户没提供时**主动填一个 `v=5.5`**。那个 `5.5`
     * 在上游全部 JS bundle 里出现 0 次，是我们自己臆造的；真实 Cookie 里
     * 本来就不带它。往请求里塞一个凭空捏造的版本号，有可能反而覆盖或干扰
     * 服务端真正的版本判定，所以拿不到就不发。
     */
    fun toCookieHeader(): String = buildString {
        append("wechatSESS_ID=").append(wechatSessId)
        append("; Authorization=").append(authorization)
        if (fromCode.isNotBlank()) append("; FROM_CODE=").append(fromCode)
        if (version.isNotBlank()) append("; v=").append(version)
    }

    /**
     * 日志用脱敏表示。
     *
     * `FROM_CODE` 原来直接打原文 —— 它是入口 URL 参数，本身不是密钥，
     * 但它和凭据同处一条日志，且足以把"谁在哪个入口"落到 logcat。
     * 统一脱敏，规则与另外两项一致。
     */
    fun maskedForLog(): String = "wechatSESS_ID=${mask(wechatSessId)}; " +
        "Authorization=${mask(authorization)}; FROM_CODE=${mask(fromCode)}"

    /**
     * 只留前 2 个字符。
     *
     * 原来是「前 2 + 后 2」（`raw.take(2)***raw.takeLast(2)`）。对 JWT 这种
     * 长度固定、末段是签名 base64 的串，末 2 字符同样是凭据的一部分；
     * 对短串（≤4）则会被整段抹掉，规律不一致。改成只留前缀，口径统一。
     */
    private fun mask(raw: String): String = when {
        raw.isEmpty() -> "-"
        raw.length <= 4 -> "***"
        else -> "${raw.take(2)}***"
    }

    companion object {
        /**
         * `v` 的缺省值：**空串＝不发送**。
         *
         * 历史上这里填的是 `"5.5"`，但那个值在上游 bundle 里查不到任何依据，
         * 属于臆造。用户粘进来的 Cookie 里有 `v` 就用真实的，没有就不发 ——
         * 让服务端按自己的默认逻辑走，比替它猜一个数字安全。
         */
        const val DEFAULT_VERSION = ""

        /**
         * 从用户粘贴的 Cookie 整串里解析会话字段。
         *
         * 设计原则：**只要求真正必须的，其余尽量兼容**。
         *
         * 必填只有两项：`wechatSESS_ID` + `Authorization`。
         * `from_code` 是入口 URL 的参数、不是 Cookie 字段（见 [fromCode] 注释），
         * 所以**可选**；`v` 缺失就用默认值。
         *
         * 兼容形态（实测用户会粘进来的各种样子）：
         * - `a=b; c=d`
         * - 带 `Cookie:` 前缀
         * - 换行分隔（浏览器 / 抓包工具复制结果）
         * - `&` 分隔（从 URL 查询串复制）
         * - 键名大小写不一致（`FROM_CODE` / `from_code` / `fromCode`）
         * - `Authorization=Bearer xxx`（多一个 Bearer 前缀）
         *
         * 缺必填项返回 null，交由上层给中文原因。
         */
        fun parseRawCookie(raw: String): SeatSession? {
            if (raw.isBlank()) return null

            // 去掉所有可能的前缀与包裹，统一成 "k=v;k=v" 再解析
            val cleaned = raw
                .replace("Cookie:", "", ignoreCase = true)
                .replace("cookie:", "", ignoreCase = true)
                .removeSurrounding("\"")
                .replace("\r", ";")
                .replace("\n", ";")
                .replace("&", ";")

            val map = HashMap<String, String>()
            cleaned.split(";").forEach { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) return@forEach
                // 键名归一化：去掉下划线与空格再小写比较，这样
                // FROM_CODE / fromCode / fromcode 都能对上
                val k = part.substring(0, idx).trim()
                    .lowercase()
                    .replace("_", "")
                    .replace(" ", "")
                val v = part.substring(idx + 1).trim()
                if (k.isNotEmpty() && v.isNotEmpty()) map[k] = v
            }

            val sess = map["wechatsessid"] ?: return null
            // Authorization 去掉可能带的 Bearer 前缀，否则拼进 Cookie 头会变成
            // "Authorization=Bearer eyJ..." 导致上游解析失败
            val auth = map["authorization"]
                ?.removePrefix("Bearer ")
                ?.removePrefix("bearer ")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return null

            val from = map["fromcode"].orEmpty()
            val ver = map["v"]?.takeIf { it.isNotBlank() } ?: DEFAULT_VERSION
            return SeatSession(
                wechatSessId = sess,
                authorization = auth,
                fromCode = from,
                version = ver,
            )
        }
    }
}
