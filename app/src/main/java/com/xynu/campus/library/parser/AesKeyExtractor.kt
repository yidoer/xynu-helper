package com.xynu.campus.library.parser

/**
 * AES key best-effort 提取。
 *
 * 背景：超星绑定表单用 AES-128-ECB/PKCS7 加密用户名密码，key 硬编码在
 * `showUserBind.action` 页内联脚本里，形态：
 * ```js
 * let keys = CryptoJS.enc.Utf8.parse("<16 字符>")
 * function getAesString(data) { ... }
 * ```
 *
 * 本模块**不做 in-app 绑定提交**（见任务书 §1「明确砍掉」），因此这里的提取只用于
 * 理解页面与将来可能的兼容，不进入关键路径。所以是 best-effort：
 * 提不到就返回 null，绝不抛异常、绝不阻断借阅查询。
 *
 * 实测：showUserBind.html（5164B，含 getAesString 锚点）命中；
 * 5391–5396B 的异常状态页无锚点，提不到属正常。
 */
object AesKeyExtractor {

    /** 锚点存在性判定：没有 getAesString 就说明不是绑定表单页。 */
    private val AES_FN_ANCHOR = Regex("""getAesString""")

    /** 放宽版 key 正则：不限定 CryptoJS 前缀，只要求 Utf8.parse( 且内容 8–32 字符。 */
    private val KEY_EXTRACT = Regex("""Utf8\.parse\(\s*["']([^"']{8,32})["']\s*\)""")

    /** 页面是否具备 AES 绑定表单的结构特征。 */
    fun hasAnchor(html: String?): Boolean =
        !html.isNullOrBlank() && AES_FN_ANCHOR.containsMatchIn(html)

    /**
     * 提取 16 字符 key；长度不是 16 的候选一律跳过（AES-128 要求）。
     * 返回 null 表示该页面没有可用的 key。
     */
    fun extractKey(html: String?): String? {
        if (!hasAnchor(html)) return null
        val key = KEY_EXTRACT.find(html!!)?.groupValues?.get(1) ?: return null
        return key.takeIf { it.length == 16 }
    }
}
