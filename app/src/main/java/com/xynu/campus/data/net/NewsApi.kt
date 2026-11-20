package com.xynu.campus.data.net

import com.xynu.campus.data.model.NewsItem
import com.xynu.campus.data.parser.NewsParser
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 校园资讯聚合：教务处动态 + 图书馆公告（WebPlus 站群）。
 */
class NewsApi(
    private val client: OkHttpClient,
    private val parser: NewsParser = NewsParser
) {
    suspend fun fetchJwcNews(): List<NewsItem> {
        val html = get("http://jwc.xynu.edu.cn/jwdt.htm")
        return parser.parseNewsList(html, "教务处")
    }

    suspend fun fetchJwcNotices(): List<NewsItem> {
        val html = get("http://jwc.xynu.edu.cn/list.jsp?urltype=tree.TreeTempUrl&wbtreeid=1033")
        return parser.parseNewsList(html, "考评通知")
    }

    suspend fun fetchLibNews(): List<NewsItem> {
        val html = get("http://lib.xynu.edu.cn/")
        return parser.parseLibNews(html)
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            return body
        }
    }

    companion object {
        const val UA = HttpClientFactory.UA_PC
    }
}