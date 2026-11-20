package com.xynu.campus.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xynu.campus.data.model.NewsItem
import com.xynu.campus.data.net.HttpClientFactory
import com.xynu.campus.data.net.NewsApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeUiState(
    val loading: Boolean = false,
    val jwcNews: List<NewsItem> = emptyList(),
    val libNews: List<NewsItem> = emptyList(),
    val error: String? = null
)

class HomeViewModel : ViewModel() {

    private val api = NewsApi(HttpClientFactory.create())
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val (jwc, lib) = withContext(Dispatchers.IO) {
                    val a = runCatching { api.fetchJwcNews() }.getOrDefault(emptyList())
                    val b = runCatching { api.fetchLibNews() }.getOrDefault(emptyList())
                    a to b
                }
                _state.value = _state.value.copy(loading = false, jwcNews = jwc, libNews = lib)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.message ?: "资讯加载失败"
                )
            }
        }
    }
}