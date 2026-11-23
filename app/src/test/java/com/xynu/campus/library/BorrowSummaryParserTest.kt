package com.xynu.campus.library

import com.xynu.campus.library.parser.BorrowSummaryParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F3 借阅摘要解析器测试。
 *
 * 现实约束（已在探测阶段确认）：上游 `itemId=20302` 返回的是老式 HTML，
 * 且我们手上**没有已绑定读者证的真实 fixture**（真实 openid 尚未拿到）。
 *
 * 所以策略是：用仿真 HTML 覆盖三种最可能的上游模板结构，
 * 确保解析器在这些结构下都能工作；同时把「解析不出来」也当正常路径测，
 * 因为线上真出问题的方式就是「结构变了、解析不出来」。
 */
class BorrowSummaryParserTest {

    // ---------- 正常路径：三种模板结构 ----------

    @Test
    fun `parses dl dt dd layout`() {
        val html = """
            <html><body>
            <dl class="reader-info">
              <dt>读者证号：</dt><dd>2021001234</dd>
              <dt>当前借阅：</dt><dd>3 册</dd>
              <dt>可借：</dt><dd>7 册</dd>
              <dt>应还日期：</dt><dd>2024-06-01</dd>
              <dt>欠费：</dt><dd>无</dd>
            </dl>
            </body></html>
        """.trimIndent()

        val s = BorrowSummaryParser.parse(html)
        assertNotNull("dl 结构必须能解析", s)
        assertEquals(3, s!!.currentBorrowed)
        assertEquals(7, s.borrowable)
        assertEquals("2024-06-01", s.dueDate)
        assertEquals("无", s.unpaid)
        // 读者证号必须脱敏，不能原样带出
        assertNotNull(s.readerMasked)
        assertTrue("证号必须打码，实际: ${s.readerMasked}", s.readerMasked!!.contains("*"))
        assertTrue(
            "不得出现完整证号",
            !s.readerMasked!!.contains("2021001234"),
        )
    }

    @Test
    fun `parses table th td layout`() {
        val html = """
            <html><body>
            <table>
              <tr><th>当前借阅</th><td>5册</td></tr>
              <tr><th>借阅历史</th><td>128 册</td></tr>
              <tr><th>应还时间</th><td>2024/06/01</td></tr>
            </table>
            </body></html>
        """.trimIndent()

        val s = BorrowSummaryParser.parse(html)!!
        assertEquals(5, s.currentBorrowed)
        assertEquals(128, s.historyTotal)
        assertEquals("2024/06/01", s.dueDate)
    }

    @Test
    fun `parses span label value layout`() {
        val html = """
            <html><body>
            <ul>
              <li><span class="label">当前借阅</span><span class="value">2</span></li>
              <li><span class="label">可借</span><span class="value">8</span></li>
            </ul>
            </body></html>
        """.trimIndent()

        val s = BorrowSummaryParser.parse(html)!!
        assertEquals(2, s.currentBorrowed)
        assertEquals(8, s.borrowable)
    }

    @Test
    fun `parses flat text without any structure`() {
        // 超星有些模板把值直接塞在文本节点里，没有任何标签包裹
        val html = "<html><body><div>读者信息 当前借阅 4 册 可借 6 册 应还日期：2024-07-15</div></body></html>"
        val s = BorrowSummaryParser.parse(html)!!
        assertEquals(4, s.currentBorrowed)
        assertEquals(6, s.borrowable)
        assertEquals("2024-07-15", s.dueDate)
    }

    // ---------- 边界 ----------

    @Test
    fun `returns null for gate page so ui falls back to raw text`() {
        // 未提供 openid 时上游返回的是引导页（1043B），里面没有任何借阅字段
        val gate = """
            <html><body><script>
            if(!navigator.userAgent.toLowerCase().match(/micromessenger/)){
              document.write('请在微信中打开');
            }
            </script></body></html>
        """.trimIndent()
        assertNull("引导页不应解析出摘要", BorrowSummaryParser.parse(gate))
    }

    @Test
    fun `returns null for empty and null input`() {
        assertNull(BorrowSummaryParser.parse(null))
        assertNull(BorrowSummaryParser.parse(""))
        assertNull(BorrowSummaryParser.parse("   "))
    }

    @Test
    fun `zero borrowed is parsed as zero not null`() {
        // 0 与 null 语义完全不同：0 表示"查到了，没借书"
        val html = "<html><body><dl><dt>当前借阅：</dt><dd>0 册</dd></dl></body></html>"
        val s = BorrowSummaryParser.parse(html)!!
        assertEquals(0, s.currentBorrowed)
        assertEquals("当前没有借阅中的图书", s.headline())
    }

    @Test
    fun `headline degrades gracefully`() {
        assertEquals("借阅信息", BorrowSummaryParser.Summary().headline())
        assertEquals("当前借阅 3 册", BorrowSummaryParser.Summary(currentBorrowed = 3).headline())
    }

    @Test
    fun `unpaid amount is preserved`() {
        val html = "<html><body><dl><dt>欠费：</dt><dd>12.50</dd></dl></body></html>"
        val s = BorrowSummaryParser.parse(html)!!
        assertEquals("12.50", s.unpaid)
    }

    // ---------- 读者证脱敏 ----------

    @Test
    fun `reader id masking never leaks the full number`() {
        val cases = listOf("2021001234", "12345678", "1234", "202100123456789")
        cases.forEach { id ->
            val masked = BorrowSummaryParser.maskReader(id)
            assertEquals("长度应保持不变: $id -> $masked", id.length, masked.length)
            if (id.length > 4) {
                assertTrue("必须含星号: $id -> $masked", masked.contains("*"))
                assertTrue(
                    "不得原样返回: $id",
                    masked != id,
                )
            }
        }
    }

    @Test
    fun `reader masking keeps a few chars for user recognition`() {
        val masked = BorrowSummaryParser.maskReader("2021001234")
        assertTrue("应保留前几位便于用户确认是自己的", masked.startsWith("2021"))
        assertTrue("应保留末两位", masked.endsWith("34"))
        println("证号脱敏示例: 2021001234 -> $masked")
    }

    // ---------- 抗噪 ----------

    @Test
    fun `extra whitespace and full width colon do not break parsing`() {
        val html = "<html><body><div>当前借阅：  9   册</div></body></html>"
        val s = BorrowSummaryParser.parse(html)!!
        assertEquals(9, s.currentBorrowed)
    }

    @Test
    fun `garbage html does not throw`() {
        val garbage = "<html><body><<<>>><div><span></body>"
        // 只要不抛异常就算通过（解析不出来返回 null 是合法结果）
        BorrowSummaryParser.parse(garbage)
        BorrowSummaryParser.parse("<script>var a = '</script>'")
    }
}
