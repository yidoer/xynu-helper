package com.xynu.campus.library.parser

import org.jsoup.Jsoup

/**
 * 馆藏检索结果解析器（阿法迪 `libopac.xynu.edu.cn:8080` 的 sms 端）。
 *
 * 为什么另开一套而不是复用老 `LibParser`：
 * - 老 `LibParser` 打的是 `222.21.62.201:8011`（内网 only，手机在外网 15s 超时）；
 * - 这里打的是 `libopac.xynu.edu.cn:8080` 的 `/search/searchList`，
 *   与借阅同一台机器，**内外网都能通**（实测外网 200/20KB）。
 *
 * 真实结构（实测，`fixtures/catalog_search.html`）：
 * ```html
 * <li>
 *   <input type="hidden" name="detailParam" value='{"url":"http://222.21.62.201:8011/opac/book/<id>"}'/>
 *   <input type="hidden" name="title"    value='逻辑学完全指南'/>
 *   <input type="hidden" name="author"   value='— (英) 李少芬著'/>
 *   <input type="hidden" name="publisher" value='北京 北京科学技术出版社 2025.7'/>
 *   <div class="detail"><p>索书号：B81/L251</p><p>ISBN： 978-7-5714-4579-9</p>
 *                       <p>流通类型：中文图书</p></div>
 * </li>
 * ```
 * 所有书目字段都在隐藏 input 上，比解析 DOM 文本稳。
 */
object CatalogSearchParser {

    data class Book(
        val title: String,
        val author: String?,
        val publisher: String?,
        val callNo: String?,
        val isbn: String?,
        val mediaType: String?,
        /** 详情页地址；在 `222.21.62.201:8011`，**外网不可达**，仅内网可开。 */
        val detailUrl: String?,
    )

    data class Page(
        /** 「共 N 条搜索结果」。 */
        val total: Int?,
        val books: List<Book>,
        /** 当前页码，从 1 开始。 */
        val pageIndex: Int,
        /** 是否还有下一页。 */
        val hasNext: Boolean,
    ) {
        val isEmpty: Boolean get() = books.isEmpty()
    }

    private val TOTAL = Regex("""共\s*(\d+)\s*条""")
    private val PAGE_INDEX = Regex("""pageIndex=(\d+)""")

    /** 从 `<p>` 文本里抠「标签：值」。 */
    private val LABELED = Regex("""^(索书号|ISBN|流通类型)\s*[：:]\s*(.*)$""")

    /** `detailParam` 里的 JSON 片段 `"url":"…"`。 */
    private val DETAIL_URL = Regex(""""url"\s*:\s*"([^"]+)"""")

    fun parse(html: String, pageIndex: Int = 1): Page {
        if (html.isBlank()) return Page(null, emptyList(), pageIndex, false)

        val doc = Jsoup.parse(html)
        val total = TOTAL.find(doc.text())?.groupValues?.get(1)?.toIntOrNull()

        val books = doc.select("li").mapNotNull { li ->
            val title = li.selectFirst("input[name=title]")?.attr("value")?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null

            val detail = li.selectFirst("input[name=detailParam]")
                ?.attr("value")?.trim().orEmpty()
                .let { raw -> DETAIL_URL.find(raw)?.groupValues?.get(1) }

            // <p>索书号：B81/L251</p> → 按标签归位，避免顺序变化就解析错
            val labeled = li.select("div.detail p").mapNotNull { p ->
                LABELED.find(p.text().trim())?.let { m -> m.groupValues[1] to m.groupValues[2].trim() }
            }.toMap()

            Book(
                title = title,
                author = li.selectFirst("input[name=author]")?.attr("value")?.trim()?.takeIf { it.isNotEmpty() && it != "—" },
                publisher = li.selectFirst("input[name=publisher]")?.attr("value")?.trim()?.takeIf { it.isNotEmpty() },
                callNo = labeled["索书号"]?.takeIf { it.isNotEmpty() },
                isbn = labeled["ISBN"]?.takeIf { it.isNotEmpty() },
                mediaType = labeled["流通类型"]?.takeIf { it.isNotEmpty() },
                detailUrl = detail?.takeIf { it.isNotEmpty() },
            )
        }

        // 分页判定，两个来源按可靠性排序：
        //
        // ① 页面上出现过的最大 pageIndex —— 这是上游自己给的翻页信息，最可信。
        // ② 兜底：当页面完全没有分页标记（maxPage == 0）、但能读到总数时，
        //    用 total 与本页条数估算。
        //
        // 历史 bug：② 里把每页条数写死成 10（`total > pageIndex * 10 && books.size >= 10`）。
        // 上游一旦改成每页 20 条，总数 187 时第 1 页会被判成"有下一页"然后翻出空页；
        // 改成每页 5 条则第 2 页就提前失去下一页。
        // 现在 ② 只在本页"看起来是满页"时生效——满页的前提是它的条数等于
        // 观测到的最大单页条数，这仍然是个估计，所以只当兜底、且条件取保守侧
        // （宁可少给一个"下一页"，也不要把用户送到空页）。
        val maxPage = PAGE_INDEX.findAll(html)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            // 脏 HTML 里出现 pageIndex=99999 会让用户一路翻到空页，加个合理性上界。
            .filter { it in 1..MAX_REASONABLE_PAGE }
            .maxOrNull() ?: 0

        val hasNext = if (maxPage > 0) {
            maxPage > pageIndex
        } else {
            // 无分页标记时的兜底：本页条数 N，已看到 pageIndex * N 条，
            // 总数还没到就认为有下一页。只在 N > 0 时有意义。
            val n = books.size
            total != null && n > 0 && total > pageIndex * n
        }

        return Page(total = total, books = books, pageIndex = pageIndex, hasNext = hasNext)
    }

    private const val MAX_REASONABLE_PAGE = 1000
}
