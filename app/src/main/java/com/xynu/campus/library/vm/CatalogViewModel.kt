package com.xynu.campus.library.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xynu.campus.library.net.CatalogDataSource
import com.xynu.campus.library.net.CatalogRepository
import com.xynu.campus.library.parser.CatalogDetailParser
import com.xynu.campus.library.parser.CatalogSearchParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 馆藏检索（模块内，替代内网 only 的老 OPAC 检索）。
 *
 * 为什么要重做：老 `LibViewModel.search` 打 `222.21.62.201:8011`，那台机器
 * **只对校园网开放**，用户连手机热点时必然 15 秒超时。现在走
 * `libopac.xynu.edu.cn:8080` 的 `/search/searchList` + `/search/bookDetail`，
 * 内外网都能通，所以「查馆藏」「看在馆几本」都能在应用内完成。
 *
 * 与老 `LibViewModel` 的关系：两者共存。老页面（含收藏、续借、我的借阅）依赖
 * OPAC 登录会话，保持不动；本 VM 是**免登录**的公共检索，只做「查得到」这件事。
 */
class CatalogViewModel(
    private val repo: CatalogDataSource = CatalogRepository(),
) : ViewModel() {

    data class UiState(
        /** 输入框里的词（受控，便于「搜完保留」与失败重试）。 */
        val keyword: String = "",
        /** 已提交的检索词（用于空态文案区分「还没搜」和「搜了没结果」）。 */
        val submittedKeyword: String = "",
        val searchtype: String = CatalogRepository.SEARCH_TITLE,
        val loading: Boolean = false,
        val page: CatalogSearchParser.Page? = null,
        val error: String? = null,
        /**
         * 失败的那一页。
         *
         * 为什么要单独记：用户点「下一页」失败时，`page` 还停在旧页，
         * 如果「重试」用 `page.pageIndex` 就会重新请求旧页——按钮写着重试，
         * 实际把用户又退回去了。所以失败页要单独留痕。
         */
        val failedPageIndex: Int? = null,
        /** 详情页状态。 */
        val detail: CatalogDetailParser.Detail? = null,
        val detailLoading: Boolean = false,
        val detailError: String? = null,
        /** 正在看哪本书的详情（用于高亮/标题）。 */
        val detailBookId: String? = null,
        /** 详情页标题兜底：列表里已知的书名，详情还没回来时先显示它。 */
        val detailTitleHint: String? = null,
        /**
         * 拿不到内部书目编号时，上游给的原始详情地址。
         *
         * 用途：给「在浏览器打开」兜底，避免 `bookIdOf` 正则一旦失配就
         * **全部**检索结果都打不开详情、且没有任何出口。
         */
        val detailFallbackUrl: String? = null,
    ) {
        val books: List<CatalogSearchParser.Book> get() = page?.books.orEmpty()
        val total: Int? get() = page?.total
        val pageIndex: Int get() = page?.pageIndex ?: 1
        val hasNext: Boolean get() = page?.hasNext == true
        val hasPrev: Boolean get() = pageIndex > 1

        val canSearch: Boolean get() = keyword.isNotBlank() && !loading
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var detailJob: Job? = null

    fun onKeywordChange(v: String) {
        _state.update { it.copy(keyword = v) }
    }

    fun onSearchtypeChange(v: String) {
        _state.update { it.copy(searchtype = v) }
    }

    /** 首页检索。重复点/空白词直接忽略，避免打空请求。 */
    fun search() {
        val s = _state.value
        val kw = s.keyword.trim()
        if (kw.isEmpty()) {
            _state.update { it.copy(error = "请输入检索词") }
            return
        }
        runSearch(kw, 1, s.searchtype)
    }

    fun nextPage() {
        val s = _state.value
        if (!s.hasNext || s.loading) return
        runSearch(s.submittedKeyword, s.pageIndex + 1, s.searchtype)
    }

    fun prevPage() {
        val s = _state.value
        if (!s.hasPrev || s.loading) return
        runSearch(s.submittedKeyword, s.pageIndex - 1, s.searchtype)
    }

    /** 重试失败的那一页（网络抖动时用）。 */
    fun retry() {
        val s = _state.value
        // 关键：重试必须复活「失败时那一对 (keyword, pageIndex)」的原子快照，
        // 不能把输入框里的**新**词和**旧的**失败页拼在一起。
        //
        // 历史 bug：原来是 `submittedKeyword.ifEmpty { keyword.trim() }` +
        // `failedPageIndex ?: pageIndex`。用户在失败后改了输入框再点重试，
        // 实际仍用旧的 submittedKeyword（以为在搜新词，拿到旧词结果）；
        // 反之若 submittedKeyword 为空而用了新词，target 却可能停在
        // failedPageIndex=3 —— 首次搜索直接跳到第 3 页并显示「没有找到」。
        //
        // 现在的规则很简单：输入框和已提交词不一致 ⇒ 这是一次新搜索，回第 1 页。
        val typed = s.keyword.trim()
        if (typed.isNotEmpty() && typed != s.submittedKeyword) {
            runSearch(typed, 1, s.searchtype)
            return
        }
        val kw = s.submittedKeyword
        if (kw.isEmpty()) return
        val target = s.failedPageIndex ?: s.pageIndex
        runSearch(kw, target, s.searchtype)
    }

    private fun runSearch(keyword: String, pageIndex: Int, searchtype: String) {
        searchJob?.cancel()
        // loading 在 launch **之外**同步置位：
        // 放在协程里会有"取消旧请求 → 新请求还没跑到第一行"的空窗，
        // 这期间用户能再点一次「下一页」，于是发出第三个请求。
        _state.update {
            it.copy(
                loading = true,
                error = null,
                submittedKeyword = keyword,
                searchtype = searchtype,
            )
        }
        searchJob = viewModelScope.launch {
            val r = repo.search(keyword, pageIndex, searchtype)
            // cancel() 只在挂起点生效。若旧请求已越过 repo.search()，
            // 它会在新请求之后恢复并覆写 page/failedPageIndex ——
            // 表现为「页码回跳」或「上一页的结果盖掉当前页」。
            // 所以挂起点之后必须再确认一次自己还是当前请求。
            if (!isActive) return@launch
            when (r) {
                is CatalogRepository.Result.Ok -> _state.update {
                    it.copy(loading = false, page = r.value, error = null, failedPageIndex = null)
                }

                is CatalogRepository.Result.NetworkError -> _state.update {
                    it.copy(loading = false, error = r.message, failedPageIndex = pageIndex)
                }
            }
        }
    }

    /** 打开书目详情（含馆藏复本）。 */
    fun openDetail(detailUrl: String?, titleHint: String? = null) {
        val id = CatalogRepository.bookIdOf(detailUrl)
        if (id == null) {
            // 取不到 id 时不要只报错：手里明明有一个可用的 detailUrl，
            // 却没有出口，用户只能自己回去手打网址。
            // 把 detailUrl 存下来，详情页据此给「在浏览器打开」兜底。
            _state.update {
                it.copy(
                    detailError = "这条结果没有书目编号，打不开内部详情",
                    detailFallbackUrl = detailUrl,
                )
            }
            return
        }
        loadDetail(id, titleHint)
    }

    fun loadDetail(bookId: String, titleHint: String? = null) {
        // 详情与列表共用一个 UiState，三条入口（列表点书 / 详情页重试 /
        // 收藏页直连）可以并发。没有取消就会出现「后写者优先」：
        // 先发的 A 后到，页面显示 A 的内容、顶栏标题是 B、收藏心形按 B 的 id 判断，
        // 用户点收藏会把 A 的元数据存成 B 的 id。
        detailJob?.cancel()
        _state.update {
            it.copy(
                detailLoading = true,
                detailError = null,
                detail = null,
                detailBookId = bookId,
                detailTitleHint = titleHint,
                detailFallbackUrl = null,
            )
        }
        detailJob = viewModelScope.launch {
            val r = repo.detail(bookId)
            if (!isActive) return@launch
            when (r) {
                is CatalogRepository.Result.Ok -> _state.update {
                    it.copy(detailLoading = false, detail = r.value)
                }

                is CatalogRepository.Result.NetworkError -> _state.update {
                    it.copy(detailLoading = false, detailError = r.message)
                }
            }
        }
    }

    fun retryDetail() {
        val s = _state.value
        val id = s.detailBookId ?: return
        loadDetail(id, s.detailTitleHint)
    }

    /** 返回列表时清掉详情态，避免下次进来闪旧数据。 */
    fun clearDetail() {
        _state.update {
            it.copy(
                detail = null,
                detailError = null,
                detailLoading = false,
                detailBookId = null,
                detailTitleHint = null,
            )
        }
    }

    companion object {
        /** UI 上的检索字段选项（顺序与学校入口页一致）。 */
        val SEARCH_TYPES: List<Pair<String, String>> = listOf(
            "题名" to CatalogRepository.SEARCH_TITLE,
            "作者" to CatalogRepository.SEARCH_AUTHOR,
            "主题词" to CatalogRepository.SEARCH_SUBJECT,
            "索书号" to CatalogRepository.SEARCH_CALLNO,
        )
    }
}
