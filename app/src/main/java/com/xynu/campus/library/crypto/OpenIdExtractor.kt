package com.xynu.campus.library.crypto

/**
 * openid 提取与格式校验。
 *
 * 用户从微信里复制的链接形如：
 * `http://mobilelib.wx.chaoxing.com/weixin/hall/showNew?pid=1512&type=8&openid=oXXXX...`
 * 正则按任务书 F1：`openid=(o[A-Za-z0-9_-]{20,})`。
 *
 * **不做真伪断言**：本地只能校验格式与上游落地页状态，
 * openid 真伪只由 mobilelib 在 bind 时确认（见任务书 §1）。
 */
object OpenIdExtractor {

    private val OPENID = Regex("""openid=(o[A-Za-z0-9_-]{20,})""")
    private val OPENID_SHAPE = Regex("""^o[A-Za-z0-9_-]{20,}$""")

    /** F1 的三种失败原因，直接映射成中文文案。 */
    enum class Failure(val message: String) {
        /** 剪贴板空 / 无权限 / 用户没复制。 */
        EMPTY_CLIPBOARD("没读取到剪贴板内容，请先回到微信复制链接，再点「我复制好了」"),

        /** 有内容但不是图书馆链接。 */
        NOT_LIBRARY_LINK("这段内容里没有 openid，请确认复制的是公众号「借阅信息」页面的链接"),

        /** 有 openid= 但格式不合规。 */
        MALFORMED_OPENID("链接里的 openid 格式不对，请重新复制一次完整链接"),
    }

    sealed interface Result {
        data class Success(val openid: String) : Result
        data class Failed(val reason: Failure) : Result
    }

    fun extract(clipboard: String?): Result {
        if (clipboard.isNullOrBlank()) return Result.Failed(Failure.EMPTY_CLIPBOARD)

        val match = OPENID.find(clipboard)
        if (match == null) {
            // 含 "openid" 关键字但正则不匹配 → 格式问题；否则就是复制了别的东西
            return if (clipboard.contains("openid", ignoreCase = true)) {
                Result.Failed(Failure.MALFORMED_OPENID)
            } else {
                Result.Failed(Failure.NOT_LIBRARY_LINK)
            }
        }
        return Result.Success(match.groupValues[1])
    }

    /** 日志脱敏：`openid=oX***`（红线要求）。 */
    fun mask(openid: String?): String = when {
        openid.isNullOrBlank() -> "openid=***"
        openid.length <= 4 -> "openid=***"
        else -> "openid=${openid.take(2)}***"
    }

    /**
     * 独立的格式校验（用于 JSBridge 收到的裸 openid，不含链接上下文）。
     *
     * 与 [extract] 的区别：这里没有 `openid=` 前缀可依，只校验形状。
     */
    fun isWellFormed(openid: String?): Boolean {
        if (openid.isNullOrBlank()) return false
        return OPENID_SHAPE.matches(openid)
    }
}
