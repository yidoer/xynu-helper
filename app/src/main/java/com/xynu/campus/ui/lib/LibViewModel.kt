package com.xynu.campus.ui.lib

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xynu.campus.data.model.BookDetail
import com.xynu.campus.data.model.BookHolding
import com.xynu.campus.data.model.BookItem
import com.xynu.campus.data.model.BorrowHistoryItem
import com.xynu.campus.data.model.BorrowItem
import com.xynu.campus.data.model.FavoriteBook
import com.xynu.campus.data.model.ReaderStats
import com.xynu.campus.data.net.AppCookieJar
import com.xynu.campus.data.net.HttpClientFactory
import com.xynu.campus.data.net.LibApi
import com.xynu.campus.data.net.SessionExpiredException
import com.xynu.campus.data.repo.CredentialsStore
import com.xynu.campus.data.repo.PreferencesStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LibUiState(
    val loggingIn: Boolean = false,
    val loginMessage: String = "",
    val loggedIn: Boolean = false,
    val checkingLogin: Boolean = false,
    val searching: Boolean = false,
    val searchQuery: String = "",
    val books: List<BookItem> = emptyList(),
    val searchError: String? = null,
    val holdings: Map<String, List<BookHolding>> = emptyMap(),
    val holdingBookId: String? = null,
    val loadingBorrow: Boolean = false,
    val borrowList: List<BorrowItem> = emptyList(),
    val borrowError: String? = null,
    val renewing: String? = null,
    val renewMessage: String? = null,
    // 新增：读者统计 / 借阅历史 / 图书详情 / 收藏
    val stats: ReaderStats? = null,
    val loadingHistory: Boolean = false,
    val historyList: List<BorrowHistoryItem> = emptyList(),
    val historyError: String? = null,
    val loadingDetail: Boolean = false,
    val bookDetail: BookDetail? = null,
    val detailError: String? = null,
    val favorites: List<FavoriteBook> = emptyList(),
    val sessionExpired: Boolean = false
)

class LibViewModel(application: Application) : AndroidViewModel(application) {

    private val api = LibApi(HttpClientFactory.create())
    private val _state = MutableStateFlow(LibUiState())
    val state: StateFlow<LibUiState> = _state.asStateFlow()

    private fun handleSessionExpired(error: SessionExpiredException) {
        AppCookieJar.clear(error.host)
        _state.value = _state.value.copy(
            loggedIn = false,
            sessionExpired = true,
            checkingLogin = false,
            loadingBorrow = false,
            renewing = null,
            renewMessage = null,
            loadingHistory = false,
            loadingDetail = false,
            borrowList = emptyList(),
            historyList = emptyList(),
            stats = null,
            borrowError = null,
            historyError = null,
            detailError = null,
            loginMessage = "图书馆登录已过期，请重新登录"
        )
    }

    fun consumeSessionExpired() {
        _state.value = _state.value.copy(sessionExpired = false)
    }

    init {
        // 持续观察收藏变化（DataStore Flow）
        viewModelScope.launch {
            PreferencesStore.favorites(getApplication()).collect { list ->
                _state.value = _state.value.copy(favorites = list)
            }
        }
        autoLogin()
    }

    fun checkLogin() {
        viewModelScope.launch {
            _state.value = _state.value.copy(checkingLogin = true)
            val ok = try {
                // 用 readerStats 探测：未登录时服务端会重定向到登录页，这里直接抛异常
                withContext(Dispatchers.IO) { api.readerStats() }
                true
            } catch (e: SessionExpiredException) {
                handleSessionExpired(e)
                false
            } catch (_: Exception) {
                false
            }
            if (!_state.value.sessionExpired) {
                _state.value = _state.value.copy(loggedIn = ok, checkingLogin = false)
            }
        }
    }

    fun login(username: String, password: String, saveCredentials: Boolean) {
        if (username.isBlank() || password.isBlank()) {
            _state.value = _state.value.copy(loginMessage = "请输入账号和密码")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(
                loggingIn = true,
                loginMessage = "",
                sessionExpired = false
            )
            val err = try {
                withContext(Dispatchers.IO) { api.login(username.trim(), password) }
            } catch (e: Exception) {
                "登录失败：${e.message ?: "网络异常"}，请检查网络后重试"
            }
            _state.value = _state.value.copy(loggingIn = false)
            if (err == null) {
                _state.value = _state.value.copy(loggedIn = true, loginMessage = "登录成功")
                if (saveCredentials) {
                    launch {
                        try {
                            withContext(Dispatchers.IO) {
                                CredentialsStore.saveLib(getApplication(), username.trim(), password)
                            }
                        } catch (_: Exception) {
                            // 凭据加密保存失败不影响登录
                        }
                    }
                }
                loadStats()
            } else {
                _state.value = _state.value.copy(loginMessage = err)
            }
        }
    }

    fun autoLogin() {
        viewModelScope.launch {
            val cred = try {
                withContext(Dispatchers.IO) {
                    CredentialsStore.libCredentials(getApplication()).first()
                }
            } catch (_: Exception) { null } ?: return@launch
            if (cred.username.isBlank()) return@launch
            _state.value = _state.value.copy(loggingIn = true)
            val err = try {
                withContext(Dispatchers.IO) { api.login(cred.username, cred.password) }
            } catch (e: Exception) {
                "自动登录失败：${e.message ?: "网络异常"}"
            }
            if (err == null) {
                _state.value = _state.value.copy(loggingIn = false, loggedIn = true, loginMessage = "已自动登录")
                loadStats()
            } else {
                try { withContext(Dispatchers.IO) { CredentialsStore.clearLib(getApplication()) } } catch (_: Exception) { }
                _state.value = _state.value.copy(loggingIn = false, loginMessage = err)
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { CredentialsStore.clearLib(getApplication()) }
            AppCookieJar.clear(java.net.URI(api.base).host)
            _state.value = LibUiState()
        }
    }

    fun search(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(searching = true, searchError = null, searchQuery = query)
            try {
                val books = withContext(Dispatchers.IO) { api.searchBooks(query.trim()) }
                _state.value = _state.value.copy(searching = false, books = books)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    searching = false,
                    searchError = e.message ?: "检索失败"
                )
            }
        }
    }

    fun loadHoldings(bookId: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(holdingBookId = bookId)
            try {
                val holdings = withContext(Dispatchers.IO) { api.getHoldings(bookId) }
                _state.value = _state.value.copy(
                    holdings = _state.value.holdings + (bookId to holdings),
                    holdingBookId = null
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(holdingBookId = null)
            }
        }
    }

    fun loadBorrowed() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loadingBorrow = true, borrowError = null)
            try {
                val list = withContext(Dispatchers.IO) { api.myBorrowed() }
                _state.value = _state.value.copy(loadingBorrow = false, borrowList = list, loggedIn = true)
            } catch (e: SessionExpiredException) {
                handleSessionExpired(e)
            } catch (e: Exception) {
                val msg = e.message ?: "加载借阅记录失败"
                // 保留服务端显式未授权的兼容判断；登录页本身由 SessionInterceptor 统一识别。
                val isAuthError = msg.contains("登录") || msg.contains("未授权") ||
                    msg.contains("HTTP 4") || msg.contains("HTTP 302")
                _state.value = _state.value.copy(
                    loadingBorrow = false,
                    borrowError = msg,
                    loggedIn = !isAuthError
                )
                if (isAuthError) {
                    // 顺手清空无效凭据，避免下次自动登录再次失败
                    launch {
                        try {
                            withContext(Dispatchers.IO) {
                                CredentialsStore.clearLib(getApplication())
                            }
                        } catch (_: Exception) { }
                    }
                }
            }
        }
    }

    fun renew(barcode: String, bookId: String, renewUrl: String? = null) {
        viewModelScope.launch {
            _state.value = _state.value.copy(renewing = barcode, renewMessage = null)
            try {
                val msg = withContext(Dispatchers.IO) { api.renew(barcode, bookId, renewUrl) }
                _state.value = _state.value.copy(renewing = null, renewMessage = msg)
                loadBorrowed()  // 刷新
            } catch (e: SessionExpiredException) {
                handleSessionExpired(e)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    renewing = null,
                    renewMessage = "续借失败: ${e.message}"
                )
            }
        }
    }

    // ================ 新增：读者统计 ================

    fun loadStats() {
        viewModelScope.launch {
            try {
                val stats = withContext(Dispatchers.IO) { api.readerStats() }
                _state.value = _state.value.copy(stats = stats, loggedIn = true)
            } catch (e: SessionExpiredException) {
                handleSessionExpired(e)
            } catch (_: Exception) {
                // 静默失败：统计是辅助信息
            }
        }
    }

    // ================ 新增：借阅历史 ================

    fun loadHistory() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loadingHistory = true, historyError = null)
            try {
                val list = withContext(Dispatchers.IO) { api.borrowHistory() }
                _state.value = _state.value.copy(loadingHistory = false, historyList = list)
            } catch (e: SessionExpiredException) {
                handleSessionExpired(e)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loadingHistory = false,
                    historyError = e.message ?: "加载借阅历史失败"
                )
            }
        }
    }

    // ================ 新增：图书详情 ================

    fun loadBookDetail(bookId: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loadingDetail = true, detailError = null, bookDetail = null)
            try {
                val detail = withContext(Dispatchers.IO) { api.getBookDetail(bookId) }
                _state.value = _state.value.copy(loadingDetail = false, bookDetail = detail)
            } catch (e: SessionExpiredException) {
                handleSessionExpired(e)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loadingDetail = false,
                    detailError = e.message ?: "加载图书详情失败"
                )
            }
        }
    }

    // ================ 新增：收藏 ================

    fun toggleFavorite(book: BookItem) {
        viewModelScope.launch {
            val fav = FavoriteBook(
                id = book.id,
                title = book.title,
                author = book.author,
                callNumber = book.callNumber
            )
            try {
                withContext(Dispatchers.IO) {
                    PreferencesStore.toggleFavorite(getApplication(), fav)
                }
            } catch (_: Exception) { }
        }
    }

    fun toggleFavorite(detail: BookDetail) {
        viewModelScope.launch {
            val fav = FavoriteBook(
                id = detail.id,
                title = detail.title,
                author = detail.author,
                callNumber = detail.callNumber
            )
            try {
                withContext(Dispatchers.IO) {
                    PreferencesStore.toggleFavorite(getApplication(), fav)
                }
            } catch (_: Exception) { }
        }
    }

    fun isFavorite(bookId: String): Boolean = _state.value.favorites.any { it.id == bookId }

    fun clearRenewMessage() {
        _state.value = _state.value.copy(renewMessage = null)
    }
}
