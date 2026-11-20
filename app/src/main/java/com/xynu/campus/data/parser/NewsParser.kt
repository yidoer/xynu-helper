package com.xynu.campus.data.parser

import com.xynu.campus.data.model.NewsItem
import org.jsoup.Jsoup

/**
 * WebPlus 站群新闻列表解析。
 * 列表项结构: <li><span>yyyy-MM-dd</span><a href="..." title="标题">标题</a></li>
 * 只解析带日期前缀的新闻条目，避免把导航菜单等链接误当新闻。
 */
object NewsParser {

    private val DATE_REGEX = Regex("""\d{4}[-/.]\d{1,2}[-/.]\d{1,2}|\d{1,2}[-/.]\d{1,2}""")

    /** 教务/站群通用列表解析：取 li>span(日期)+a(标题) 结构 */
    fun parseNewsList(html: String, source: String): List<NewsItem> {
        val doc = Jsoup.parse(html)
        val items = mutableListOf<NewsItem>()
        doc.select("li").forEach { li ->
            val dateSpan = li.children().firstOrNull()?.takeIf { it.tagName() == "span" } ?: return@forEach
            val date = dateSpan.text().trim()
            // 必须带日期（yyyy-MM-dd 或 MM-dd），过滤导航菜单
            if (!DATE_REGEX.matches(date)) return@forEach

            val a = li.select("a[href]").firstOrNull() ?: return@forEach
            val title = a.attr("title").ifEmpty { a.text() }.trim()
            if (title.isEmpty() || title.length < 4) return@forEach
            if (title.contains("版权所有") || title.contains("©")) return@forEach

            val href = a.attr("href")
            if (href.startsWith("javascript")) return@forEach
            val url = resolveUrl(href)
            if (url.isBlank()) return@forEach

            items += NewsItem(title, date, url, source)
        }
        return items.distinctBy { it.title + it.url }.take(20)
    }

    /** 图书馆首页公告：div.tzgg 下带 info/ 相对路径的新闻链接 */
    fun parseLibNews(html: String): List<NewsItem> {
        val doc = Jsoup.parse(html)
        val items = mutableListOf<NewsItem>()
        doc.select("div.tzgg a[href]").forEach { a ->
            val title = a.attr("title").ifEmpty { a.text() }.trim()
            if (title.isEmpty() || title.length < 4) return@forEach
            if (title.contains("版权所有")) return@forEach
            val href = a.attr("href")
            if (!href.startsWith("info/")) return@forEach
            val url = "http://lib.xynu.edu.cn/${href.removePrefix("/")}"
            items += NewsItem(title, "", url, "图书馆")
        }
        return items.distinctBy { it.title + it.url }.take(20)
    }

    /** 依据站群惯例解析相对链接为教务处完整地址 */
    private fun resolveUrl(href: String): String {
        return when {
            href.startsWith("http://") || href.startsWith("https://") -> href
            href.startsWith("//") -> "http:$href"
            href.startsWith("/") -> "http://jwc.xynu.edu.cn$href"
            href.startsWith("info/") -> "http://jwc.xynu.edu.cn/$href"
            else -> "http://jwc.xynu.edu.cn/$href"
        }
    }
}