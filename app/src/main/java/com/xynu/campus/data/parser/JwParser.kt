package com.xynu.campus.data.parser

import com.xynu.campus.data.model.CampusOption
import com.xynu.campus.data.model.Course
import com.xynu.campus.data.model.FreeRoom
import com.xynu.campus.data.model.ScoreItem
import com.xynu.campus.data.model.TermOption
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * 教务系统解析器（正方 v5）。
 * 课表/成绩接口即返回 JSON，也兼容 HTML 表格回退。
 */
object JwParser {

    /**
     * 解析课表。输入可能是 JSON（正方 v5 接口返回 kbList）或 HTML 表格。
     */
    fun parseSchedule(payload: String): List<Course> {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.startsWith("{") && trimmed.contains("\"kbList\"")) {
            return runCatching { parseScheduleJson(JSONObject(trimmed)) }.getOrDefault(emptyList())
        }
        if (trimmed.startsWith("[")) {
            return runCatching { parseScheduleJsonArray(JSONArray(trimmed)) }.getOrDefault(emptyList())
        }
        return parseScheduleHtml(payload)
    }

    /** 正方 v5 JSON: {"kbList":[{kcmc,jsmc,xqj,skjc,jcs,xmd,cdmc, ...}]} */
    private fun parseScheduleJson(o: JSONObject): List<Course> {
        val arr = o.optJSONArray("kbList") ?: return emptyList()
        return parseKbArray(arr)
    }

    private fun parseScheduleJsonArray(arr: JSONArray): List<Course> {
        return parseKbArray(arr)
    }

    private fun parseKbArray(arr: JSONArray): List<Course> {
        val courses = mutableListOf<Course>()
        for (i in 0 until arr.length()) {
            try {
                val it = arr.getJSONObject(i)
                val name = it.optString("kcmc").orEmpty().trim()
                if (name.isEmpty()) continue
                val teacher = it.optString("jsmc").ifEmpty { it.optString("xm") }.orEmpty().trim()
                val day = it.optString("xqj").toIntOrNull() ?: it.optInt("xqj", -1)
                val weeksRaw = it.optString("xqjmc").orEmpty()
                // skjc 节次 e.g. "3-4" ; jcs 描述
                val sectionRaw = it.optString("skjc").ifEmpty { it.optString("jcs") }
                val (start, end) = parseSections(sectionRaw)
                val location = it.optString("cdmc").orEmpty().trim()
                val weeks = parseWeeks(it.optString("zcd").orEmpty().ifEmpty { weeksRaw })
                val xs = it.optString("xs").orEmpty().trim()  // 单双周
                courses += Course(
                    name = name,
                    teacher = teacher,
                    dayOfWeek = day,
                    startSection = start,
                    endSection = end,
                    startWeek = weeks.first,
                    endWeek = weeks.second,
                    location = location,
                    weeksText = it.optString("zcd").orEmpty(),
                    colorIndex = (name.hashCode() and 0x7fffffff) % 8,
                    isOddWeeks = xs.contains("单"),
                    isEvenWeeks = xs.contains("双")
                )
            } catch (_: Exception) { }
        }
        return courses.distinctBy { it.name + it.dayOfWeek + it.startSection + it.location + it.weeksText }
    }

    /** HTML 表格回退 */
    private fun parseScheduleHtml(html: String): List<Course> {
        val doc = Jsoup.parse(html)
        val courses = mutableListOf<Course>()
        // 尝试 JSON 嵌套在 HTML 中的情况（<script>var kbList = [...]）
        doc.select("script").forEach { script ->
            val t = script.html()
            if (t.contains("kbList")) {
                Regex("\"kbList\"\\s*[:=]\\s*(\\[.*?\\])").find(t)?.groupValues?.get(1)?.let {
                    try {
                        return parseKbArray(JSONArray(it))
                    } catch (_: Exception) { }
                }
            }
        }

        val rows = doc.select("tr[id^=xskbxx]")
        for (tr in rows) {
            try {
                val name = tr.select("td[id^=kb_kcmc]").firstOrNull()?.text()?.trim() ?: continue
                if (name.isEmpty()) continue
                val teacher = tr.select("td[id^=kb_jsmc]").firstOrNull()?.text()?.trim() ?: ""
                val weeksRaw = tr.select("td[id^=kb_sj]").firstOrNull()?.text()
                    ?: tr.select("td").getOrNull(3)?.text() ?: ""
                val weeksInfo = parseWeeks(weeksRaw)
                val sectionRaw = tr.select("td[id^=kb_jcs]").firstOrNull()?.text()
                    ?: tr.select("td").getOrNull(2)?.text() ?: ""
                val (start, end) = parseSections(sectionRaw)
                val dayRaw = tr.select("td[id^=kb_xqj]").firstOrNull()?.text() ?: ""
                val day = parseDay(dayRaw)
                val location = tr.select("td[id^=kb_cdmc]").firstOrNull()?.text()?.trim() ?: ""

                if (day in 1..7 && start > 0) {
                    courses += Course(
                        name = name,
                        teacher = teacher,
                        dayOfWeek = day,
                        startSection = start,
                        endSection = end,
                        startWeek = weeksInfo.first,
                        endWeek = weeksInfo.second,
                        location = location,
                        weeksText = weeksRaw,
                        colorIndex = (name.hashCode() and 0x7fffffff) % 8,
                        isOddWeeks = weeksInfo.third,
                        isEvenWeeks = false
                    )
                }
            } catch (_: Exception) { }
        }
        return courses.distinctBy { it.name + it.dayOfWeek + it.startSection + it.location + it.weeksText }
    }

    private fun parseSections(raw: String): Pair<Int, Int> {
        if (raw.isBlank()) return 1 to 1
        val m = Regex("(\\d+)\\s*[-~至]\\s*(\\d+)").find(raw)
        if (m != null) return m.groupValues[1].toInt() to m.groupValues[2].toInt()
        // JSON "skjc":"3" 单节
        return raw.trim().toIntOrNull()?.let { it to it } ?: (1 to 1)
    }

    private fun parseDay(raw: String): Int {
        return when (raw.trim()) {
            "一", "1", "星期一" -> 1
            "二", "2", "星期二" -> 2
            "三", "3", "星期三" -> 3
            "四", "4", "星期四" -> 4
            "五", "5", "星期五" -> 5
            "六", "6", "星期六" -> 6
            "日", "天", "7", "星期日", "星期天" -> 7
            else -> -1
        }
    }

    /** 解析周次文本，返回 (start, end, 单/双周标记) */
    private fun parseWeeks(raw: String): Triple<Int, Int, Boolean> {
        var start = 1; var end = 20; var oddEven = false
        val m = Regex("(\\d+)(?:\\s*-\\s*(\\d+))?").find(raw)
        if (m != null) {
            start = m.groupValues[1].toIntOrNull() ?: 1
            end = m.groupValues[2].toIntOrNull() ?: start
        }
        if (raw.contains("单") || raw.contains("双")) oddEven = true
        return Triple(start, end, oddEven)
    }

    /** 解析成绩：兼容 JSON 数组 / {items:[]} / HTML */
    fun parseScores(payload: String): List<ScoreItem> {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.startsWith("{")) {
            return runCatching {
                val o = JSONObject(trimmed)
                val arr = o.optJSONArray("items") ?: o.optJSONArray("xxb") ?: return@runCatching emptyList()
                parseScoreArray(arr)
            }.getOrDefault(emptyList())
        }
        if (trimmed.startsWith("[")) {
            return runCatching { parseScoreArray(JSONArray(trimmed)) }.getOrDefault(emptyList())
        }
        return parseScoreHtml(payload)
    }

    private fun parseScoreArray(arr: JSONArray): List<ScoreItem> {
        val scores = mutableListOf<ScoreItem>()
        for (i in 0 until arr.length()) {
            try {
                val o = arr.getJSONObject(i)
                val course = o.optString("kcmc").ifEmpty { o.optString("课程名称") }
                if (course.isBlank()) continue
                scores += ScoreItem(
                    courseName = course,
                    credit = o.optString("xf").ifEmpty { o.optString("学分") },
                    score = o.optString("cj").ifEmpty { o.optString("成绩") },
                    gpa = o.optString("jd").ifEmpty { o.optString("绩点") },
                    courseNature = o.optString("kcxzmc").ifEmpty { o.optString("课程性质") },
                    examType = o.optString("ksxzmc").ifEmpty { o.optString("考试性质") },
                    remark = o.optString("bz").ifEmpty { o.optString("备注") },
                    semester = o.optString("xnmc").ifEmpty { o.optString("学年学期") }
                )
            } catch (_: Exception) { }
        }
        return scores
    }

    /** HTML 表格回退（老接口） */
    private fun parseScoreHtml(html: String): List<ScoreItem> {
        val doc = Jsoup.parse(html)
        val scores = mutableListOf<ScoreItem>()
        val rows = doc.select("table[id^=mx_table] tr, table.datelist tr")
        for (tr in rows) {
            val tds = tr.select("td")
            if (tds.size < 6) continue
            val course = tds.getOrNull(1)?.text()?.trim() ?: continue
            if (course.isEmpty() || course.contains("课程名称")) continue
            scores += ScoreItem(
                courseName = course,
                credit = tds.getOrNull(2)?.text()?.trim() ?: "",
                score = tds.getOrNull(3)?.text()?.trim() ?: "",
                gpa = tds.getOrNull(4)?.text()?.trim() ?: "",
                courseNature = tds.getOrNull(5)?.text()?.trim() ?: "",
                examType = tds.getOrNull(6)?.text()?.trim() ?: "",
                remark = tds.getOrNull(7)?.text()?.trim() ?: "",
                semester = ""
            )
        }
        return scores
    }

    /** 读取教务页面明确标记的当前学期；没有 selected 标记时返回 null。 */
    fun parseSelectedTerm(html: String): TermOption? {
        val doc = Jsoup.parse(html)
        val year = doc.selectFirst("select#xnm option[selected]")?.attr("value").orEmpty()
        val term = doc.selectFirst("select#xqm option[selected]")?.attr("value").orEmpty()
        if (year.isBlank() || term.isBlank()) return null
        val baseYear = year.toIntOrNull() ?: return null
        val label = "$year-${baseYear + 1} 学年第${if (term == "3") 1 else 2}学期"
        return TermOption(year, term, label)
    }

    /** 从成绩查询页解析学年学期下拉框，明确选项排在最前。 */
    fun parseTermOptions(html: String): List<TermOption> {
        val doc = Jsoup.parse(html)
        val years = mutableListOf<String>()
        doc.select("select#xnm option").forEach {
            val v = it.attr("value")
            if (v.isNotEmpty()) years += v
        }
        val opts = mutableListOf<TermOption>()
        for (year in years) {
            val label = year.take(4) + "-" + (year.take(4).toIntOrNull()?.plus(1) ?: (year + 1))
            opts += TermOption(year, "12", "$label 学年第2学期")
            opts += TermOption(year, "3", "$label 学年第1学期")
        }
        if (opts.isEmpty()) {
            val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            opts += TermOption("${year - 1}", "3", "${year - 1}-${year} 学年第1学期")
            opts += TermOption("${year - 1}", "12", "${year - 1}-${year} 学年第2学期")
        }
        if (opts.isEmpty()) {
            val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            opts += TermOption("${year - 1}", "3", "${year - 1}-${year} 学年第1学期")
            opts += TermOption("${year - 1}", "12", "${year - 1}-${year} 学年第2学期")
        }
        val selected = parseSelectedTerm(html)
        return if (selected != null) {
            listOf(selected) + opts.filterNot { it.xnm == selected.xnm && it.xqm == selected.xqm }
        } else {
            opts
        }
    }

    /**
     * 解析空闲场地查询结果。
     *
     * 实测真实响应（`cdjy/cdjy_cxKxcdlb.html?doType=query`）：
     * ```json
     * {"currentPage":1,"totalResult":"90","items":[
     *   {"cd_id":"01BF…","cdbh":"LZ2101","cdmc":"综教2101","cdjc":"综合教学楼2",
     *    "jxlmc":"综合教学楼2","lch":"1","lh":"102","zws":"215","kszws1":"115",
     *    "cdlbmc":"智慧教室","xqmc":"理工学院","sfkjy":"是","sfkpk":"是"}]}
     * ```
     * 兼容旧版 `{"cdList":[...]}` / `{"list":[...]}` 以及 HTML 表格兜底。
     */
    fun parseFreeRooms(payload: String): List<FreeRoom> {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.startsWith("{")) {
            val arr = runCatching {
                val o = JSONObject(trimmed)
                o.optJSONArray("items") ?: o.optJSONArray("cdList") ?: o.optJSONArray("list")
            }.getOrNull()
            if (arr != null) {
                val out = mutableListOf<FreeRoom>()
                for (i in 0 until arr.length()) {
                    val it = arr.optJSONObject(i) ?: continue
                    val name = it.optString("cdmc").trim()
                        .ifBlank { it.optString("jxlmc").trim() }
                    if (name.isBlank()) continue
                    out += FreeRoom(
                        name = name,
                        code = it.optString("cdbh").trim(),
                        building = it.optString("cdjc").trim()
                            .ifBlank { it.optString("jxlmc").trim() },
                        floor = it.optString("lch").trim(),
                        category = it.optString("cdlbmc").trim(),
                        seats = it.optString("zws").trim().toIntOrNull() ?: 0,
                        availableSeats = it.optString("kszws1").trim().toIntOrNull() ?: 0,
                        campus = it.optString("xqmc").trim(),
                        available = it.optString("sfkjy").trim() != "否"
                    )
                }
                return out
            }
        }
        // HTML 兜底：老接口以表格返回，只取首列作为场地名
        val out = mutableListOf<FreeRoom>()
        runCatching {
            val doc = Jsoup.parse(payload)
            doc.select("tr").forEach { tr ->
                val tds = tr.select("td")
                val name = tds.firstOrNull()?.text()?.trim().orEmpty()
                if (name.isNotEmpty() && name.length < 30 && !name.contains("教室名称")) {
                    out += FreeRoom(
                        name = name,
                        code = "",
                        building = tds.getOrNull(1)?.text()?.trim().orEmpty(),
                        floor = "",
                        category = "",
                        seats = 0,
                        availableSeats = 0,
                        campus = "",
                        available = true
                    )
                }
            }
            if (out.isEmpty()) {
                doc.select("td").forEach { td ->
                    val t = td.text().trim()
                    if (t.isNotEmpty() && t.length < 30) {
                        out += FreeRoom(t, "", "", "", "", 0, 0, "", true)
                    }
                }
            }
        }
        return out.distinctBy { it.name }
    }

    /** 兼容旧调用：只取场地名称。 */
    fun parseFreeClassrooms(payload: String): List<String> =
        parseFreeRooms(payload).map { it.name }.distinct()

    /**
     * 空闲场地页面上的学年学期下拉框是 `select#dm_cx`，选项值形如 `2026-3`
     * （学年-学期），与成绩页的 `#xnm` / `#xqm` 两个下拉不同，需要单独解析。
     */
    fun parseFreeClassroomTerm(html: String): TermOption? {
        val doc = Jsoup.parse(html)
        val raw = doc.selectFirst("select#dm_cx option[selected]")?.attr("value").orEmpty()
        val parts = raw.split("-")
        if (parts.size != 2) return null
        val year = parts[0].trim()
        val term = parts[1].trim()
        if (year.toIntOrNull() == null || term.toIntOrNull() == null) return null
        val label = "$year-${year.toInt() + 1} 学年第${if (term == "3") 1 else 2}学期"
        return TermOption(year, term, label)
    }

    /**
     * 空闲场地页面的校区 ID。查询必须带上它，否则服务端按全校汇总
     * （实测：带上=90 间，留空=632 间）。
     */
    fun parseFreeClassroomCampusId(html: String): String {
        val doc = Jsoup.parse(html)
        val selected = doc.selectFirst("select#xqh_id option[selected]")?.attr("value")
        if (!selected.isNullOrBlank()) return selected
        val hidden = doc.selectFirst("input#xqh_id")?.attr("value")
        if (!hidden.isNullOrBlank()) return hidden
        return doc.selectFirst("select#xqh_id option")?.attr("value").orEmpty()
    }

    /**
     * 空闲场地页面「校区」下拉框的全部选项。
     *
     * 页面默认只选中「理工学院」，直接拿 `parseFreeClassroomCampusId` 去查会漏掉
     * 其它校区（理工 76 间 vs 全校 591 间）。这里把选项全解析出来，供 UI 提供
     * 「全校 / 指定校区」的切换。
     *
     * 返回的列表保留 `value` 为空串的「全部」选项（若页面提供）。
     */
    fun parseFreeClassroomCampuses(html: String): List<CampusOption> {
        val doc = Jsoup.parse(html)
        return doc.select("select#xqh_id option")
            .mapNotNull { option ->
                val value = option.attr("value").trim()
                val label = option.text().trim()
                if (label.isBlank()) null
                else CampusOption(id = value, name = label, selected = option.hasAttr("selected"))
            }
            .distinctBy { it.id }
    }
}