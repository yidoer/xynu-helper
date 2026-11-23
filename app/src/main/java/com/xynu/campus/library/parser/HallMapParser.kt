package com.xynu.campus.library.parser

import com.xynu.campus.library.model.HallEntry
import org.jsoup.Jsoup

/**
 * hall 页入口解析器。
 *
 * 页面结构（实测 app/src/test/resources/fixtures/hall.html）：
 * ```html
 * <li><a href="javascript:toUrl('https://...',0,28277);" title=""><em>...</em>
 *        <span>座位预约</span></a></li>
 * ```
 * 解析三要素：URL、isNeedBind、itemId，以及相邻的 span 名称。
 *
 * **禁止硬编码 itemId**：服务端会增删/重排入口，本次实测的 15 项只是当前快照。
 */
object HallMapParser {

    /** `toUrl('URL',bind,itemId)` —— 实测 bind 与 itemId 均为纯数字。 */
    private val TO_URL = Regex("""toUrl\(\s*'([^']+)'\s*,\s*(\d+)\s*,\s*(\d+)\s*\)""")

    fun parse(html: String): List<HallEntry> {
        if (html.isBlank()) return emptyList()
        val doc = Jsoup.parse(html)
        return doc.select("a[href^=javascript:toUrl]").mapNotNull { a ->
            val m = TO_URL.find(a.attr("href")) ?: return@mapNotNull null
            val url = m.groupValues[1].trim()
            if (url.isEmpty()) return@mapNotNull null
            val name = a.selectFirst("span")?.text()?.trim().orEmpty()
            HallEntry(
                name = name,
                url = url,
                isNeedBind = m.groupValues[2] == "1",
                itemId = m.groupValues[3].toInt(),
            )
        }
    }

    /**
     * 从 hall 页里取出 traceint 的学校码（`from_code`）。
     *
     * 实测值形如 URL 编码后的 base64（例如 `WwsCBVIJAgs%3D`），所以必须先
     * URL 解码再 base64 解码。**禁止硬编码**：这是本校专属凭据性参数。
     */
    fun extractFromCode(html: String): String? {
        if (html.isBlank()) return null
        val m = Regex("""traceint\.com/index\.php/reserve/index\.html\?[^'"\s]*?from_code=([^'"&\s]+)""")
            .find(html) ?: return null
        return m.groupValues[1]
    }

    /** traceint 入口 URL（含 from_code），用于 F4 基础档跳转。 */
    fun traceintEntryUrl(entries: List<HallEntry>): String? =
        entries.firstOrNull { it.url.contains("traceint.com", ignoreCase = true) }?.url
}
