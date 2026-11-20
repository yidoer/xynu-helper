package com.xynu.campus.data.net

import com.xynu.campus.data.model.TermOption
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        val requests = RequestCoordinator()
        val firstRequest = requests.next()
        val secondRequest = requests.next()

        assertFalse(requests.isCurrent(firstRequest))
        assertTrue(requests.isCurrent(secondRequest))
    }

    @Test
    fun `old free classroom success cannot overwrite newer result`() = runBlocking {
        val requests = RequestCoordinator()
        val oldRequest = requests.next()
        val newRequest = requests.next()
        val oldResult = CompletableDeferred<List<String>>()
        val newResult = CompletableDeferred<List<String>>()
        var rooms = emptyList<String>()

        suspend fun applyResult(requestId: Long, result: Deferred<List<String>>) {
            val response = result.await()
            if (requests.isCurrent(requestId)) rooms = response
        }

        val oldJob = launch { applyResult(oldRequest, oldResult) }
        val newJob = launch { applyResult(newRequest, newResult) }
        newResult.complete(listOf("新节次教室"))
        newJob.join()
        oldResult.complete(listOf("旧节次教室"))
        oldJob.join()

        assertEquals(listOf("新节次教室"), rooms)
    }

    @Test
    fun `old free classroom failure cannot overwrite newer request`() = runBlocking {
        val requests = RequestCoordinator()
        val oldRequest = requests.next()
        requests.next()
        val oldResult = CompletableDeferred<List<String>>()
        var error: String? = null

        val oldJob = launch {
            try {
                oldResult.await()
            } catch (e: Exception) {
                if (requests.isCurrent(oldRequest)) error = e.message
            }
        }
        oldResult.completeExceptionally(IllegalStateException("旧节次失败"))
        oldJob.join()

        assertNull(error)
    }
}
