package com.xynu.campus.library.migrate

import com.xynu.campus.library.crypto.OpenIdExtractor
import com.xynu.campus.library.model.SeatSession
import com.xynu.campus.library.store.IdentityStore
import com.xynu.campus.library.util.LibraryLog
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

/**
 * F5 本地身份迁移：导出/导入全部凭据。
 *
 * 设计取舍（任务书 F5）：
 * - **纯本地**：导出物是一段文本（可存文件/二维码），不经过任何服务器
 * - **必须警告**：导出物包含 openid + Cookie，等价于账号本体
 * - **不做加密**：用户要迁移就得能跨设备读，若加密则需再传一个密钥，
 *   反而更不安全（用户会把密钥也一起记下来）。所以明确警告 + 明文，
 *   但 UI 必须让用户确认他知道自己在导出什么。
 *
 * 格式用 JSON 包一层 Base64：避免贴到微信/备忘录里被自动转义或截断，
 * 同时让它看起来"不是随手能读的"，减少误发风险。
 */
object IdentityMigration {

    /** 迁移包版本，向后兼容留出空间。 */
    private const val VERSION = 2

    /**
     * 迁移码末尾的校验和长度（十六进制字符数）。
     *
     * **这里刻意用校验和（checksum），不是签名，也不是 MAC。**
     * 把边界说清楚，否则注释就成了自欺：
     *
     * - 防：抄错 / 截断 / 粘贴时被自动转义。改一个字符校验和对不上，
     *   用户立刻看到「迁移码不完整或被改过」，可以再抄一次。
     *   否则会"导入成功"，直到查询时才失败——而那种失败看起来
     *   像「Cookie 失效」，用户的归因方向就完全错了。
     * - **不防**：伪造。校验和算法就在安装包里，谁都能算出一个合法的。
     *
     * 要真的防伪，密钥必须不可导出（AndroidKeyStore）且由**可信端**签名；
     * 但迁移码的整个设计前提是「本机生成、本机消费、可离线手抄」，
     * 没有可信第三方，所以那个前提根本不成立。用不可导出密钥给
     * "自己给自己签名"的东西加签，只会得到安全剧场。
     *
     * 真正的防线是下面两条（都在 [import] 里落实）：
     * 1. 全部校验通过**之后**才 `clearAll()` —— 坏包绝不破坏本机数据；
     * 2. openid 必须过 [OpenIdExtractor.isWellFormed]。
     *
     * ## 一条被撤回的声明
     *
     * 这里原本还写着第 2 条「导入的 seat session 一律 `verifiedAt = 0`，
     * **强制重新验活**」，配套处还写「让 UI 提示未验证，进选座页会主动做一次验活」。
     * **这句话不成立，已删除。** 事实是：`verifiedAt` 在整个 `main` 源集里
     * 只被写入、存储、迁移和导出，**没有任何一处把它读进判断**；
     * UI 层搜 `verifiedAt` 也是零引用。真实门控是
     * `SeatViewModel.refreshTier()` 里的 `session != null && session.isComplete`，
     * 而 `isComplete` 只判会话与 JWT 两个字段非空。
     *
     * 所以 `verifiedAt = 0L` 的实际效果**只是"记录上不写时间戳"**，
     * 没有任何强制重验行为。保留这个赋值是有意义的（不该谎称刚验过），
     * 但不能声称它带来了一道防线。
     *
     * 若要真的实现"强制重验"，需要让 `refreshTier()` 参与判定并在
     * `verifiedAt == 0` 时先验活再进高级档 —— 那是行为改动，必须配测试，
     * 不能靠注释宣布完成。
     */
    private const val CHECKSUM_LEN = 16

    /**
     * 载荷校验和：SHA-256 的十六进制前 [CHECKSUM_LEN] 位。
     *
     * 用 SHA-256 而不是 CRC32 纯粹是因为它已经在 JDK 里、不用引依赖，
     * 且长度足够让"少粘一段"和"多粘一个换行"都必然被发现。
     */
    private fun checksum(payload: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(CHECKSUM_LEN)
    }

    /** 定长比较，避免用 `==` 逐字符短路（对校验和不是必须，但保持一致习惯）。 */
    private fun checksumEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    /** 导出结果。 */
    data class Payload(
        /** 可直接展示/复制的文本（Base64）。 */
        val text: String,
        /** 人类可读摘要，用于 UI 二次确认。 */
        val summary: String,
        val hasBorrow: Boolean,
        val hasSeat: Boolean,
    )

    /** 导入结果。 */
    sealed interface ImportResult {
        data class Success(val hasBorrow: Boolean, val hasSeat: Boolean) : ImportResult
        data class Invalid(val reason: String) : ImportResult
    }

    /**
     * 导出当前身份。
     *
     * @return null 表示本机没有任何身份可导出（避免导出空包误导用户）
     */
    fun export(store: IdentityStore): Payload? {
        val snap = store.snapshot()
        if (snap.isEmpty) return null

        // 校验和覆盖的是**身份字段本身**的规范化串，而不是 json.toString()。
        // 因为 JSON 键顺序不保证稳定，用 toString() 算出来的校验和在别处重算会不一致。
        val canonical = buildString {
            append("v=").append(VERSION)
            append("|openid=").append(snap.openid.orEmpty())
            append("|boundAt=").append(snap.openidBoundAt)
            snap.seat?.let { s ->
                append("|sess=").append(s.wechatSessId)
                append("|auth=").append(s.authorization)
                append("|from=").append(s.fromCode)
                append("|vcode=").append(s.version)
            }
        }

        val json = JSONObject().apply {
            put("v", VERSION)
            put("app", "xynu-campus")
            put("module", "library")
            put("sum", checksum(canonical))
            snap.openid?.let {
                put("openid", it)
                put("openid_bound_at", snap.openidBoundAt)
            }
            snap.seat?.let { s ->
                put("wechat_sess_id", s.wechatSessId)
                put("authorization", s.authorization)
                put("from_code", s.fromCode)
                put("v_code", s.version)
            }
        }

        val encoded = Base64.getEncoder().encodeToString(json.toString().toByteArray())

        val parts = buildList {
            if (snap.openid != null) add("借阅身份（openid）")
            if (snap.seat != null) add("选座 Cookie")
        }

        LibraryLog.i(
            "IdentityMigration",
            "exported ${parts.joinToString("+")} (openid=${com.xynu.campus.library.crypto.OpenIdExtractor.mask(snap.openid)}, cookie=***)",
        )

        return Payload(
            text = encoded,
            summary = "包含：${parts.joinToString("、")}",
            hasBorrow = snap.openid != null,
            hasSeat = snap.seat != null,
        )
    }

    /**
     * 导入身份。
     *
     * 语义：**整体替换**而不是合并。迁移的意图是"把旧手机的状态搬到新手机"，
     * 合并会让两台机器的残留混在一起，反而更难排查。
     */
    fun import(store: IdentityStore, text: String): ImportResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return ImportResult.Invalid("迁移码是空的，没东西可以导入。")
        }

        val json = runCatching {
            val decoded = String(Base64.getDecoder().decode(trimmed))
            JSONObject(decoded)
        }.getOrNull() ?: return ImportResult.Invalid(
            "这段文字不是有效的迁移码。请确认完整复制了整段内容（不要带空格或换行）。",
        )

        if (json.optString("app") != "xynu-campus" || json.optString("module") != "library") {
            return ImportResult.Invalid("这段迁移码不是「信阳师范学院校园」的图书馆功能迁移码，无法导入。")
        }

        val openidRaw = json.optString("openid", "").takeIf { it.isNotBlank() }
        val sessId = json.optString("wechat_sess_id", "").takeIf { it.isNotBlank() }
        val auth = json.optString("authorization", "").takeIf { it.isNotBlank() }
        val fromCode = json.optString("from_code", "").takeIf { it.isNotBlank() }

        if (openidRaw == null && sessId == null) {
            return ImportResult.Invalid("迁移码里既没有借阅身份也没有选座 Cookie，导入没有意义。")
        }

        // ① 校验和先过：这是唯一能区分"抄错"与"Cookie 真失效"的地方。
        //
        // 迁移码是纯 Base64，改错一个字符仍可能解出结构合法的 JSON（尤其
        // from_code / JWT 这类不透明串，没有任何格式约束可以兜住）。
        // 不校验的话用户会看到「导入成功」，直到查询时才失败，
        // 而失败信息看起来像「Cookie 失效」—— 归因方向完全错了。
        //
        // 老版本迁移包（VERSION=1）没有 sum 字段。这里**不**拒绝它们，
        // 只是跳过校验：拒收会让已经导出的旧包全部作废，代价比收益大。
        // 新版导出一定带 sum。
        val storedSum = json.optString("sum", "")
        if (storedSum.isNotEmpty()) {
            val canonical = buildString {
                append("v=").append(json.optInt("v", 1))
                append("|openid=").append(openidRaw.orEmpty())
                append("|boundAt=").append(json.optLong("openid_bound_at", 0L))
                if (sessId != null) {
                    append("|sess=").append(sessId)
                    append("|auth=").append(auth.orEmpty())
                    append("|from=").append(fromCode.orEmpty())
                    // 缺省必须与 SeatSession.DEFAULT_VERSION 一致（空串＝不发送）。
                    //
                    // 原来这里写死 "5.5"。那个值在上游全部 JS bundle 里出现 0 次，
                    // 是我们自己臆造的——SeatSession 已经专门论证过为什么不能用它。
                    // 而且两处（校验和 / 落库）各抄一遍字面量，只改一处就会变成
                    // "校验通过但版本被改写"的隐蔽错位，所以统一引用常量。
                    append("|vcode=")
                        .append(json.optString("v_code", SeatSession.DEFAULT_VERSION))
                }
            }
            if (!checksumEquals(checksum(canonical), storedSum)) {
                return ImportResult.Invalid(
                    "迁移码不完整或被改过（校验和不匹配）。请重新完整复制一次，" +
                        "注意不要漏掉任何字符、也不要在中间插入换行。",
                )
            }
        }

        // ② 结构校验：与 JSBridge.saveIdentity（LibraryJsBridge.kt）同一套口径。
        if (openidRaw != null && !OpenIdExtractor.isWellFormed(openidRaw)) {
            return ImportResult.Invalid(
                "迁移码里的 openid 格式不对（应以 o 开头且长度 ≥ 21）。" +
                    "多半是复制时漏了字符，请重新完整复制一次。",
            )
        }
        val openid = openidRaw

        // 选座 Cookie 只要求会话 + JWT 两项。
        // from_code 是入口 URL 参数而非 Cookie 字段，旧版本导出物里可能没有，
        // 不能因为缺它就把整包判成坏包。
        if (sessId != null && auth == null) {
            return ImportResult.Invalid("迁移码里的选座 Cookie 不完整（缺少 Authorization）。")
        }

        // ③ 全部校验通过之后才动本机数据。
        //
        // 注意这里的顺序：clearAll() 放在所有 return 之后。
        // 若像原来那样在校验中途 clearAll()，任何一条后续 return
        // 都会把用户原有身份删掉却什么都没导入 —— 数据丢失且不可撤销。
        store.clearAll()

        if (openid != null) {
            store.saveBorrowIdentity(openid, json.optLong("openid_bound_at", 0L))
        }

        if (sessId != null && auth != null) {
            store.saveSeatSession(
                SeatSession(
                    wechatSessId = sessId,
                    authorization = auth,
                    fromCode = fromCode.orEmpty(),
                    // 与 L197 校验和用**同一个**缺省值，见那里的注释。
                    version = json.optString("v_code", SeatSession.DEFAULT_VERSION),
                ),
                // 关键：导入的 Cookie 来自另一台设备/另一个时间点，本机从未验活过。
                // 若沿用 saveSeatSession 的默认值（now），记录上会显示"刚验过"。
                verifiedAt = 0L,
            )
        }

        LibraryLog.i(
            "IdentityMigration",
            "imported openid=${com.xynu.campus.library.crypto.OpenIdExtractor.mask(openid)}, cookie=***",
        )

        return ImportResult.Success(hasBorrow = openid != null, hasSeat = sessId != null)
    }
}
