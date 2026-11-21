package com.xynu.campus.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 使用脱敏的页面结构样例验证解析器。
 * 解析层只依赖 Jsoup/JSON 和 data model，因此可以直接在纯 JVM 测试。
 */
class ParserTest {

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/fixtures/$name")?.use {
            it.reader(Charsets.UTF_8).readText()
        } ?: error("fixture missing: $name")

    @Test
    fun `news parser extracts only dated jwc news`() {
        val items = NewsParser.parseNewsList(fixture("jwc-jwdt.html"), "教务处")
        assertTrue("should find news items", items.isNotEmpty())
        items.forEach {
            assertTrue("should have date, got: ${it.title} -> ${it.date}", it.date.matches(Regex("\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}")))
            assertTrue("title should be meaningful: ${it.title}", it.title.length >= 4)
            assertTrue("should have url", it.url.startsWith("http"))
        }
        assertTrue(items.none { it.title == "网站首页" })
    }

    @Test
    fun `lib news parser extracts library notices`() {
        val items = NewsParser.parseLibNews(fixture("lib-home.html"))
        assertTrue("should find lib news", items.isNotEmpty())
        assertTrue(items.all { it.url.contains("lib.xynu.edu.cn") })
    }

    @Test
    fun `jw schedule parser extracts html courses and ignores incomplete rows`() {
        val courses = JwParser.parseSchedule(fixture("jwc-schedule.html"))

        assertEquals(2, courses.size)
        assertEquals("高等数学A", courses[0].name)
        assertEquals("张老师", courses[0].teacher)
        assertEquals(1, courses[0].dayOfWeek)
        assertEquals(1, courses[0].startSection)
        assertEquals(2, courses[0].endSection)
        assertEquals("理科楼101", courses[0].location)
        assertEquals("大学英语", courses[1].name)
        assertTrue(courses[1].isOddWeeks)
    }

    @Test
    fun `jw score parser extracts html rows and skips header or missing course`() {
        val scores = JwParser.parseScores(fixture("jwc-scores.html"))

        assertEquals(2, scores.size)
        assertEquals("高等数学A", scores[0].courseName)
        assertEquals("5", scores[0].credit)
        assertEquals("92", scores[0].score)
        assertEquals("必修", scores[0].courseNature)
        assertEquals("大学英语", scores[1].courseName)
        assertEquals("优秀", scores[1].remark)
    }

    @Test
    fun `jw free classroom parser extracts distinct room names`() {
        val rooms = JwParser.parseFreeClassrooms(fixture("jwc-free-classrooms.html"))

        assertEquals(listOf("理科楼101", "外语楼203"), rooms)
    }

    @Test
    fun `cdjy response parser reads every room field from the real payload`() {
        val rooms = JwParser.parseFreeRooms(fixture("cdjy-free-rooms.json"))

        assertEquals(6, rooms.size)
        val first = rooms[0]
        assertEquals("大学物理实验室一", first.name)
        assertEquals("B1-101", first.code)
        assertEquals("B1-101", first.building)
        assertEquals("1", first.floor)
        assertEquals("实验室", first.category)
        assertEquals(60, first.seats)
        assertEquals("理工学院", first.campus)
        assertTrue(first.available)
        assertTrue("副标题应含楼栋与座位数，实际: ${first.subtitle}", first.subtitle.contains("60 座"))
    }

    @Test
    fun `cdjy rooms keep their category so the UI can group them`() {
        val rooms = JwParser.parseFreeRooms(fixture("cdjy-free-rooms.json"))

        // fixture 覆盖了三种真实类别，顺序无关地校验
        assertEquals(
            setOf("实验室", "多媒体教室", "普通教室"),
            rooms.map { it.category }.toSet()
        )
        assertTrue(rooms.all { it.code.isNotBlank() })
        assertTrue(rooms.all { it.name.isNotBlank() })
    }

    @Test
    fun `cdjy index parser reads the current term and campus`() {
        val html = fixture("cdjy-index.html")

        val term = JwParser.parseFreeClassroomTerm(html)
        assertEquals("2026", term?.xnm)
        assertEquals("3", term?.xqm)
        assertEquals("2026-2027 学年第1学期", term?.label)

        // 校区决定查询范围：留空会让服务端按全校汇总
        assertEquals("FD65E581B6103CDBE05394182BD2FD61", JwParser.parseFreeClassroomCampusId(html))
    }

    @Test
    fun `cdjy index parser reads every campus option so the UI can widen the query`() {
        val html = fixture("cdjy-index.html")

        val campuses = JwParser.parseFreeClassroomCampuses(html)

        // 教务页面默认只勾选「理工学院」，只照抄它就会漏掉其它校区
        assertEquals(5, campuses.size)
        assertEquals("理工学院", campuses[0].name)
        assertEquals("FD65E581B6103CDBE05394182BD2FD61", campuses[0].id)
        assertTrue(campuses[0].selected)
        assertEquals(
            listOf("理工学院", "校本部校区", "淮河校区", "信阳职业", "潢川幼儿"),
            campuses.map { it.name }
        )
        // 默认勾选的只有一个校区，其余都要靠 UI 让用户切换
        assertEquals(1, campuses.count { it.selected })
    }

    @Test
    fun `cdjy index parser tolerates a page without the expected selects`() {
        assertNull(JwParser.parseFreeClassroomTerm("<html><body>无表单</body></html>"))
        assertEquals("", JwParser.parseFreeClassroomCampusId("<html><body>无表单</body></html>"))
        assertTrue(JwParser.parseFreeClassroomCampuses("<html><body>无表单</body></html>").isEmpty())
    }

    @Test
    fun `opac parser extracts borrow rows and reader summary`() {
        val html = fixture("opac-borrow.html")
        val borrowed = LibParser.parseBorrowList(html)
        val reader = LibParser.parseReaderInfo(html)

        assertEquals(2, borrowed.size)
        assertEquals("Java编程思想", borrowed[0].title)
        assertEquals("Bruce Eckel", borrowed[0].author)
        assertEquals("BC001", borrowed[0].barcode)
        assertEquals("/opac/book/renew", borrowed[0].renewUrl)
        assertEquals("张三", reader.first)
        assertEquals(2, reader.second)
    }

    @Test
    fun `opac search parser tolerates missing optional fields`() {
        val html = """
            <div class="jp-searchList"><ul><li>
              <h2><a href="/opac/book/42">只有题名</a></h2>
            </li></ul></div>
        """.trimIndent()

        val books = LibParser.parseSearchResults(html)
        assertEquals(1, books.size)
        assertEquals("只有题名", books.single().title)
        assertEquals("42", books.single().id)
        assertEquals("", books.single().author)
    }

    @Test
    fun `parsers return empty results for blank or malformed payloads`() {
        assertTrue(JwParser.parseSchedule("").isEmpty())
        assertTrue(JwParser.parseScores("{").isEmpty())
        assertTrue(JwParser.parseFreeClassrooms("{").isEmpty())
        assertTrue(LibParser.parseBorrowList("").isEmpty())
        assertEquals("" to 0, LibParser.parseReaderInfo(""))
        assertTrue(LibParser.parseHoldings("not-json").isEmpty())
    }

    @Test
    fun `parsers keep safe fallback when fields disappear after a page revision`() {
        val schedule = JwParser.parseSchedule(
            "<table><tr id='xskbxx_0'><td id='kb_kcmc_0'>课程仍在</td></tr></table>"
        )
        val score = JwParser.parseScores(
            "<table class='datelist'><tr><td>1</td><td>成绩仍在</td><td>2</td><td>合格</td><td></td><td>选修</td></tr></table>"
        )
        val borrow = LibParser.parseBorrowList(
            "<table><tr><th>应还</th></tr><tr><td>改版书目</td><td></td><td></td></tr></table>"
        )

        assertTrue("missing schedule fields should not produce an invalid course", schedule.isEmpty())
        assertEquals(1, score.size)
        assertEquals("成绩仍在", score.single().courseName)
        assertEquals(1, borrow.size)
        assertEquals("改版书目", borrow.single().title)
        assertTrue(borrow.single().dueDate.isBlank())
    }
}
