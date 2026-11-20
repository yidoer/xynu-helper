package com.xynu.campus.data.net

import com.xynu.campus.data.crypto.JwRsaCrypto
import com.xynu.campus.data.model.Course
import com.xynu.campus.data.model.JwLoginResult
import com.xynu.campus.data.model.ScoreItem
import com.xynu.campus.data.model.TermOption
import com.xynu.campus.data.parser.JwParser
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.jsoup.Jsoup
import org.json.JSONObject

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

    private fun get(url: String, referer: String? = null, ajax: Boolean = false): String {
        val builder = Request.Builder().url(url).get()
        if (referer != null) builder.header("Referer", referer)
        if (ajax) builder.header("X-Requested-With", "XMLHttpRequest")
        client.newCall(builder.build()).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            return body
        }
    }

    private fun post(url: String, form: RequestBody, referer: String): String {
        val req = Request.Builder()
            .url(url)
            .header("Referer", referer)
            .header("X-Requested-With", "XMLHttpRequest")
            .post(form)
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
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
            referer = "$base/xtgl/index_initMenu.html?gnmkdm=N253508")
        if (index.contains("login_slogin")) throw RuntimeException("登录已过期，请重新登录")
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
                    referer = "$base/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N253508")
            } catch (_: Exception) {
                continue
            }
            if (html.contains("login_slogin")) throw RuntimeException("登录已过期，请重新登录")
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
        try { get("$base/cjcx/cjcx_cxXscjIndex.html?gnmkdm=N305005") } catch (_: Exception) { }
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
            referer = "$base/cjcx/cjcx_cxXscjIndex.html?gnmkdm=N305005")
        if (html.contains("login_slogin")) throw RuntimeException("登录已过期，请重新登录")
        return parser.parseScores(html)
    }

    // ================= 空教室 =================

    suspend fun fetchFreeClassrooms(jc: Int = 1): List<String> {
        val base = activeBase
        try { get("$base/kbcx/xskbcx_cxKxJscxIndex.html?gnmkdm=N253508") } catch (_: Exception) { }
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val form = FormBody.Builder()
            .add("xnm", "${year - 1}")
            .add("xqm", "3")
            .add("xqj", "1")
            .add("jxlid", "")
            .add("jch", jc.toString())
            .add("jcsj", "")
            .build()
        val html = post("$base/kbcx/xskbcx_cxKxJscxKb.html?gnmkdm=N253508", form,
            referer = "$base/kbcx/xskbcx_cxKxJscxIndex.html?gnmkdm=N253508")
        if (html.contains("login_slogin")) throw RuntimeException("登录已过期，请重新登录")
        return parser.parseFreeClassrooms(html)
    }

    // ================= 学期 =================

    suspend fun fetchTermOptions(): List<TermOption> {
        val html = get("$activeBase/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N253508")
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