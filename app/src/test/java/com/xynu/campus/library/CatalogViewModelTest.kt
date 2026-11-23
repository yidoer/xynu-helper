package com.xynu.campus.library

import com.xynu.campus.library.net.CatalogDataSource
import com.xynu.campus.library.net.CatalogRepository
import com.xynu.campus.library.parser.CatalogDetailParser
import com.xynu.campus.library.parser.CatalogSearchParser
import com.xynu.campus.library.vm.CatalogViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 馆藏检索状态机测试。
 *
 * 为什么要测状态机而不是只测解析器：用户真正会踩的坑都在**边界**上——
 * 翻到最后一页点「下一页」、网络失败后点「重试」是不是还停在原来那页、
 * 返回后再进详情会不会闪上一本书的数据。这些和解析器无关，只和状态流转有关。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    // ---------- 假数据源 ----------

    private class FakeSource(
        var pageResult: CatalogRepository.Result<CatalogSearchParser.Page>,
        var detailResult: CatalogRepository.Result<CatalogDetailParser.Detail> =
            CatalogRepository.Result.Ok(emptyDetail()),
    ) : CatalogDataSource {
        val searchCalls = mutableListOf<Triple<String, Int, String>>()
        val detailCalls = mutableListOf<String>()

        override suspend fun search(
            keyword: String,
            pageIndex: Int,
            searchtype: String,
        ): CatalogRepository.Result<CatalogSearchParser.Page> {
            searchCalls += Triple(keyword, pageIndex, searchtype)
            return pageResult
        }

        override suspend fun detail(bookId: String): CatalogRepository.Result<CatalogDetailParser.Detail> {
            detailCalls += bookId
            return detailResult
        }
    }

    companion object {
        private fun emptyDetail() = CatalogDetailParser.Detail(
            null, null, null, null, null, null, null, null, null, null, null, emptyList(),
        )

        private fun page(
            titles: List<String>,
            total: Int? = titles.size,
            pageIndex: Int = 1,
            hasNext: Boolean = false,
        ) = CatalogSearchParser.Page(
            total = total,
            books = titles.map {
                CatalogSearchParser.Book(it, null, null, null, null, null, null)
            },
            pageIndex = pageIndex,
            hasNext = hasNext,
        )
    }

    // ---------- 检索 ----------

    @Test
    fun `blank keyword never hits the network`() = runTest {
        val src = FakeSource(CatalogRepository.Result.Ok(page(listOf("甲"))))
        val vm = CatalogViewModel(src)

        vm.onKeywordChange("   ")
        vm.search()
        advanceUntilIdle()

        assertTrue("空白词不该发出请求", src.searchCalls.isEmpty())
        assertEquals("请输入检索词", vm.state.value.error)
    }

    @Test
    fun `successful search stores page and clears error`() = runTest {
        val src = FakeSource(CatalogRepository.Result.Ok(page(listOf("甲", "乙"), total = 187)))
        val vm = CatalogViewModel(src)

        vm.onKeywordChange(" 逻辑学 ")
        vm.search()
        advanceUntilIdle()

        val s = vm.state.value
        assertFalse(s.loading)
        assertNull(s.error)
        assertEquals(listOf("甲", "乙"), s.books.map { it.title })
        assertEquals(187, s.total)
        assertEquals("检索词应去掉首尾空格", "逻辑学", s.submittedKeyword)
        assertEquals(listOf(Triple("逻辑学", 1, CatalogRepository.SEARCH_TITLE)), src.searchCalls)
    }

    @Test
    fun `selected searchtype is forwarded and remembered`() = runTest {
        val src = FakeSource(CatalogRepository.Result.Ok(page(listOf("甲"))))
        val vm = CatalogViewModel(src)

        vm.onKeywordChange("丁毓峰")
        vm.onSearchtypeChange(CatalogRepository.SEARCH_AUTHOR)
        vm.search()
        advanceUntilIdle()

        assertEquals(CatalogRepository.SEARCH_AUTHOR, src.searchCalls.single().third)
        assertEquals(
            "字段选择要留在 state 里，否则翻页会悄悄换回题名",
            CatalogRepository.SEARCH_AUTHOR,
            vm.state.value.searchtype,
        )
    }

    @Test
    fun `network error keeps the previous page so retry does not lose context`() = runTest {
        val src = FakeSource(CatalogRepository.Result.Ok(page(listOf("甲", "乙"), total = 30, hasNext = true)))
        val vm = CatalogViewModel(src)

        vm.onKeywordChange("逻辑学")
        vm.search()
        advanceUntilIdle()
        assertEquals(2, vm.state.value.books.size)

        // 翻页失败
        src.pageResult = CatalogRepository.Result.NetworkError("馆藏接口不可达：timeout")
        vm.nextPage()
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals("馆藏接口不可达：timeout", s.error)
        assertEquals("失败不能把已有结果清空", 2, s.books.size)
        assertEquals("失败后仍停在原页", 1, s.pageIndex)
        assertEquals("但失败的是第 2 页，重试必须打第 2 页", 2, s.failedPageIndex)

        // 重试用同一词、失败的那一页
        src.pageResult = CatalogRepository.Result.Ok(page(listOf("丙"), total = 30, pageIndex = 2, hasNext = true))
        vm.retry()
        advanceUntilIdle()

        assertEquals(Triple("逻辑学", 2, CatalogRepository.SEARCH_TITLE), src.searchCalls.last())
        assertNull(vm.state.value.error)
        assertNull("成功后失败页标记要清掉", vm.state.value.failedPageIndex)
        assertEquals(listOf("丙"), vm.state.value.books.map { it.title })
    }

    // ---------- 分页边界 ----------

    @Test
    fun `next page is ignored when there is no next page`() = runTest {
        val src = FakeSource(CatalogRepository.Result.Ok(page(listOf("甲"), total = 1, hasNext = false)))
        val vm = CatalogViewModel(src)

        vm.onKeywordChange("逻辑学")
        vm.search()
        advanceUntilIdle()
        val before = src.searchCalls.size

        vm.nextPage()
        advanceUntilIdle()

        assertEquals("最后一页点下一页不该发请求", before, src.searchCalls.size)
        assertFalse(vm.state.value.hasNext)
    }

    @Test
    fun `prev page is ignored on the first page`() = runTest {
        val src = FakeSource(CatalogRepository.Result.Ok(page(listOf("甲"), total = 50, hasNext = true)))
        val vm = CatalogViewModel(src)

        vm.onKeywordChange("逻辑学")
        vm.search()
        advanceUntilIdle()
        val before = src.searchCalls.size

        assertFalse(vm.state.value.hasPrev)
        vm.prevPage()
        advanceUntilIdle()

        assertEquals("第一页点上一页不该发请求", before, src.searchCalls.size)
    }

    @Test
    fun `paging walks forward and backward with the same keyword`() = runTest {
        val src = FakeSource(CatalogRepository.Result.Ok(page(listOf("甲"), total = 50, hasNext = true)))
        val vm = CatalogViewModel(src)

        vm.onKeywordChange("逻辑学")
        vm.search()
        advanceUntilIdle()

        src.pageResult = CatalogRepository.Result.Ok(page(listOf("乙"), total = 50, pageIndex = 2, hasNext = true))
        vm.nextPage()
        advanceUntilIdle()
        assertEquals(2, vm.state.value.pageIndex)
        assertTrue(vm.state.value.hasPrev)

        src.pageResult = CatalogRepository.Result.Ok(page(listOf("甲"), total = 50, pageIndex = 1, hasNext = true))
        vm.prevPage()
        advanceUntilIdle()
        assertEquals(1, vm.state.value.pageIndex)

        assertEquals(
            listOf(1, 2, 1),
            src.searchCalls.map { it.second },
        )
        assertTrue("翻页必须带同一个检索词", src.searchCalls.all { it.first == "逻辑学" })
    }

    // ---------- 详情 ----------

    @Test
    fun `open detail resolves book id from the detail url`() = runTest {
        val src = FakeSource(CatalogRepository.Result.Ok(page(listOf("甲"))))
        val vm = CatalogViewModel(src)

        vm.openDetail(
            "http://222.21.62.201:8011/opac/book/5f01bdf949e01a729cb9c52c164f2f1c",
            "大白话讲透逻辑学",
        )
        advanceUntilIdle()

        assertEquals(listOf("5f01bdf949e01a729cb9c52c164f2f1c"), src.detailCalls)
        assertEquals("大白话讲透逻辑学", vm.state.value.detailTitleHint)
        assertNotNull(vm.state.value.detail)
        assertFalse(vm.state.value.detailLoading)
    }

    @Test
    fun `detail without a usable id reports an error instead of crashing`() = runTest {
        val src = FakeSource(CatalogRepository.Result.Ok(page(listOf("甲"))))
        val vm = CatalogViewModel(src)

        vm.openDetail(null)
        advanceUntilIdle()

        assertTrue(src.detailCalls.isEmpty())
        assertNotNull(vm.state.value.detailError)
        assertTrue(vm.state.value.detailError!!.contains("书目编号"))
    }

    @Test
    fun `detail error is retryable`() = runTest {
        val src = FakeSource(
            pageResult = CatalogRepository.Result.Ok(page(listOf("甲"))),
            detailResult = CatalogRepository.Result.NetworkError("馆藏接口不可达：timeout"),
        )
        val vm = CatalogViewModel(src)

        vm.loadDetail("abc1234567890abc", "某书")
        advanceUntilIdle()
        assertEquals("馆藏接口不可达：timeout", vm.state.value.detailError)

        src.detailResult = CatalogRepository.Result.Ok(emptyDetail())
        vm.retryDetail()
        advanceUntilIdle()

        assertNull(vm.state.value.detailError)
        assertEquals(2, src.detailCalls.size)
        assertTrue("重试用同一个 id", src.detailCalls.all { it == "abc1234567890abc" })
    }

    @Test
    fun `clear detail drops stale data so reopening does not flash the old book`() = runTest {
        val src = FakeSource(CatalogRepository.Result.Ok(page(listOf("甲"))))
        val vm = CatalogViewModel(src)

        vm.loadDetail("abc1234567890abc", "某书")
        advanceUntilIdle()
        assertNotNull(vm.state.value.detail)

        vm.clearDetail()

        assertNull(vm.state.value.detail)
        assertNull(vm.state.value.detailTitleHint)
        assertNull(vm.state.value.detailError)
        assertNull(vm.state.value.detailBookId)
    }

    // ---------- UI 选项 ----------

    @Test
    fun `search type options match what the school entry page offers`() {
        // 入口页只有 题名 / 作者 / 主题词 / 索书号 四个 a 标签；
        // 多给一个（如 ISBN）就会让用户「搜了 0 条但不知道为什么」。
        assertEquals(
            listOf("题名", "作者", "主题词", "索书号"),
            CatalogViewModel.SEARCH_TYPES.map { it.first },
        )
        assertEquals(
            listOf("题名", "作者", "主题词", "索书号"),
            CatalogViewModel.SEARCH_TYPES.map { it.second },
        )
    }
}
