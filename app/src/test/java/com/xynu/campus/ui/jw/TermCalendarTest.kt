package com.xynu.campus.ui.jw

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 学期日历换算测试。
 *
 * 重点覆盖「开学日不是周一」这个此前出错的场景：旧实现里 `currentWeek`
 * 把开学日直接当第 1 周第 1 天，而 `dateOfWeek` 又把开学日对齐到周一，
 * 两套基准在开学日非周一时会差一周。
 */
class TermCalendarTest {

    private fun millis(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, 0, 0, 0)
        }.timeInMillis

    @Test
    fun `week of term start is always week one`() {
        // 2026-09-03 是周四，开学日不论星期几都必须落在第 1 周
        val start = millis(2026, 9, 3)
        assertEquals(1, TermCalendar.weekOf(start, start))
    }

    @Test
    fun `week advances on monday when term starts mid week`() {
        val start = millis(2026, 9, 3) // 周四
        // 同一周内的周日仍属第 1 周
        assertEquals(1, TermCalendar.weekOf(start, millis(2026, 9, 6)))
        // 下一个周一进入第 2 周
        assertEquals(2, TermCalendar.weekOf(start, millis(2026, 9, 7)))
        assertEquals(3, TermCalendar.weekOf(start, millis(2026, 9, 14)))
    }

    @Test
    fun `date of week round trips back to the same week`() {
        val start = millis(2026, 9, 3)
        for (week in 1..20) {
            for (day in 1..7) {
                val date = TermCalendar.dateOf(start, week, day)!!.timeInMillis
                assertEquals("week=$week day=$day", week, TermCalendar.weekOf(start, date))
                assertEquals("week=$week day=$day", day, TermCalendar.dayOfWeekOf(date))
            }
        }
    }

    @Test
    fun `dates before term start clamp to week one`() {
        val start = millis(2026, 9, 3)
        // 开学前不应出现第 0 周或负数
        assertEquals(1, TermCalendar.weekOf(start, millis(2026, 8, 1)))
    }

    @Test
    fun `day of week maps monday to one and sunday to seven`() {
        assertEquals(1, TermCalendar.dayOfWeekOf(millis(2026, 9, 7)))  // 周一
        assertEquals(7, TermCalendar.dayOfWeekOf(millis(2026, 9, 13))) // 周日
    }

    @Test
    fun `missing term start yields null week`() {
        assertNull(TermCalendar.weekOfTerm(null, millis(2026, 9, 7)))
        assertNull(TermCalendar.dateOf(null, 3, 2))
    }

    @Test
    fun `weekday labels cover all seven days`() {
        assertEquals("周一", TermCalendar.weekdayLabel(1))
        assertEquals("周日", TermCalendar.weekdayLabel(7))
        // 越界不应崩溃
        assertEquals("周一", TermCalendar.weekdayLabel(0))
        assertEquals("周日", TermCalendar.weekdayLabel(9))
    }
}
