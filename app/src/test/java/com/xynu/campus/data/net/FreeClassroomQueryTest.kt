package com.xynu.campus.data.net

import com.xynu.campus.data.model.TermOption
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeClassroomQueryTest {

    @Test
    fun `query uses actual weekday and date-derived academic term`() {
        val query = buildFreeClassroomQuery(LocalDate.of(2026, 11, 20), section = 4)

        assertEquals("2026", query.xnm)
        assertEquals("3", query.xqm)
        assertEquals(5, query.xqj)
        assertEquals(4, query.section)
        assertTrue(query.display.contains("周五"))
        assertTrue(query.display.contains("按日期推算"))
    }

    @Test
    fun `query prefers term explicitly selected by the education page`() {
        val query = buildFreeClassroomQuery(
            date = LocalDate.of(2026, 3, 2),
            section = 1,
            term = TermOption("2025", "12", "2025-2026 学年第2学期")
        )

        assertEquals("2025", query.xnm)
        assertEquals("12", query.xqm)
        assertEquals(1, query.xqj)
        assertTrue(query.display.contains("教务页面当前选项"))
    }

    @Test
    fun `old free classroom response cannot apply after a newer request`() {
        assertFalse(isCurrentFreeRoomsRequest(1L, 2L))
        assertTrue(isCurrentFreeRoomsRequest(2L, 2L))
    }
}
