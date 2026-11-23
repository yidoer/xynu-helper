package com.xynu.campus.library

import com.xynu.campus.library.net.LibraryHostAllowList
import com.xynu.campus.library.parser.CatalogSearchParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 馆藏检索解析器 × 真实响应。
 *
 * fixture 来源：外网（手机热点）POST
 *   http://libopac.xynu.edu.cn:8080/search/searchList
 *   kw=逻辑学&xc=3&schoolid=968
 * 的**原样返回**（19796 bytes，共 187 条，本页 10 条）。
 *
 * 这套接口的意义：现有 `LibApi` 打的是 `222.21.62.201:8011`（只对校园网开放，
 * 手机在外网 15s 超时），而这里与借阅同一台机器，**外网可用**。
 */
class CatalogSearchParserTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("fixtures/$name")
            ?.bufferedReader()?.readText()
            ?: error("fixture 缺失：$name")

    @Test
    fun `parses total count from real response`() {
        val page = CatalogSearchParser.parse(fixture("catalog_search.html"))
        assertEquals("共187条搜索结果", 187, page.total)
    }

    @Test
    fun `parses ten books per page from real response`() {
        val page = CatalogSearchParser.parse(fixture("catalog_search.html"))
        assertEquals(10, page.books.size)
        assertTrue("不应为空页", !page.isEmpty)
    }

    @Test
    fun `parses all book fields of first real item`() {
        val first = CatalogSearchParser.parse(fixture("catalog_search.html")).books.first()
        assertEquals("大白话讲透逻辑学", first.title)
        assertEquals("丁毓峰编著", first.author?.replace("— ", ""))
        assertNotNull("索书号应解析出来", first.callNo)
        assertTrue("索书号应形如 B81-49/xxx", first.callNo!!.startsWith("B81"))
        assertEquals("978-7-122-47467-4", first.isbn)
        assertEquals("中文图书", first.mediaType)
        assertNotNull("出版社应解析出来", first.publisher)
        assertNotNull("详情地址应解析出来", first.detailUrl)
    }

    @Test
    fun `detail url points at the intranet only host`() {
        // 这是本接口的真实边界：列表能在外网拿到，但详情页在 222.21.62.201，
        // 外网不可达。测试把它钉住，避免以后误以为点进去一定能打开。
        val first = CatalogSearchParser.parse(fixture("catalog_search.html")).books.first()
        val detail = first.detailUrl!!
        assertTrue("详情在 .201:8011（内网 only）", detail.contains("222.21.62.201:8011"))
        assertTrue("详情地址不在模块白名单内", !LibraryHostAllowList.isAllowed(detail))
    }

    @Test
    fun `second page differs from first page`() {
        val p1 = CatalogSearchParser.parse(fixture("catalog_search.html"), 1)
        val p2 = CatalogSearchParser.parse(fixture("catalog_search_p2.html"), 2)
        assertEquals(10, p2.books.size)
        assertEquals("第2页应从第11条开始", "逻辑学导论:推理、论证与批判性思维", p2.books.first().title)
        assertTrue(
            "两页书名集合不应相同",
            p1.books.map { it.title }.intersect(p2.books.map { it.title }.toSet()).isEmpty(),
        )
    }

    @Test
    fun `has next page is true when total exceeds one page`() {
        val page = CatalogSearchParser.parse(fixture("catalog_search.html"), 1)
        assertTrue("187 条 / 每页 10 条 → 还有下一页", page.hasNext)
    }

    @Test
    fun `empty or garbage input yields empty page instead of throwing`() {
        assertEquals(0, CatalogSearchParser.parse("").books.size)
        assertEquals(0, CatalogSearchParser.parse("<html><body>无结果</body></html>").books.size)
        assertNull(CatalogSearchParser.parse("<html><body>无结果</body></html>").total)
    }

    @Test
    fun `book without title is skipped`() {
        // 页面上还有别的 <li>（导航/页脚），没有 title 的一律跳过
        val html = """
            <html><body><ul>
              <li><input type="hidden" name="detailParam" value='{}'/></li>
              <li><input type="hidden" name="title" value='有效书名'/>
                  <div class="detail"><p>索书号：A1/B2</p></div></li>
            </ul></body></html>
        """.trimIndent()
        val page = CatalogSearchParser.parse(html)
        assertEquals(1, page.books.size)
        assertEquals("有效书名", page.books.first().title)
        assertEquals("A1/B2", page.books.first().callNo)
    }
}
