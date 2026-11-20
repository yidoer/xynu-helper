package com.xynu.campus.data.net

import com.xynu.campus.data.model.Course
import com.xynu.campus.data.model.JwLoginResult
import com.xynu.campus.data.model.ScoreItem
import com.xynu.campus.data.parser.JwParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 校内服务后端代理客户端。
 * 教务登录/课表/成绩/空教室 + 超星图书检索，全部经由后端转发，
 * 好处：① 外网可用 ② 教务 5 条线路在后端自动容错 ③ 超星 IP:8080 端口由后端兜底。
 *
 * 后端地址可在设置页配置（默认本地开发地址；生产部署后改）。
 */
class BackendApi(
    private val client: OkHttpClient,
    var base: String = "http://10.0.2.2:5000"   // 模拟器访问宿主机
) {

    @Volatile
    var session: String? = null

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun getJson(path: String): JSONObject {
        val req = Request.Builder()
            .url(base + path)
            .header("X-Session", session ?: "")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw RuntimeException("后端 HTTP ${resp.code}: $body")
            val obj = JSONObject(body)
            if (!obj.optBoolean("success", false)) {
                throw RuntimeException(obj.optString("msg", "后端返回错误"))
            }
            return obj
        }
    }

    private fun postJson(path: String, payload: JSONObject): JSONObject {
        val req = Request.Builder()
            .url(base + path)
            .header("X-Session", session ?: "")
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw RuntimeException("后端 HTTP ${resp.code}: $body")
            val obj = JSONObject(body)
            if (!obj.optBoolean("success", false)) {
                throw RuntimeException(obj.optString("msg", "后端返回错误"))
            }
            return obj
        }
    }

    // ---------- 健康检查 / 线路状态 ----------
    suspend fun health(): Map<String, Boolean> {
        val obj = getJson("/api/health")
        val hosts = obj.optJSONObject("data")?.optJSONObject("hosts") ?: JSONObject()
        return hosts.keys().asSequence().associateWith { hosts.optBoolean(it) }
    }

    // ---------- 教务登录 ----------
    suspend fun jwLogin(username: String, password: String): JwLoginResult {
        return try {
            val obj = postJson("/api/jw/login", JSONObject().apply {
                put("username", username)
                put("password", password)
            })
            val data = obj.optJSONObject("data")
            session = data?.optString("session")
            JwLoginResult(true, "登录成功")
        } catch (e: Exception) {
            val msg = e.message ?: "登录失败"
            JwLoginResult(false, msg.replace("Failed to connect", "网络异常（请确认连接了外网可达的教务后端）"))
        }
    }

    fun logout() { session = null }

    // ---------- 课表 ----------
    suspend fun schedule(xnm: String? = null, xqm: String? = null): List<Course> {
        val q = buildString {
            append("?")
            if (!xnm.isNullOrBlank()) append("xnm=$xnm&")
            if (!xqm.isNullOrBlank()) append("xqm=$xqm")
        }
        val obj = getJson("/api/jw/schedule$q")
        return JwParser.parseSchedule(obj.optJSONArray("data")?.toString() ?: "[]")
    }

    // ---------- 成绩 ----------
    suspend fun scores(xnm: String, xqm: String): List<ScoreItem> {
        val obj = getJson("/api/jw/scores?xnm=$xnm&xqm=$xqm")
        return JwParser.parseScores(obj.optJSONArray("data")?.toString() ?: "[]")
    }

    // ---------- 空教室 ----------
    suspend fun freeClassrooms(jxlid: String = "", jc: Int = 1): List<String> {
        val obj = getJson("/api/jw/free_classrooms?jxlid=$jxlid&jc=$jc")
        val arr = obj.optJSONArray("data") ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i)
            if (it != null) {
                // 教室名优先 cdmc，其次 jxlmc；无则用原始串
                val name = it.optString("cdmc").ifBlank { it.optString("jxlmc") }
                if (name.isNotBlank()) out += name
                else out += it.toString()
            } else {
                out += arr.opt(i).toString()
            }
        }
        return out.distinct()
    }

    // ---------- 超星图书检索 ----------
    suspend fun libSearch(kw: String): String {
        val obj = getJson("/api/lib/search?kw=${java.net.URLEncoder.encode(kw, "UTF-8")}")
        return obj.optJSONObject("data")?.optString("raw") ?: ""
    }
}