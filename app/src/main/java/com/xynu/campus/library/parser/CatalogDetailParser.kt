package com.xynu.campus.library.parser

import org.jsoup.Jsoup

/**
 * 书目详情 + 馆藏复本解析器（阿法迪 sms 端 `POST /search/bookDetail`）。
 *
 * 为什么需要它：检索列表只给书名/作者/索书号/ISBN，**「在馆几本、在哪个书库」**
 * 这些真正影响借书的信息只有详情页有。原先详情走 `222.21.62.201:8011`
 * （`LibApi.getBookDetail`），那台机器**只对校园网开放**，用户连热点时 15 秒超时。
 *
 * 实测 `libopac.xynu.edu.cn:8080` 上的 `POST /search/bookDetail` 直接返回同样的
 * 书目信息 + 馆藏表，内外网都能通（`fixtures/catalog_detail.html`、`catalog_detail_2.html`）。
 *
 * 真实结构：
 * ```html
 * <div class="catalog">
 *   <p>ISBN/价格：978-7-122-47467-4:CNY52.00</p>
 *   <p>题名责任者项：大白话讲透逻辑学/.丁毓峰编著</p>
 *   <p>出版发行项：北京:,化学工业出版社:,2026.3</p>
 *   <p>载体形态项：266页:;+图:;+21cm</p>
 *   <p>提要文摘：本书分为两篇……</p>
 *   <p>题名主题：逻辑学 通俗读物</p>
 *   <p>中图分类：B81-49</p>
 * </div>
 * <div class="tableLib">                     <!-- 一个分馆块 -->
 *   <div class="tableCon"><table>
 *     <tr><th>条码号</th><td>23364954</td></tr>
 *     <tr><th>索书号</th><td>B81-49/D591</td></tr>
 *     <tr><th>藏馆</th><td>社科书库一</td></tr>
 *     <tr><th>馆藏状态</th><td>在架</td></tr>
 *   </table></div>
 * </div>
 * ```
 *
 * 注意点（都是实测踩出来的）：
 * - `<p>` 的标签不固定，有「题名主题」也有「题名主题词」，所以用**前缀匹配 + 表驱动**；
 * - `ISBN/价格` 把两个字段塞在一行（`978-…:CNY52.00`），要拆；
 * - `tableLib` 块可能有多个（多分馆），必须全部收集，不能只取第一个；
 * - HTML 里带 `<?xml … ?>` 头，jsoup 能正常吃。
 */
object CatalogDetailParser {

    /** 一条馆藏复本。 */
    data class Holding(
        val barcode: String?,
        val callNo: String?,
        val library: String?,
        /** 「在架 / 借出 / 不外借」等原始状态词。 */
        val status: String?,
    ) {
        /** 是否可借（状态词含「在架/可借」）。 */
        val isAvailable: Boolean
            get() = status?.let { it.contains("在架") || it.contains("可借") } == true
    }

    data class Detail(
        val title: String?,
        val author: String?,
        val isbn: String?,
        val price: String?,
        val publisher: String?,
        val publishDate: String?,
        val physicalForm: String?,
        val summary: String?,
        val subject: String?,
        val clcClass: String?,
        val series: String?,
        val holdings: List<Holding>,
    ) {
        /** 在架复本数。 */
        val availableCount: Int get() = holdings.count { it.isAvailable }

        /** 有没有任何可展示的内容（全空时 UI 应显示「无数据」而不是空卡片）。 */
        val isEmpty: Boolean
            get() = title == null && author == null && isbn == null &&
                publisher == null && summary == null && holdings.isEmpty()
    }

    /** `<p>标签：值</p>` 的标签 → 字段。用前缀匹配，兼容「题名主题 / 题名主题词」。 */
    private val LABEL_TO_FIELD: List<Pair<String, String>> = listOf(
        "ISBN/价格" to "isbnPrice",
        "ISBN" to "isbnPrice",
        "题名责任者项" to "titleAuthor",
        "责任者" to "titleAuthor",
        "出版发行项" to "publisher",
        "出版发行" to "publisher",
        "载体形态项" to "physical",
        "载体形态" to "physical",
        "提要文摘" to "summary",
        "内容提要" to "summary",
        "题名主题" to "subject",
        "题名主题词" to "subject",
        "主题词" to "subject",
        "中图分类" to "clc",
        "分类号" to "clc",
        "丛编项" to "series",
        "丛编" to "series",
        "个人名称等同" to "authorExtra",
        "个人名称" to "authorExtra",
    )

    /** 表格里 `<th>` → 字段名。 */
    private val TH_MAP = mapOf(
        "条码号" to "barcode",
        "索书号" to "callNo",
        "藏馆" to "library",
        "馆藏状态" to "status",
    )

    fun parse(html: String): Detail {
        if (html.isBlank()) return emptyDetail()

        val doc = Jsoup.parse(html)

        // ---------- 书目信息 ----------
        val fields = mutableMapOf<String, String>()
        doc.select("div.catalog p").forEach { p ->
            val text = p.text().trim()
            val idx = text.indexOfFirst { it == '：' || it == ':' }
            if (idx <= 0) return@forEach
            val label = text.substring(0, idx).trim()
            val value = text.substring(idx + 1).trim()
            // 长标签优先：避免「ISBN/价格」被「ISBN」抢先匹配
            val field = LABEL_TO_FIELD
                .sortedByDescending { it.first.length }
                .firstOrNull { label == it.first || label.startsWith(it.first) }
                ?.second
                ?: return@forEach
            if (value.isNotEmpty() && !fields.containsKey(field)) fields[field] = value
        }

        // 「978-7-122-47467-4:CNY52.00」→ isbn + price
        val (isbn, price) = fields["isbnPrice"]?.let { raw ->
            val sep = raw.indexOfFirst { it == ':' || it == '：' }
            if (sep > 0) {
                raw.substring(0, sep).trim() to raw.substring(sep + 1).trim().takeIf { it.isNotEmpty() }
            } else {
                raw.trim() to null
            }
        } ?: (null to null)

        // 「大白话讲透逻辑学/.丁毓峰编著」→ title + author
        val (respTitle, respAuthor) = fields["titleAuthor"]?.let { raw ->
            val sep = raw.indexOf("/.")
            if (sep > 0) {
                raw.substring(0, sep).trim() to raw.substring(sep + 2).trim().takeIf { it.isNotEmpty() }
            } else {
                raw.trim() to null
            }
        } ?: (null to null)

        // 「北京:,化学工业出版社:,2026.3」→ publisher + date（取最后一段数字年份）
        val (publisher, pubDate) = fields["publisher"]?.let { raw ->
            val parts = raw.split(':', ',').map { it.trim() }.filter { it.isNotEmpty() }
            val date = parts.lastOrNull()?.takeIf { Regex("""\d{4}""").containsMatchIn(it) }
            val name = parts.filter { it != date }.joinToString(" ").takeIf { it.isNotEmpty() }
            name to date
        } ?: (null to null)

        // 标题优先用 <h1>（比「题名责任者项」干净，没有「/.」）
        val title = doc.selectFirst("div.tit h1, article header h1, h1")
            ?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: respTitle

        // ---------- 馆藏复本 ----------
        val holdings = doc.select("div.tableLib").mapNotNull { block ->
            val kv = block.select("table tr").mapNotNull { tr ->
                val th = tr.selectFirst("th")?.text()?.trim().orEmpty()
                val td = tr.selectFirst("td")?.text()?.trim().orEmpty()
                val field = TH_MAP[th] ?: return@mapNotNull null
                field to td
            }.toMap()
            if (kv.isEmpty()) return@mapNotNull null
            Holding(
                barcode = kv["barcode"]?.takeIf { it.isNotEmpty() },
                callNo = kv["callNo"]?.takeIf { it.isNotEmpty() },
                library = kv["library"]?.takeIf { it.isNotEmpty() },
                status = kv["status"]?.takeIf { it.isNotEmpty() },
            )
        }

        return Detail(
            title = title,
            author = respAuthor ?: fields["authorExtra"],
            isbn = isbn,
            price = price,
            publisher = publisher,
            publishDate = pubDate,
            physicalForm = fields["physical"],
            summary = fields["summary"],
            subject = fields["subject"],
            clcClass = fields["clc"],
            series = fields["series"],
            holdings = holdings,
        )
    }

    private fun emptyDetail() = Detail(
        null, null, null, null, null, null, null, null, null, null, null, emptyList(),
    )
}
