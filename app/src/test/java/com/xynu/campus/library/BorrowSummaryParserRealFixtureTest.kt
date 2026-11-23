package com.xynu.campus.library

import com.xynu.campus.library.parser.BorrowSummaryParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F3 借阅摘要解析器 × 真实已绑定账号的落地页。
 *
 * 这份 fixture (`fixtures/lend_status.html`) 不是编造的：
 * 用真实 openid 请求
 *   http://mobilelib.wx.chaoxing.com/weixin/userBind/openItemNeedBind.action
 *       ?wxProductId=1512&itemId=20302&openid=<openid>
 * 拿到 302，跟随到
 *   http://libopac.xynu.edu.cn:8080/sms/opac/user/lendStatus.action&sn=<新sn>
 * 的原样输出（书名等已脱敏）。
 *
 * 它同时钉住三件事：
 * 1. 该学校借阅数据由阿法迪模板出，文案是「目前借书N本」不是「当前借阅N册」；
 * 2. 日期挂在 `class="dueDate"` 上，续借次数挂在 `class="renewCount"` 上；
 * 3. 空账号（0 本）也必须解析成功，不能因为没书就退化成「无法识别」。
 */
class BorrowSummaryParserRealFixtureTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("fixtures/$name")
            ?.bufferedReader()?.readText()
            ?: error("fixture 缺失：$name")

    @Test
    fun `parses real lendStatus page`() {
        val summary = BorrowSummaryParser.parse(fixture("lend_status.html"))
        assertNotNull("真实已绑定页面必须能解析出摘要", summary)
        summary!!
        assertEquals("目前借书1本 → 在借 1 册", 1, summary.currentBorrowed)
        assertEquals("应还日期取 class=dueDate", "2026/12/30", summary.dueDate)
        assertEquals("续借次数取 class=renewCount", 1, summary.renewCount)
        assertTrue("不应被判为空摘要", !summary.isEmpty)
    }

    @Test
    fun `headline reflects real borrowed count`() {
        val summary = BorrowSummaryParser.parse(fixture("lend_status.html"))!!
        assertTrue(
            "标题应说明当前借阅 1 册，实际=${summary.headline()}",
            summary.headline().contains("1"),
        )
    }

    @Test
    fun `zero book account still parses`() {
        // 0 本书的页面只差标题那一行，其它结构一样。
        // 不能因为「没有书」就解析失败，否则用户会看到「无法识别服务器返回」。
        val html = fixture("lend_status.html").replace("目前借书1本", "目前借书0本")
        val summary = BorrowSummaryParser.parse(html)
        assertNotNull(summary)
        assertEquals(0, summary!!.currentBorrowed)
        assertTrue(summary.headline().contains("没有") || summary.headline().contains("0"))
    }

    @Test
    fun `legacy chaoxing wording is still supported`() {
        // 超星自家模板用「当前借阅N册」，不能因为适配阿法迪就把旧模板丢了
        val legacy = """
            <html><body><div class="num"><h1>当前借阅3册</h1></div>
            <table><tr><th>应还日期</th><td>2024-06-01</td></tr></table>
            </body></html>
        """.trimIndent()
        val s = BorrowSummaryParser.parse(legacy)!!
        assertEquals(3, s.currentBorrowed)
        assertEquals("2024-06-01", s.dueDate)
    }

    @Test
    fun `no borrow fields yields null so UI can degrade`() {
        assertEquals(null, BorrowSummaryParser.parse("<html><body><p>系统维护中</p></body></html>"))
        assertEquals(null, BorrowSummaryParser.parse(""))
        assertEquals(null, BorrowSummaryParser.parse(null))
    }

    // ---------- 逐本借阅明细（应用内渲染的主路径） ----------

    @Test
    fun `item list is parsed from the real page so detail renders in app`() {
        val s = BorrowSummaryParser.parse(fixture("lend_status.html"))!!

        assertEquals("真实页面只有 1 本在借", 1, s.items.size)
        val item = s.items.single()
        assertEquals("逻辑学导论:推理、论证与批判性思维", item.title)
        assertEquals("23183438", item.barcode)
        assertEquals("2026/9/1 20:36:58", item.borrowDate)
        assertEquals("2026/12/30 23:59:59", item.dueDate)
        assertEquals("应还日要能单独取出日期部分给 UI 显示", "2026/12/30", item.dueDay)
        assertEquals(1, item.renewCount)
    }

    @Test
    fun `hidden renew rows and renewParam json are not mistaken for fields`() {
        // 真实页面每本书末尾有 tr[style*=display:none]（续借结果/续借时间）和
        // tr.renewParam（{"barcode":"…"}）。如果不过滤，续借次数会被污染成
        // json 里的数字，或者凭空多出「续借结果」这种字段。
        val s = BorrowSummaryParser.parse(fixture("lend_status.html"))!!
        val item = s.items.single()

        assertEquals("续借次数必须是表格里的 1，不是 json 里的数字", 1, item.renewCount)
        assertEquals("条码必须来自 th=条码号 的 td", "23183438", item.barcode)
        assertTrue("书单必须恰好 1 条，隐藏行不能变成第 2 本书", s.items.size == 1)
    }

    @Test
    fun `multiple borrowed books are all listed`() {
        // 借满 5 本时页面会叠 5 个 div.tableLib。
        fun block(barcode: String, title: String, due: String) = """
            <div class="tableLib"><div class="tableCon"><table>
              <tr><th>条码号</th><td>$barcode</td></tr>
              <tr><th>题名</th><td>$title</td></tr>
              <tr><th>借阅日期</th><td>2026/9/1 20:36:58</td></tr>
              <tr><th>应还日期</th><td class="dueDate">$due</td></tr>
              <tr><th>续借次数</th><td class="renewCount">0</td></tr>
              <tr style="display:none"><th>续借结果</th><td class="renewMsg"></td></tr>
              <tr style="display: none;" class="renewParam"><td>{"barcode":"$barcode"}</td></tr>
            </table></div></div>
        """.trimIndent()

        val html = "<html><body><div class='num'><h1>目前借书2本</h1></div>" +
            block("A1", "第一本书", "2026/11/1 23:59:59") +
            block("A2", "第二本书", "2026/12/1 23:59:59") +
            "</body></html>"

        val s = BorrowSummaryParser.parse(html)!!
        assertEquals(2, s.items.size)
        assertEquals(listOf("第一本书", "第二本书"), s.items.map { it.title })
        assertEquals(listOf("A1", "A2"), s.items.map { it.barcode })
        assertEquals(listOf("2026/11/1", "2026/12/1"), s.items.map { it.dueDay })
    }

    @Test
    fun `zero book page yields empty item list rather than null`() {
        // 0 本书时页面结构还在（只是没书），摘要要能出来，书单为空。
        val html = fixture("lend_status.html")
            .replace("目前借书1本", "目前借书0本")
            .let { Regex("""<div class="tableLib">[\s\S]*?</div>\s*</div>\s*</div>""").replace(it, "") }
        val s = BorrowSummaryParser.parse(html)
        assertNotNull("没书也要能解析，否则用户看到的是「无法识别」", s)
        assertEquals(0, s!!.currentBorrowed)
        assertTrue(s.items.isEmpty())
    }
}
