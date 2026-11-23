package com.xynu.campus.library.parser

import com.xynu.campus.library.util.HtmlText
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * F3 借阅摘要解析器。
 *
 * 上游 `itemId=20302` 返回的是**面向手机的老式 HTML**，没有 JSON 接口，
 * 所以只能解析 DOM。这里的态度是「能拿到多少算多少」：
 *
 * - 解析成功 → 给用户一张摘要卡（当前借阅本数、可借数、最近应还日期）
 * - 解析失败 → 绝不报错，退回展示原始文本
 *
 * 为什么不用固定 CSS 选择器硬编码：
 * 超星这套页面在 wx/school 维度有多个模板版本，选择器随时会变。
 * 所以这里按**中文标签文字**定位（"当前借阅"、"应还日期" 这些业务词更稳定），
 * 再兜底到正则扫全文。两者都失败就返回 null，UI 自然退化。
 *
 * 任务书把这块标为「summary parser placeholder until real bound fixture」——
 * 本实现就是这个 placeholder 的完成版：结构已经按最可能的字段写好，
 * 拿到真实已绑定账号的 HTML 后只需补一条 fixture 测试，不改代码结构。
 */
object BorrowSummaryParser {

    /** 一条借阅中的图书（阿法迪模板里每个 `div.tableLib` 是一本书）。 */
    data class Item(
        val barcode: String?,
        val title: String?,
        val borrowDate: String?,
        val dueDate: String?,
        val renewCount: Int?,
    ) {
        /** 应还日期里的日期部分（`2026/12/30 23:59:59` → `2026/12/30`）。 */
        val dueDay: String?
            get() = dueDate?.let { DueDateRegex.find(it)?.value }
    }

    /** 解析结果。任一字段解析不出来就是 null，UI 自行决定展示哪些。 */
    data class Summary(
        /** 当前借阅册数。 */
        val currentBorrowed: Int? = null,
        /** 可借册数上限内还可借多少。 */
        val borrowable: Int? = null,
        /** 借阅历史总数（若有展示）。 */
        val historyTotal: Int? = null,
        /** 最近应还日期，原样保留上游文案（格式如 2024-06-01）。 */
        val dueDate: String? = null,
        /** 欠费金额文案，原样保留（"0.00" 或 "无"）。 */
        val unpaid: String? = null,
        /** 读者证号脱敏后的展示（如 2021****23）。 */
        val readerMasked: String? = null,
        /** 续借次数（阿法迪模板有 `class="renewCount"`）。 */
        val renewCount: Int? = null,
        /** 逐本借阅明细。空表示模板里没有书单（或全已归还）。 */
        val items: List<Item> = emptyList(),
    ) {
        val isEmpty: Boolean
            get() = currentBorrowed == null && borrowable == null &&
                historyTotal == null && dueDate == null && unpaid == null &&
                readerMasked == null && renewCount == null && items.isEmpty()

        /** 给卡片顶部的一句话。 */
        fun headline(): String = when {
            currentBorrowed == null -> "借阅信息"
            currentBorrowed == 0 -> "当前没有借阅中的图书"
            else -> "当前借阅 $currentBorrowed 册"
        }
    }

    // 业务词锚点：这些词在超星移动图书馆各模板里都出现。
    // 阿法迪（/sms/opac）模板用的是「目前借书N本」，超星自家模板用「当前借阅N册」，
    // 两种都要认——真实 fixture 见 app/src/test/resources/fixtures/lend_status.html
    private val CURRENT = Regex("""(?:当前借阅|目前借书|在借)[^0-9]{0,8}(\d{1,3})\s*(?:册|本)?""")
    private val BORROWABLE = Regex("""可借[^0-9]{0,8}(\d{1,3})\s*(?:册|本)?""")
    private val HISTORY = Regex("""借阅历史[^0-9]{0,8}(\d{1,5})\s*(?:册|本)?""")
    private val DUE = Regex("""应还(?:日期|时间)\s*[:：]?\s*(\d{4}[-/年]\d{1,2}[-/月]\d{1,2})""")
    private val DUE_LOOSE = Regex("""(\d{4}[-/]\d{1,2}[-/]\d{1,2})\s*(?:应还|到期)?\s*(\d{1,2}:\d{2})?""")
    private val UNPAID = Regex("""欠费\s*[:：]?\s*(?:￥|¥)?\s*([\d.]+|无)""")
    private val READER = Regex("""(?:读者证|借书证|证号)\s*[:：]?\s*([0-9A-Za-z*]{4,24})""")
    private val RENEW = Regex("""续借次数\s*[:：]?\s*(\d{1,3})""")
    private val DueDateRegex = Regex("""\d{4}[-/]\d{1,2}[-/]\d{1,2}""")

    // 以下在逐格 / 逐字段的热路径上调用，预编译一次，避免每次调用都重新编译正则
    private val LABEL_NOISE = Regex("""[\s:：]+""")
    private val WHITESPACE_RUN = Regex("""[ \t\r\n]+""")
    private val INT_UP_TO_3 = Regex("""\d{1,3}""")
    private val INT_UP_TO_5 = Regex("""\d{1,5}""")
    private val NUMBER = Regex("""[\d.]+""")
    private val DATE_CN = Regex("""\d{4}[-/年]\d{1,2}[-/月]\d{1,2}""")

    /** 书单里 `div.tableLib` 的 `<th>` 标签 → 字段。 */
    private val ITEM_FIELDS = mapOf(
        "条码号" to "barcode",
        "题名" to "title",
        "借阅日期" to "borrowDate",
        "应还日期" to "dueDate",
        "续借次数" to "renewCount",
    )

    /**
     * 逐本借阅明细。
     *
     * 真实页面结构（`fixtures/lend_status.html`）：每本借出的书是一个 `div.tableLib`,
     * 里面是 `<th>标签</th><td>值</td>` 的表格。注意两个坑：
     *
     * 1. 有的 `<tr>` **没有闭合标签**（`<tr><th>题名</th><td>…` 后直接跟下一个 `<tr>`），
     *    jsoup 会按 HTML 规则自动修。修完 `tr` 里可能塞了两个 `th/td`，
     *    所以取值要按「成对扫描」而不是「取第 0 个 th 和第 0 个 td」。
     * 2. 每本书末尾有 `tr[style*=display:none]`（续借结果/续借时间）和
     *    `tr.renewParam`（JSON 参数），这些**不是**业务字段，必须跳过，
     *    否则会把 `{"barcode":"…"}` 当成续借次数。
     */
    fun parseItems(html: String?): List<Item> {
        if (html.isNullOrBlank()) return emptyList()

        val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: return emptyList()

        return doc.select("div.tableLib").mapNotNull { block ->
            val kv = mutableMapOf<String, String>()

            // 精确到本块内的表格行，避免把整个页面的行混进来
            block.select("table tr").forEach { tr ->
                if (tr.className().contains("renewParam")) return@forEach
                if (tr.attr("style").replace(" ", "").contains("display:none")) return@forEach

                val cells = tr.children().filter { it.tagName() == "th" || it.tagName() == "td" }
                var i = 0
                while (i + 1 < cells.size) {
                    val label = cleanLabel(cells[i].text())
                    val field = ITEM_FIELDS[label]
                    if (field != null) {
                        kv.putIfAbsent(field, cells[i + 1].text().trim())
                        i += 2
                    } else {
                        i++
                    }
                }
            }

            val title = kv["title"]?.takeIf { it.isNotEmpty() }
            val barcode = kv["barcode"]?.takeIf { it.isNotEmpty() }
            // 一本书至少要有书名或条码，否则这只是一段空表格
            if (title == null && barcode == null) return@mapNotNull null

            Item(
                barcode = barcode,
                title = title,
                borrowDate = kv["borrowDate"]?.takeIf { it.isNotEmpty() },
                dueDate = kv["dueDate"]?.takeIf { it.isNotEmpty() },
                renewCount = kv["renewCount"]?.let { INT_UP_TO_3.find(it)?.value?.toIntOrNull() },
            )
        }
    }

    /**
     * 解析 HTML。
     *
     * @return 解析不出任何字段时返回 null（调用方退化到展示原始文本）
     */
    fun parse(html: String?): Summary? {
        if (html.isNullOrBlank()) return null

        // 先尝试 DOM 结构化路径（含 <dl>/<td>/标签-值对）
        val fromDom = runCatching { parseDom(Jsoup.parse(html)) }.getOrNull()

        // 再对纯文本做正则兜底（老页面常把值塞进无标签的文本节点）
        val text = HtmlText.stripTags(html, maxChars = 20000)
        val fromText = parseText(text)

        val merged = Summary(
            currentBorrowed = fromDom?.currentBorrowed ?: fromText.currentBorrowed,
            borrowable = fromDom?.borrowable ?: fromText.borrowable,
            historyTotal = fromDom?.historyTotal ?: fromText.historyTotal,
            dueDate = fromDom?.dueDate ?: fromText.dueDate,
            unpaid = fromDom?.unpaid ?: fromText.unpaid,
            readerMasked = fromDom?.readerMasked ?: fromText.readerMasked,
            renewCount = fromDom?.renewCount ?: fromText.renewCount,
            items = parseItems(html),
        )

        return merged.takeUnless { it.isEmpty }
    }

    /** 纯文本路径：直接在归一化文本上跑正则。 */
    fun parseText(text: String): Summary {
        val norm = normalize(text)
        return Summary(
            currentBorrowed = CURRENT.find(norm)?.groupValues?.get(1)?.toIntOrNull(),
            borrowable = BORROWABLE.find(norm)?.groupValues?.get(1)?.toIntOrNull(),
            historyTotal = HISTORY.find(norm)?.groupValues?.get(1)?.toIntOrNull(),
            dueDate = (DUE.find(norm) ?: DUE_LOOSE.find(norm))?.groupValues?.get(1),
            unpaid = UNPAID.find(norm)?.groupValues?.get(1),
            readerMasked = READER.find(norm)?.groupValues?.get(1)?.let { maskReader(it) },
            renewCount = RENEW.find(norm)?.groupValues?.get(1)?.toIntOrNull(),
        )
    }

    /**
     * DOM 路径：把 `<dt>/<dd>`、`<th>/<td>`、`标签<span>值</span>` 三类结构
     * 统一成「标签 → 值」映射，再按业务词取值。
     */
    private fun parseDom(doc: Document): Summary {
        val pairs = mutableMapOf<String, String>()

        // <dl><dt>当前借阅</dt><dd>3</dd></dl>
        doc.select("dt").forEach { dt ->
            val dd = dt.nextElementSibling()
            if (dd != null && dd.tagName() == "dd") {
                pairs[cleanLabel(dt.text())] = dd.text().trim()
            }
        }

        // <tr><th>当前借阅</th><td>3</td></tr>
        doc.select("tr").forEach { tr ->
            val cells = tr.children().filter { it.tagName() == "th" || it.tagName() == "td" }
            if (cells.size >= 2) {
                pairs[cleanLabel(cells[0].text())] = cells[1].text().trim()
            }
        }

        // <li><span class="label">当前借阅</span><span class="value">3</span></li>
        doc.select("li, div").forEach { el: Element ->
            val kids = el.children()
            if (kids.size == 2) {
                val label = cleanLabel(kids[0].text())
                val value = kids[1].text().trim()
                if (label.isNotEmpty() && value.isNotEmpty() && label.length <= 12) {
                    pairs.putIfAbsent(label, value)
                }
            }
        }

        fun lookup(vararg keys: String): String? {
            keys.forEach { k ->
                pairs.entries.firstOrNull { it.key.contains(k) }?.let { return it.value }
            }
            return null
        }

        // 阿法迪模板把值放在固定 class 上，比按文案定位更稳，优先取。
        // 实测：<h1>目前借书1本</h1>、<td class="dueDate">、<td class="renewCount">
        val dueByClass = doc.selectFirst("td.dueDate, .dueDate")?.text()?.trim()
        val renewByClass = doc.selectFirst("td.renewCount, .renewCount")?.text()?.trim()
        val headline = doc.selectFirst("header .num h1, .num h1, h1")?.text()?.trim()

        return Summary(
            currentBorrowed = headline?.let { CURRENT.find(normalize(it))?.groupValues?.get(1)?.toIntOrNull() }
                ?: lookup("当前借阅", "目前借书", "在借")?.extractInt(),
            borrowable = lookup("可借")?.extractInt(),
            historyTotal = lookup("借阅历史", "历史借阅")?.extractInt(),
            dueDate = dueByClass?.extractDate()
                ?: lookup("应还日期", "应还", "到期")?.extractDate(),
            unpaid = lookup("欠费", "逾期费")?.let { if (it.contains("无")) "无" else it.extractNumber() },
            readerMasked = lookup("读者证", "借书证", "证号")?.let { maskReader(it.trim()) },
            renewCount = renewByClass?.extractInt()
                ?: lookup("续借次数")?.extractInt(),
        )
    }

    private fun cleanLabel(raw: String): String =
        raw.replace(LABEL_NOISE, "").trim()

    private fun normalize(text: String): String =
        text.replace('\u00A0', ' ')
            .replace(WHITESPACE_RUN, " ")
            .trim()

    private fun String.extractInt(): Int? =
        INT_UP_TO_5.find(this)?.value?.toIntOrNull()

    private fun String.extractNumber(): String? =
        NUMBER.find(this)?.value

    private fun String.extractDate(): String? =
        DATE_CN.find(this)?.value

    /**
     * 读者证号脱敏。
     *
     * 红线要求日志里不出现完整凭据；读者证号虽然不算密码，
     * 但它是学号，属于个人信息，展示与日志都应打码。
     */
    fun maskReader(raw: String): String {
        val s = raw.trim()
        return when {
            s.length <= 4 -> "*".repeat(s.length)
            s.length <= 8 -> s.take(2) + "*".repeat(s.length - 4) + s.takeLast(2)
            else -> s.take(4) + "*".repeat((s.length - 6).coerceAtLeast(2)) + s.takeLast(2)
        }
    }
}
