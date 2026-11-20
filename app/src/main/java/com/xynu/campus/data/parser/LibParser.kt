package com.xynu.campus.data.parser

import com.xynu.campus.data.model.BookDetail
import com.xynu.campus.data.model.BookHolding
import com.xynu.campus.data.model.BookItem
import com.xynu.campus.data.model.BorrowHistoryItem
import com.xynu.campus.data.model.BorrowItem
import org.jsoup.Jsoup
import org.json.JSONArray
import org.json.JSONObject

/**
 * 图书馆 OPAC（阿法迪 goldlib）HTML/JSON 解析器。
 */
object LibParser {

    /**
     * 解析检索结果页。
     * 结构: div.jp-searchList > ul > li: h2 > a[href=/opac/book/{id}] 标题
     *       div.jp-booksInfo > p.creator 作者 | p.call_number 索书号 | p.isbn | p.publisher
     *       input[name=sourceId][value="{id}/图书"]
     */
    fun parseSearchResults(html: String): List<BookItem> {
        val doc = Jsoup.parse(html)
        val items = mutableListOf<BookItem>()
        val lis = doc.select("div.jp-searchList li")
        for (li in lis) {
            try {
                val titleLink = li.select("h2 a").firstOrNull() ?: continue
                val title = titleLink.text().trim()
                if (title.isEmpty()) continue
                val href = titleLink.attr("href")
                val detailUrl = if (href.startsWith("http")) href else "http://222.21.62.201:8011$href"

                val info = li.select(".jp-booksInfo").firstOrNull()
                val author = info?.select("p.creator a")?.firstOrNull()?.text()?.trim()
                    ?: info?.select("p.creator")?.text()?.trim()?.removePrefix("—")?.trim() ?: ""
                val callNumber = info?.select("p.call_number")?.text()?.trim()
                    ?.removePrefix("索书号：")?.trim() ?: ""
                val isbn = info?.select("p.isbn")?.text()?.trim()
                    ?.removePrefix("标准编码：")?.trim() ?: ""
                val publisher = info?.select("p.publisher")?.text()?.trim()
                    ?.removePrefix("出版信息：")?.trim() ?: ""

                val sourceId = li.select("input[name=sourceId]").firstOrNull()?.attr("value") ?: ""
                val id = sourceId.substringBefore("/").ifEmpty {
                    Regex("^/opac/book/([^/]+)").find(href)?.groupValues?.get(1) ?: ""
                }
                val countText = li.select("p.libraryCount span").text()

                items += BookItem(
                    id = id,
                    title = title,
                    author = author,
                    callNumber = callNumber,
                    isbn = isbn,
                    publisher = publisher,
                    detailUrl = detailUrl,
                    holdingCount = countText,
                    canBorrowCount = ""
                )
            } catch (_: Exception) { }
        }
        return items.distinctBy { it.id }
    }

    /**
     * 解析馆藏 JSON（getHoldingsInformation/{id} 返回数组）
     * 数组项字段（中文键）：索书号 / 条形码 / 部门名称 / 架位号 / 书刊状态
     */
    fun parseHoldings(json: String): List<BookHolding> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            val list = ArrayList<BookHolding>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list += BookHolding(
                    callNumber = o.optString("索书号"),
                    barcode = o.optString("条形码"),
                    location = o.optString("部门名称"),
                    status = o.optString("书刊状态")
                )
            }
            list
        }.getOrDefault(emptyList())
    }

    /**
     * 解析"我的图书馆"借阅列表。
     * 阿法迪典型结构: table 每行含 题名/责任者/借书日期/应还日期/续借/状态, 续借按钮 form action=/opac/book/renew
     */
    fun parseBorrowList(html: String): List<BorrowItem> {
        val doc = Jsoup.parse(html)
        val items = mutableListOf<BorrowItem>()
        // 常见容器
        val tables = doc.select("table")
        for (table in tables) {
            if (!table.text().contains("应还") && !table.text().contains("借书")) continue
            val rows = table.select("tr")
            for (tr in rows) {
                val tds = tr.select("td")
                if (tds.size < 3) continue
                val title = tds.getOrNull(0)?.text()?.trim() ?: continue
                if (title.isEmpty() || title.contains("题名") || title.contains("书名")) continue
                val author = tds.getOrNull(1)?.text()?.trim() ?: ""
                val borrowDate = tds.getOrNull(2)?.text()?.trim() ?: ""
                val dueDate = tds.getOrNull(3)?.text()?.trim() ?: ""
                val renewCount = tds.getOrNull(4)?.text()?.trim() ?: "0"
                val status = tds.getOrNull(5)?.text()?.trim() ?: ""
                val barcode = tr.select("input[name=barcode]").firstOrNull()?.attr("value")
                    ?: tr.select("input[name^=renew]").firstOrNull()?.attr("value") ?: ""
                val renewForm = tr.select("form[action*=renew], form[action*=renewInfo]").firstOrNull()
                val renewUrl = renewForm?.attr("action")

                items += BorrowItem(
                    title = title,
                    author = author,
                    borrowDate = borrowDate,
                    dueDate = dueDate,
                    renewCount = renewCount,
                    status = status,
                    barcode = barcode,
                    renewUrl = renewUrl
                )
            }
        }
        return items
    }

    /** 解析读者借阅概要：返回 (读者名, 借阅数) */
    fun parseReaderInfo(html: String): Pair<String, Int> {
        val doc = Jsoup.parse(html)
        val text = doc.body()?.text() ?: ""
        val nameMatch = Regex("(?:读者|用户)[:：]\\s*([\\u4e00-\\u9fa5A-Za-z]+)").find(text)
        val countMatch = Regex("(?:当前借阅|在借)\\s*[\\u4e00-\\u9fa5]{0,6}?\\s*?(\\d+)\\s*(?:本|册)").find(text)
        return Pair(
            nameMatch?.groupValues?.get(1) ?: "",
            countMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
        )
    }

    /**
     * 解析借阅历史（已归还）。阿法迪典型结构：table 每行 题名/责任者/借书日期/归还日期/馆藏地
     */
    fun parseBorrowHistory(html: String): List<BorrowHistoryItem> {
        val doc = Jsoup.parse(html)
        val items = mutableListOf<BorrowHistoryItem>()
        val tables = doc.select("table")
        for (table in tables) {
            val head = table.text()
            // 必须包含归还/还书字样，且不是当前借阅表
            if (!(head.contains("归还") || head.contains("还书"))) continue
            val rows = table.select("tr")
            for (tr in rows) {
                val tds = tr.select("td")
                if (tds.size < 3) continue
                val title = tds.getOrNull(0)?.text()?.trim() ?: continue
                if (title.isEmpty() || title.contains("题名") || title.contains("书名")) continue
                items += BorrowHistoryItem(
                    title = title,
                    author = tds.getOrNull(1)?.text()?.trim() ?: "",
                    borrowDate = tds.getOrNull(2)?.text()?.trim() ?: "",
                    returnDate = tds.getOrNull(3)?.text()?.trim() ?: "",
                    location = tds.getOrNull(4)?.text()?.trim() ?: "",
                    barcode = tr.select("input[name=barcode]").firstOrNull()?.attr("value") ?: ""
                )
            }
        }
        return items
    }

    /**
     * 解析书目详情页 /opac/book/{id}。
     * 阿法迪典型结构： div.bookinfo / table，字段以"题名/责任者/出版发行/载体形态/ISBN/主题/提要/目录"等 label 标识
     */
    fun parseBookDetail(html: String, bookId: String): BookDetail {
        val doc = Jsoup.parse(html)

        // 取第一个 h1/h2 作为题名的兜底；再尝试表格 label
        var title = doc.select("h1, h2").firstOrNull()?.text()?.trim().orEmpty()
        var author = ""
        var callNumber = ""
        var isbn = ""
        var publisher = ""
        var publishDate = ""
        var pages = ""
        var price = ""
        var subject = ""
        var summary = ""
        var toc = ""
        var language = ""
        var category = ""

        // 阿法迪图书详情页用 dl/dt/dd 或 table 行承载字段
        // 策略：扫所有 tr / li / div，找含中文 label 的节点
        val candidates = doc.select("tr, li, div.item, div.field, p")
        for (el in candidates) {
            val text = el.text()?.trim() ?: continue
            if (text.length > 800) continue
            when {
                text.startsWith("题名") || text.startsWith("书名") -> {
                    if (title.isEmpty()) title = text.substringAfter("：").substringAfter(":").trim()
                }
                text.startsWith("责任者") || text.startsWith("作者") -> {
                    if (author.isEmpty()) author = text.substringAfter("：").substringAfter(":").trim()
                }
                text.startsWith("索书号") -> {
                    if (callNumber.isEmpty()) callNumber = text.substringAfter("：").substringAfter(":").trim()
                }
                text.startsWith("ISBN") || text.startsWith("标准编码") -> {
                    if (isbn.isEmpty()) isbn = text.substringAfter("：").substringAfter(":").trim()
                }
                text.startsWith("出版发行") || text.startsWith("出版信息") || text.startsWith("出版社") -> {
                    if (publisher.isEmpty()) publisher = text.substringAfter("：").substringAfter(":").trim()
                }
                text.startsWith("出版时间") || text.startsWith("出版日期") -> {
                    if (publishDate.isEmpty()) publishDate = text.substringAfter("：").substringAfter(":").trim()
                }
                text.startsWith("载体形态") || text.startsWith("页数") -> {
                    if (pages.isEmpty()) pages = text.substringAfter("：").substringAfter(":").trim()
                }
                text.startsWith("价格") || text.startsWith("定价") -> {
                    if (price.isEmpty()) price = text.substringAfter("：").substringAfter(":").trim()
                }
                text.startsWith("主题") || text.startsWith("主题词") -> {
                    if (subject.isEmpty()) subject = text.substringAfter("：").substringAfter(":").trim()
                }
                text.startsWith("提要") || text.startsWith("内容简介") || text.startsWith("摘要") -> {
                    if (summary.isEmpty()) summary = text.substringAfter("：").substringAfter(":").trim()
                }
                text.startsWith("目录") -> {
                    if (toc.isEmpty()) toc = text.substringAfter("：").substringAfter(":").trim()
                }
                text.startsWith("语种") -> {
                    if (language.isEmpty()) language = text.substringAfter("：").substringAfter(":").trim()
                }
                text.startsWith("中图分类") || text.startsWith("分类号") -> {
                    if (category.isEmpty()) category = text.substringAfter("：").substringAfter(":").trim()
                }
            }
        }

        return BookDetail(
            id = bookId,
            title = title,
            author = author,
            callNumber = callNumber,
            isbn = isbn,
            publisher = publisher,
            publishDate = publishDate,
            pages = pages,
            price = price,
            subject = subject,
            summary = summary,
            toc = toc,
            language = language,
            category = category
        )
    }
}