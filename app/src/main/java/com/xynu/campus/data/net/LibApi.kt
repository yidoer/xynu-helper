package com.xynu.campus.data.net

import com.xynu.campus.data.crypto.AesEcbCrypto
import com.xynu.campus.data.model.BookDetail
import com.xynu.campus.data.model.BookHolding
import com.xynu.campus.data.model.BookItem
import com.xynu.campus.data.model.BorrowHistoryItem
import com.xynu.campus.data.model.BorrowItem
import com.xynu.campus.data.model.ReaderStats
import com.xynu.campus.data.parser.LibParser
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 图书馆 OPAC（阿法迪 goldlib）客户端。
 * 协议调研结论：
 *  - 登录: POST /pages/include/checklogin.jsp, 密码 AES-128-ECB 加密（密钥 goldlib@2306!@#$，PKCS7）
 *  - 检索: POST /opac/search (q/field/searchModel/corename)
 *  - 馆藏: GET /opac/book/getHoldingsInformation/{bookId}
 *  - 书目详情: GET /opac/book/{id}
 *  - 我的借阅: GET /opac/mylibrary
 *  - 借阅历史: GET /opac/mylibrary/history（阿法迪常见路径，失败则返回空）
 *  - 续借: POST /opac/book/renew
 */
class LibApi(
    private val client: OkHttpClient,
    private val parser: LibParser = LibParser
) {
    val base = "http://222.21.62.201:8011"

    private fun get(url: String, requireSession: Boolean = false): String {
        val builder = Request.Builder().url(url).get()
        if (requireSession) builder.requireSession(SessionKind.LIB)
        client.newCall(builder.build()).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            return body
        }
    }

    private fun getOrNull(url: String, requireSession: Boolean = false): String? {
        return try {
            get(url, requireSession)
        } catch (e: SessionExpiredException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun post(url: String, form: FormBody, requireSession: Boolean = false): String {
        val builder = Request.Builder().url(url).post(form)
        if (requireSession) builder.requireSession(SessionKind.LIB)
        client.newCall(builder.build()).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            return body
        }
    }

    /** 图书馆登录。返回错误信息；null 表示成功 */
    suspend fun login(username: String, password: String): String? {
        val encrypted = AesEcbCrypto.encrypt(password, AesEcbCrypto.LIB_KEY)
        val form = FormBody.Builder()
            .add("username", username)
            .add("password", encrypted)
            .add("loginType", "barCode")
            .add("validCode", "")
            .build()
        val body = post("$base/pages/include/checklogin.jsp", form)
        return if (body.trim() == "ok") null else body.trim().ifEmpty { "登录失败，请检查账号密码" }
    }

    /** 馆藏检索 */
    suspend fun searchBooks(query: String, field: String = "title", page: Int = 1): List<BookItem> {
        val form = FormBody.Builder()
            .add("q", query)
            .add("field", field)
            .add("searchModel", "contain")
            .add("corename", "")
            .add("title", "")
            .add("page", page.toString())
            .build()
        val html = post("$base/opac/search", form)
        return parser.parseSearchResults(html)
    }

    /** 获取某本书的馆藏信息 */
    suspend fun getHoldings(bookId: String): List<BookHolding> {
        val json = get("$base/opac/book/getHoldingsInformation/$bookId")
        return parser.parseHoldings(json)
    }

    /** 图书详情页 + 馆藏 */
    suspend fun getBookDetail(bookId: String): BookDetail {
        val html = get("$base/opac/book/$bookId")
        val holdings = try { getHoldings(bookId) } catch (_: Exception) { emptyList() }
        return parser.parseBookDetail(html, bookId).copy(holdings = holdings)
    }

    /** 我的借阅（需登录） */
    suspend fun myBorrowed(): List<BorrowItem> {
        val html = get("$base/opac/mylibrary", requireSession = true)
        return parser.parseBorrowList(html)
    }

    /** 借阅历史（已归还）。阿法迪常见路径，缺失则返回空 */
    suspend fun borrowHistory(): List<BorrowHistoryItem> {
        // 尝试几个常见的历史借阅路径
        val candidates = listOf(
            "$base/opac/mylibrary/history",
            "$base/opac/mylibrary/borrowHistory",
            "$base/opac/mylibrary/loanHistory",
            "$base/opac/reader/history"
        )
        for (url in candidates) {
            val html = getOrNull(url, requireSession = true) ?: continue
            if (html.contains("登录") && html.contains("密码")) {
                throw SessionExpiredException(SessionKind.LIB, base.removePrefix("http://").substringBefore('/'))
            }
            val list = try {
                parser.parseBorrowHistory(html)
            } catch (_: Exception) {
                emptyList()
            }
            if (list.isNotEmpty()) return list
        }
        return emptyList()
    }

    /** 读者信息（借阅中/应还） */
    suspend fun readerInfo(): Pair<String, Int> {
        val html = get("$base/opac/mylibrary", requireSession = true)
        return parser.parseReaderInfo(html)
    }

    /** 综合统计：合并 mylibrary 与 history，返回读者整体状态 */
    suspend fun readerStats(): ReaderStats {
        val borrowed = try {
            myBorrowed()
        } catch (e: SessionExpiredException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        val history = try {
            borrowHistory()
        } catch (e: SessionExpiredException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        val (name, _) = try {
            readerInfo()
        } catch (e: SessionExpiredException) {
            throw e
        } catch (_: Exception) {
            Pair("", 0)
        }

        val today = java.util.Calendar.getInstance()
        var overdue = 0
        var soon = 0
        for (item in borrowed) {
            val due = parseDate(item.dueDate) ?: continue
            val diffDays = ((due.timeInMillis - today.timeInMillis) / (24L * 3600 * 1000)).toInt()
            when {
                diffDays < 0 -> overdue++
                diffDays <= 7 -> soon++
            }
        }
        return ReaderStats(
            name = name,
            borrowCount = borrowed.size,
            historyCount = history.size,
            overdueCount = overdue,
            soonDueCount = soon
        )
    }

    private fun parseDate(s: String): java.util.Calendar? {
        if (s.isBlank()) return null
        val parts = s.trim().split("-", "/", ".")
        if (parts.size < 3) return null
        return try {
            java.util.Calendar.getInstance().apply {
                set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt(), 0, 0, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 续借。
     *  优先使用解析出的 renewUrl；否则用标准端点（阿法迪 getHoldingsInformation 同族）。
     *  返回服务端提示文本。
     */
    suspend fun renew(barcode: String, bookId: String, renewUrl: String? = null): String {
        val url = when {
            !renewUrl.isNullOrBlank() && renewUrl.startsWith("http") -> renewUrl
            !renewUrl.isNullOrBlank() && renewUrl.startsWith("/") -> "$base$renewUrl"
            else -> "$base/opac/mylibrary/renew"
        }
        val form = FormBody.Builder()
            .add("barcode", barcode)
            .add("bookId", bookId)
            .add("userType", "IN")
            .build()
        val req = Request.Builder()
            .url(url)
            .post(form)
            .requireSession(SessionKind.LIB)
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            // 返回 JSON 如 {"success":true,"msg":"续借成功"} 或纯文本
            val t = body.trim()
            return if (t.startsWith("{")) {
                try {
                    val o = org.json.JSONObject(t)
                    val msg = o.optString("msg").ifEmpty { o.optString("message") }
                    if (o.optBoolean("success", false)) msg.ifEmpty { "续借成功" } else msg.ifEmpty { "续借失败" }
                } catch (_: Exception) { t }
            } else t
        }
    }
}
