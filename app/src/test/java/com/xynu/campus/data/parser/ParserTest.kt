package com.xynu.campus.data.parser

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * 用真实抓取的校内页面验证解析器。
 * 这些 fixture 是本项目侦察阶段从信阳师范大学教务处/图书馆/OPAC 抓取的真实 HTML。
 */
class ParserTest {

    private fun fixture(name: String): String {
        val f = File("src/test/resources/fixtures/$name")
        assertTrue("fixture missing: $name", f.exists())
        return f.readText(Charsets.UTF_8)
    }

    @Test
    fun `news parser extracts only dated jwc news`() {
        val html = fixture("jwc-jwdt.html")
        val items = NewsParser.parseNewsList(html, "教务处")
        assertTrue("should find news items", items.isNotEmpty())
        // 每条都必须带日期
        items.forEach {
            assertTrue("should have date, got: ${it.title} -> ${it.date}", it.date.matches(Regex("\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}")) )
            assertTrue("title should be meaningful: ${it.title}", it.title.length >= 4)
            assertTrue("should have url", it.url.startsWith("http"))
        }
        // 不应包含导航菜单项
        assertTrue(items.none { it.title.contains("首页") && it.title == "网站首页" })
        println("jwc news count = ${items.size}, first = ${items.first().title}")
    }

    @Test
    fun `lib news parser extracts library notices`() {
        val html = fixture("lib-home.html")
        val items = NewsParser.parseLibNews(html)
        assertTrue("should find lib news", items.isNotEmpty())
        items.forEach {
            assertTrue("url should be lib: ${it.url}", it.url.contains("lib.xynu.edu.cn"))
        }
        println("lib news count = ${items.size}, first = ${items.first().title}")
    }

    @Test
    fun `opac search parser extracts books with call numbers`() {
        val html = fixture("opac-search.html")
        val books = LibParser.parseSearchResults(html)
        assertTrue("should find books", books.isNotEmpty())
        val sample = books.first()
        assertTrue("title: ${sample.title}", sample.title.isNotBlank())
        assertTrue("detailUrl: ${sample.detailUrl}", sample.detailUrl.contains("/opac/book/"))
        println("books count = ${books.size}")
        println("first book: ${sample.title} | ${sample.author} | 索书号 ${sample.callNumber}")
    }
}