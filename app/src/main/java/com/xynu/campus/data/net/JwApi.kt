package com.xynu.campus.data.net

import com.xynu.campus.data.crypto.JwRsaCrypto
import com.xynu.campus.data.model.Course
import com.xynu.campus.data.model.JwLoginResult
import com.xynu.campus.data.model.ScoreItem
import com.xynu.campus.data.model.TermOption
import com.xynu.campus.data.parser.JwParser
import java.io.IOException
import java.time.LocalDate
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.jsoup.Jsoup
import org.json.JSONObject

data class FreeClassroomQuery(
    val xnm: String,
    val xqm: String,
    val xqj: Int,
    val section: Int,
    val date: LocalDate,
    val termSource: String,
    val termLabel: String
) {
    val display: String
        get() = "$date ${weekdayLabel(xqj)} · $termLabel（$termSource）· 第${section}节"
}

data class FreeClassroomResult(
    val rooms: List<String>,
    val query: FreeClassroomQuery
)

enum class JwHttpErrorKind {
    INVALID_QUERY,
    SERVER_REJECTED,
}

/** 保留教务非 2xx 响应，避免把业务码误显示成无上下文的通用异常。 */
class JwHttpException(
    val statusCode: Int,
    val endpoint: String,
    val responseBody: String,
) : RuntimeException("HTTP $statusCode") {
    val kind: JwHttpErrorKind
        get() = classifyJwHttpError(statusCode, responseBody)
}

internal fun isJwSessionExpiredStatus(statusCode: Int): Boolean = statusCode == 901 || statusCode == 910

internal fun classifyJwHttpError(statusCode: Int, responseBody: String): JwHttpErrorKind {
    val body = responseBody.lowercase()
    return when {
        statusCode == 400 || statusCode == 422 -> JwHttpErrorKind.INVALID_QUERY
        listOf("参数", "学期", "星期", "节次", "xnm", "xqm", "xqj", "jch")
            .any(body::contains) -> JwHttpErrorKind.INVALID_QUERY
        else -> JwHttpErrorKind.SERVER_REJECTED
    }
}

/** 构造正方 V9 空教室接口实际使用的字段，供请求和 JVM 单测共用。 */
internal fun buildFreeClassroomForm(query: FreeClassroomQuery): List<Pair<String, String>> = listOf(
    "xnm" to query.xnm,
    "xqm" to query.xqm,
    "xqj" to query.xqj.toString(),
    "jxlid" to "",
    "jch" to query.section.toString(),
    "jcsj" to "",
)

internal fun freeClassroomErrorMessage(error: Throwable): String = when (error) {
    is JwHttpException -> when (error.kind) {
        JwHttpErrorKind.INVALID_QUERY ->
            "查询参数不合法（HTTP ${error.statusCode}），请检查当前学期、日期和节次后重试"
        JwHttpErrorKind.SERVER_REJECTED ->
            "教务服务器拒绝了空教室查询（HTTP ${error.statusCode}），请重试；若持续失败，请重新登录"
    }
    is IOException -> "网络失败，无法连接教务服务器，请检查网络后重试"
    else -> "空教室查询失败：${error.message ?: "未知错误"}"
}

/** 纯条件构造，日期和学期均可在单测中显式传入。 */
fun buildFreeClassroomQuery(
    date: LocalDate,
    section: Int,
    term: TermOption? = null,
    termSource: String = if (term == null) "按日期推算" else "教务页面当前选项"
): FreeClassroomQuery {
    require(section in 1..12) { "节次必须在 1 到 12 之间" }
    val fallbackYear = if (date.monthValue >= 8) date.year else date.year - 1
    val fallbackTerm = if (date.monthValue in 2..7) "12" else "3"
    val selected = term ?: TermOption(
        fallbackYear.toString(),
        fallbackTerm,
        "${fallbackYear}-${fallbackYear + 1} 学年第${if (fallbackTerm == "3") 1 else 2}学期"
    )
    return FreeClassroomQuery(
        xnm = selected.xnm,
        xqm = selected.xqm,
        xqj = date.dayOfWeek.value,
        section = section,
        date = date,
        termSource = termSource,
        termLabel = selected.label
    )
}

private fun weekdayLabel(day: Int): String =
    listOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日").getOrElse(day) { "星期$day" }

/** 请求回调的纯并发协调器：新请求取代旧请求，旧回调只能被忽略。 */
internal class RequestCoordinator {
    private var latestRequestId = 0L

    fun next(): Long = ++latestRequestId

    fun invalidate() {
        latestRequestId++
    }

    fun isCurrent(requestId: Long): Boolean = requestId == latestRequestId
}

/**
 * 教务系统（正方 V9）客户端 —— 直连学校 5 条线路，App 内自动容错。
 *
 * 线路（教务处官方入口页）：210.43.24.137 / .138 / .139 / .140 / .144
 *  - 登录：GET login_slogin.html 取 csrftoken → GET login_getPublicKey.html 取 RSA 公钥
 *          → POST login_slogin.html 提交 yhm / mm(RSA) / csrftoken
 *  - 查询：必须带 X-Requested-With: XMLHttpRequest 与正确 Referer
 *  - 线路切换：每条线路独立 Cookie（CookieJar 按 host 隔离），登录时自动探测
 */
class JwApi(
    private val client: OkHttpClient,
    private val parser: JwParser = JwParser
) {
    /**
     * 教务线路池。
     * 第一位是外网域名入口（实测可达：jwgl.xynu.edu.cn → /jwglxt），
     * 其余为教务处官网列出的 IP 线路（校内/特定网络可达）。
     */
    val hosts = listOf(
        "http://jwgl.xynu.edu.cn",
        "http://210.43.24.137",
        "http://210.43.24.138",
        "http://210.43.24.139",
        "http://210.43.24.140",
        "http://210.43.24.144",
    )

    /** 当前生效的线路 */
    @Volatile
    var activeHost: String = hosts.last()

    private val activeBase: String get() = "$activeHost/jwglxt"

    private fun get(
        url: String,
        referer: String? = null,
        ajax: Boolean = false,
        requireSession: Boolean = false
    ): String {
        val builder = Request.Builder().url(url).get()
        if (requireSession) builder.requireSession(SessionKind.JW)
        if (referer != null) builder.header("Referer", referer)
        if (ajax) builder.header("X-Requested-With", "XMLHttpRequest")
        client.newCall(builder.build()).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                // 910 是教务服务器对空教室查询的会话/线路拒绝码，应映射到会话失效而不是服务器错误。
                if (requireSession && isJwSessionExpiredStatus(resp.code)) {
                    throw SessionExpiredException(SessionKind.JW, resp.request.url.host)
                }
                throw JwHttpException(resp.code, resp.request.url.toString(), body.take(4096))
            }
            return body
        }
    }

    private fun post(
        url: String,
        form: RequestBody,
        referer: String,
        requireSession: Boolean = false
    ): String {
        val builder = Request.Builder()
            .url(url)
            .header("Referer", referer)
            .header("X-Requested-With", "XMLHttpRequest")
            .post(form)
        if (requireSession) builder.requireSession(SessionKind.JW)
        client.newCall(builder.build()).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                // 910 是教务服务器对空教室查询的会话/线路拒绝码，应映射到会话失效而不是服务器错误。
                if (requireSession && isJwSessionExpiredStatus(resp.code)) {
                    throw SessionExpiredException(SessionKind.JW, resp.request.url.host)
                }
                throw JwHttpException(resp.code, resp.request.url.toString(), body.take(4096))
            }
            return body
        }
    }

    // ================= 登录（自动选线） =================

    /** 在指定线路上执行完整登录流程；返回 null 表示登录成功，否则返回错误信息 */
    private fun loginOnHost(host: String, username: String, password: String): String? {
        val base = "$host/jwglxt"
        // 1. csrftoken
        val loginPage = get("$base/xtgl/login_slogin.html")
        val csrf = Jsoup.parse(loginPage).select("input#csrftoken").firstOrNull()?.attr("value")
            ?: Jsoup.parse(loginPage).select("input[name=csrftoken]").firstOrNull()?.attr("value")
            ?: return "无法获取登录令牌"
        // 2. RSA 公钥
        val pubJson = JSONObject(get("$base/xtgl/login_getPublicKey.html?time=1"))
        val modulus = pubJson.optString("modulus")
        val exponent = pubJson.optString("exponent")
        if (modulus.isEmpty() || exponent.isEmpty()) return "无法获取加密密钥"
        // 3. RSA/PKCS#1 v1.5 加密密码
        val encPassword = try {
            JwRsaCrypto.encryptPassword(modulus, exponent, password)
        } catch (e: Exception) {
            return "密码加密失败"
        }
        // 4. 提交登录（与浏览器表单一致）
        val form = FormBody.Builder()
            .add("csrftoken", csrf)
            .add("language", "zh_CN")
            .add("yhm", username)
            .add("mm", encPassword)
            .add("mm_md5", password.md5())
            .add("mmsrddcshkzfs", "0")
            .build()
        val body = post("$base/xtgl/login_slogin.html", form, "$base/xtgl/login_slogin.html")
        val doc = Jsoup.parse(body)
        val text = doc.text()

        // 5. 判定：响应若仍是登录页，提取服务端提示并返回
        val hasLoginForm = doc.select("form[action*='login_slogin']").isNotEmpty()
        if (hasLoginForm) {
            val tips = doc.selectFirst("#tips")?.text()?.trim().orEmpty()
            return when {
                tips.isNotEmpty() -> tips.take(80)
                text.contains("用户名或密码") -> "用户名或密码不正确"
                text.contains("锁定") -> "账号已被临时锁定，请稍后再试"
                text.contains("验证码") -> "教务要求验证码，请稍后重试"
                else -> "登录失败：教务返回登录页"
            }
        }
        // 6. 会话校验
        val chk = try {
            get("$base/xtgl/index_initMenu.html?gnmkdm=N253508")
        } catch (e: Exception) {
            return "会话校验失败"
        }
        if (isLoginPage(chk)) return "登录未成功：会话未建立"
        return null
    }

    /** 判断页面是否为登录页：存在可提交的登录表单（action → login_slogin） */
    private fun isLoginPage(html: String): Boolean {
        val doc = Jsoup.parse(html)
        return doc.select("form[action*='login_slogin']").isNotEmpty()
    }

    /**
     * 登录：依次尝试线路，第一个成功者成为 activeHost。
     * 若某线路通了但密码错，优先回传业务错误；全部不可达才报网络错误。
     */
    suspend fun login(username: String, password: String): JwLoginResult {
        var lastBizError: String? = null
        var reachable = false
        for (host in hosts) {
            try {
                val err = loginOnHost(host, username, password)
                if (err == null) {
                    activeHost = host
                    return JwLoginResult(true, "登录成功（线路 ${host.removePrefix("http://")}）")
                }
                reachable = true
                lastBizError = err
            } catch (e: Exception) {
                // 该线路不可达/超时，尝试下一条
            }
        }
        return if (reachable) JwLoginResult(false, lastBizError ?: "登录失败")
        else JwLoginResult(false, "所有教务线路均无法连接，请检查网络")
    }

    /** 检查当前线路是否已登录 */
    suspend fun isLoggedIn(): Boolean {
        return try {
            val html = get("$activeBase/xtgl/index_initMenu.html?gnmkdm=N253508")
            !isLoginPage(html)
        } catch (e: Exception) {
            false
        }
    }

    // ================= 课表 =================

    /** 拉取课表。
     *  xnm/xqm 非空时强制使用指定学期；否则按候选顺序自动尝试。
     */
    suspend fun fetchSchedule(xnm: String? = null, xqm: String? = null): List<Course> {
        val base = activeBase
        val index = get("$base/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N253508",
            referer = "$base/xtgl/index_initMenu.html?gnmkdm=N253508",
            requireSession = true)
        if (index.contains("login_slogin")) throw SessionExpiredException(SessionKind.JW, activeHost)
        // 学期候选：明确指定 → 服务端标记的当前学期 → 按当前学年推算 → 下拉框里的年份
        val candidates = if (xnm != null && xqm != null) {
            listOf(xnm to xqm)
        } else {
            termCandidates(index)
        }
        for ((year, term) in candidates) {
            val form = FormBody.Builder()
                .add("xnm", year)
                .add("xqm", term)
                .add("kzlx", "ck")
                .add("xsdm", "")
                .add("xszd", "")
                .add("kcxx", "")
                .add("kcmc", "")
                .add("jsxx", "")
                .add("jssj", "")
                .add("xxkbj", "")
                .build()
            val html = try {
                post("$base/kbcx/xskbcx_cxXsKb.html?gnmkdm=N253508", form,
                    referer = "$base/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N253508",
                    requireSession = true)
            } catch (e: SessionExpiredException) {
                throw e
            } catch (_: Exception) {
                continue
            }
            if (html.contains("login_slogin")) throw SessionExpiredException(SessionKind.JW, activeHost)
            if (html.trim() == "null" || html.isBlank()) continue
            val list = try { parser.parseSchedule(html) } catch (_: Exception) { emptyList() }
            if (list.isNotEmpty()) return list
        }
        return emptyList()
    }

    /** 生成学期候选（学年 xnm + 学期 xqm） */
    private fun termCandidates(indexHtml: String): List<Pair<String, String>> {
        val doc = Jsoup.parse(indexHtml)
        val selectedYear = doc.select("select#xnm option[selected]").attr("value")
        val selectedXqm = doc.select("select#xqm option[selected]").attr("value")
        val years = doc.select("select#xnm option").map { it.attr("value") }
            .filter { it.isNotEmpty() }

        val out = mutableListOf<Pair<String, String>>()
        // 1. 服务端标记的当前学期
        if (selectedYear.isNotEmpty() && selectedXqm.isNotEmpty()) {
            out += selectedYear to selectedXqm
        }
        // 2. 按当前日期推算学年（9月-次年1月=第一学期 3；2-7月=第二学期 12）
        val cal = java.util.Calendar.getInstance()
        val year = cal.get(java.util.Calendar.YEAR)
        val month = cal.get(java.util.Calendar.MONTH) + 1
        val curYear = if (month >= 8 || month == 1) year else year - 1
        out += "$curYear" to "3"
        out += "$curYear" to "12"
        out += "${curYear - 1}" to "12"
        out += "${curYear - 1}" to "3"
        // 3. 下拉框年份（最近几年）兜底
        years.take(6).forEach { yr ->
            out += yr to "3"
            out += yr to "12"
        }
        return out.distinct()
    }

    // ================= 成绩 =================

    suspend fun fetchScores(xnm: String, xqm: String): List<ScoreItem> {
        val base = activeBase
        try {
            get("$base/cjcx/cjcx_cxXscjIndex.html?gnmkdm=N305005", requireSession = true)
        } catch (e: SessionExpiredException) {
            throw e
        } catch (_: Exception) { }
        val form = FormBody.Builder()
            .add("xnm", xnm)
            .add("xqm", xqm)
            .add("_search", "false")
            .add("nd", "1737044199314")
            .add("queryModel.showCount", "20")
            .add("queryModel.currentPage", "1")
            .add("queryModel.sortName", "")
            .add("queryModel.sortOrder", "asc")
            .add("time", "1")
            .build()
        val html = post("$base/cjcx/cjcx_cxDgXscj.html?doType=query&gnmkdm=N305005", form,
            referer = "$base/cjcx/cjcx_cxXscjIndex.html?gnmkdm=N305005",
            requireSession = true)
        if (html.contains("login_slogin")) throw SessionExpiredException(SessionKind.JW, activeHost)
        return parser.parseScores(html)
    }

    // ================= 空教室 =================

    suspend fun fetchFreeClassrooms(jc: Int = 1): List<String> =
        fetchFreeClassroomsWithQuery(jc).rooms

    suspend fun fetchFreeClassroomsWithQuery(jc: Int = 1): FreeClassroomResult {
        val base = activeBase
        val today = LocalDate.now()
        val indexUrl = "$base/kbcx/xskbcx_cxKxJscxIndex.html?gnmkdm=N253508"
        val indexHtml = get(indexUrl, requireSession = true)
        val selectedTerm = indexHtml.takeIf { it.isNotBlank() }?.let(parser::parseSelectedTerm)
        val query = buildFreeClassroomQuery(
            date = today,
            section = jc,
            term = selectedTerm,
            termSource = if (selectedTerm == null) "未读到教务当前选项，按日期推算" else "教务页面当前选项"
        )
        val form = FormBody.Builder().apply {
            buildFreeClassroomForm(query).forEach { (name, value) -> add(name, value) }
        }.build()
        val html = post("$base/kbcx/xskbcx_cxKxJscxKb.html?gnmkdm=N253508", form,
            referer = indexUrl,
            requireSession = true)
        if (html.contains("login_slogin")) throw SessionExpiredException(SessionKind.JW, activeHost)
        return FreeClassroomResult(parser.parseFreeClassrooms(html), query)
    }

    // ================= 学期 =================

    suspend fun fetchTermOptions(): List<TermOption> {
        val html = get(
            "$activeBase/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N253508",
            requireSession = true
        )
        return parser.parseTermOptions(html)
    }

    private fun parseFirstTerm(html: String): Pair<String, String> {
        val doc = Jsoup.parse(html)
        val xnm = doc.select("select#xnm option").firstOrNull()?.attr("value") ?: "2026"
        val xqm = doc.select("select#xqm option").firstOrNull()?.attr("value") ?: "3"
        return xnm to xqm
    }

    private fun String.md5(): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        return md.digest(toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}