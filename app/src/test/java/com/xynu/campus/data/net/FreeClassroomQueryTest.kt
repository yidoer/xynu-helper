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
    fun `week and weekday override the date derived values`() {
        val query = buildFreeClassroomQuery(
            date = LocalDate.of(2026, 11, 20),   // 周五
            section = 3,
            week = 12,
            weekday = 1                            // 显式指定周一
        )

        assertEquals(1, query.xqj)
        assertEquals(12, query.week)
        assertEquals(3, query.section)
        assertTrue(query.display.contains("周一"))
        assertTrue(query.display.contains("第12周"))
        // 选中的不是当天时，不应把今天的日期混进展示
        assertFalse(query.display.contains("2026-11-20"))
    }

    @Test
    fun `display keeps the date when the selected weekday is today`() {
        val query = buildFreeClassroomQuery(LocalDate.of(2026, 11, 20), section = 1)

        assertTrue(query.display.startsWith("2026-11-20 周五"))
    }

    @Test
    fun `week bitmask follows the 2 to the power of n minus 1 encoding`() {
        assertEquals(1L, jwBitmask(listOf(1)))          // 2^0
        assertEquals(2L, jwBitmask(listOf(2)))          // 2^1
        assertEquals(2048L, jwBitmask(listOf(12)))      // 2^11
        assertEquals(3L, jwBitmask(listOf(1, 2)))       // 1 周 + 2 周
        assertEquals(0L, jwBitmask(emptyList()))
        // 越界值被忽略，不会把掩码算歪
        assertEquals(512L, jwBitmask(listOf(10, 0, -3, 99)))
    }

    @Test
    fun `query form uses the real cdjy parameter names and queryModel paging`() {
        val query = buildFreeClassroomQuery(
            date = LocalDate.of(2026, 11, 20),   // 周五
            section = 4,
            week = 12,
            campusId = "FD65E581B6103CDBE05394182BD2FD61",
            term = TermOption("2026", "3", "2026-2027 学年第1学期")
        )
        val form = buildFreeClassroomForm(query).toMap()

        assertEquals("FD65E581B6103CDBE05394182BD2FD61", form["xqh_id"])
        assertEquals("2026", form["xnm"])
        assertEquals("3", form["xqm"])
        assertEquals("0", form["jyfs"])            // 按周次借用
        assertEquals("2048", form["zcd"])          // 第 12 周 -> 2^11
        assertEquals("5", form["xqj"])             // 周五
        assertEquals("8", form["jcd"])             // 第 4 节 -> 2^3
        // 分页只有 queryModel.* 生效；传 jqGrid 的 page/rows 会被忽略
        assertEquals("1000", form["queryModel.showCount"])
        assertEquals("1", form["queryModel.currentPage"])
        assertFalse(form.containsKey("page"))
        assertFalse(form.containsKey("rows"))
        // 旧实现用的字段已经不存在
        assertFalse(form.containsKey("jxlid"))
        assertFalse(form.containsKey("jch"))
        assertFalse(form.containsKey("jcsj"))
    }

    @Test
    fun `HTTP 910 means the module is closed and is never a session expiry`() {
        assertFalse("910 是模块未开放，不是会话失效", isJwSessionExpiredStatus(910))
        assertTrue(isJwModuleUnavailableStatus(910))
        assertTrue(isJwModuleUnavailableStatus(911))

        val error = JwHttpException(910, "http://jwgl.xynu.edu.cn/jwglxt/kbcx/x", "")
        assertEquals(JwHttpErrorKind.MODULE_UNAVAILABLE, error.kind)
        val message = freeClassroomErrorMessage(error)
        assertTrue(message.contains("未向当前账号开放"))
        assertFalse("模块未开放不应提示重新登录", message.contains("重新登录"))
    }

    @Test
    fun `parameter error body is classified separately`() {
        val error = JwHttpException(400, "free", "xqj 参数不合法")

        assertEquals(JwHttpErrorKind.INVALID_QUERY, error.kind)
        assertTrue(freeClassroomErrorMessage(error).startsWith("查询参数不合法"))
    }

    @Test
    fun `status 901 is the only expired education session signal`() {
        assertTrue(isJwSessionExpiredStatus(901))
        assertFalse(isJwSessionExpiredStatus(910))
        assertFalse(isJwSessionExpiredStatus(911))
        assertFalse(isJwSessionExpiredStatus(400))
        assertFalse(isJwSessionExpiredStatus(200))
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
