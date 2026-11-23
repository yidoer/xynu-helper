package com.xynu.campus.library

import com.xynu.campus.library.net.CatalogRepository
import com.xynu.campus.library.parser.CatalogDetailParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 书目详情解析器回归测试（真实 fixture，`POST /search/bookDetail` 抓的原文）。
 *
 * 这件事的重要性：**「在馆几本、在哪个书库」是决定用户能不能借到书的关键信息**，
 * 而这些字段只有详情页有。原先详情走 `222.21.62.201:8011`，那台机器只对校园网开放，
 * 用户连热点时直接 15 秒超时。现在详情改走 `libopac.xynu.edu.cn:8080`（内外网都通），
 * 所以这里的解析必须在**真实响应**上守住，而不是在手工拼的 HTML 上。
 */
class CatalogDetailParserTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("fixtures/$name")!!
            .bufferedReader().readText()

    @Test
    fun `parses real detail page for 大白话讲透逻辑学`() {
        val d = CatalogDetailParser.parse(fixture("catalog_detail.html"))

        assertEquals("大白话讲透逻辑学", d.title)
        assertNotNull("必须解析出作者（题名责任者项里带的）", d.author)
        assertTrue("作者应含丁毓峰，实际：${d.author}", d.author!!.contains("丁毓峰"))

        assertEquals("978-7-122-47467-4", d.isbn)
        assertEquals("CNY52.00", d.price)

        assertNotNull("必须解析出出版社", d.publisher)
        assertTrue("出版社应含化学工业出版社，实际：${d.publisher}", d.publisher!!.contains("化学工业出版社"))
        assertEquals("2026.3", d.publishDate)

        assertNotNull("必须解析出载体形态", d.physicalForm)
        assertTrue(d.physicalForm!!.contains("266页"))

        assertNotNull("必须解析出内容简介", d.summary)
        assertTrue("简介应提到逻辑", d.summary!!.contains("逻辑"))

        assertNotNull("必须解析出主题词", d.subject)
        assertTrue(d.subject!!.contains("逻辑学"))

        assertEquals("B81-49", d.clcClass)
    }

    @Test
    fun `parses holdings so users can tell whether the book is on the shelf`() {
        val d = CatalogDetailParser.parse(fixture("catalog_detail.html"))

        assertEquals("这本书只有一个复本", 1, d.holdings.size)
        val h = d.holdings.first()
        assertEquals("23364954", h.barcode)
        assertEquals("B81-49/D591", h.callNo)
        assertEquals("社科书库一", h.library)
        assertEquals("在架", h.status)
        assertTrue("「在架」必须判定为可借", h.isAvailable)
        assertEquals(1, d.availableCount)
        assertFalse(d.isEmpty)
    }

    @Test
    fun `parses second real detail page with different field set`() {
        // 换一本书：字段集合不同（多了「丛编项」「一般附注」，没有「个人名称等同」），
        // 用来验证解析器不是靠字段顺序/固定下标在猜。
        val d = CatalogDetailParser.parse(fixture("catalog_detail_2.html"))

        assertEquals("文化主体性的中国逻辑", d.title)
        assertTrue("作者应含韩庆祥，实际：${d.author}", d.author!!.contains("韩庆祥"))
        assertEquals("978-7-300-34231-3", d.isbn)
        assertTrue(d.publisher!!.contains("中国人民大学出版社"))
        assertEquals("2026.3", d.publishDate)
        assertTrue("必须解析出丛编项", d.series!!.contains("认识中国"))
        assertEquals("D61 K203", d.clcClass)

        assertEquals(1, d.holdings.size)
        assertEquals("社科书库三", d.holdings.first().library)
        assertEquals("D61/H097:3", d.holdings.first().callNo)
    }

    @Test
    fun `multiple holding copies are all collected`() {
        // 多分馆/多复本是常态，只取第一个会让用户白跑一趟。
        val html = """
            <html><body>
            <div class="tit"><h1>测试书</h1></div>
            <div class="tableLib"><table>
              <tr><th>条码号</th><td>A1</td></tr>
              <tr><th>索书号</th><td>I1</td></tr>
              <tr><th>藏馆</th><td>书库一</td></tr>
              <tr><th>馆藏状态</th><td>在架</td></tr>
            </table></div>
            <div class="tableLib"><table>
              <tr><th>条码号</th><td>A2</td></tr>
              <tr><th>索书号</th><td>I1</td></tr>
              <tr><th>藏馆</th><td>书库二</td></tr>
              <tr><th>馆藏状态</th><td>借出</td></tr>
            </table></div>
            </body></html>
        """.trimIndent()

        val d = CatalogDetailParser.parse(html)
        assertEquals(2, d.holdings.size)
        assertEquals(1, d.availableCount)
        assertTrue(d.holdings[0].isAvailable)
        assertFalse(d.holdings[1].isAvailable)
    }

    @Test
    fun `blank or garbage html yields empty detail instead of throwing`() {
        listOf("", "   ", "<html></html>", "not html at all", "<div class='catalog'></div>")
            .forEach { assertTrue("不该抛异常，且应判为空：${it.take(20)}", CatalogDetailParser.parse(it).isEmpty) }
    }

    @Test
    fun `title falls back to title responsibility field when h1 missing`() {
        val html = """
            <html><body><div class="catalog">
              <p>题名责任者项：没有标题的怪页面/.某某著</p>
            </div></body></html>
        """.trimIndent()
        val d = CatalogDetailParser.parse(html)
        assertEquals("没有标题的怪页面", d.title)
        assertEquals("某某著", d.author)
    }

    @Test
    fun `isbn and price are split correctly and price may be absent`() {
        val withPrice = CatalogDetailParser.parse(
            "<html><body><div class='catalog'><p>ISBN/价格：978-7-111-11111-1:CNY99.00</p></div></body></html>",
        )
        assertEquals("978-7-111-11111-1", withPrice.isbn)
        assertEquals("CNY99.00", withPrice.price)

        val noPrice = CatalogDetailParser.parse(
            "<html><body><div class='catalog'><p>ISBN/价格：978-7-111-11111-1</p></div></body></html>",
        )
        assertEquals("978-7-111-11111-1", noPrice.isbn)
        assertNull("没有价格时不该编造一个", noPrice.price)
    }

    // ---------- 仓库层：id 换算与字段枚举 ----------

    @Test
    fun `book id is extracted from the detail url returned by search`() {
        val url = "http://222.21.62.201:8011/opac/book/5f01bdf949e01a729cb9c52c164f2f1c"
        assertEquals("5f01bdf949e01a729cb9c52c164f2f1c", CatalogRepository.bookIdOf(url))
        assertNull(CatalogRepository.bookIdOf(null))
        assertNull(CatalogRepository.bookIdOf(""))
        assertNull("不像详情的地址不该硬抠出东西", CatalogRepository.bookIdOf("http://x/foo"))
    }

    @Test
    fun `search type constants match the values the entry page actually sends`() {
        // 入口页 showiphoneSearch.action 的 <a id="..."> 只有这四个；
        // 其中「题名」是默认选中项。传别的值（如 ISBN）服务端不报错但结果为空，
        // 所以 UI 的可选项必须与这里一致，否则用户会「搜不到但不知道为什么」。
        assertEquals("题名", CatalogRepository.SEARCH_TITLE)
        assertEquals("作者", CatalogRepository.SEARCH_AUTHOR)
        assertEquals("主题词", CatalogRepository.SEARCH_SUBJECT)
        assertEquals("索书号", CatalogRepository.SEARCH_CALLNO)
    }
}
