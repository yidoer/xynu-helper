package com.xynu.campus.ui.jw

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xynu.campus.data.model.Course
import com.xynu.campus.data.model.JwLoginResult
import com.xynu.campus.data.model.ScoreItem
import com.xynu.campus.data.model.TermOption
import com.xynu.campus.data.net.AppCookieJar
import com.xynu.campus.data.net.HttpClientFactory
import com.xynu.campus.data.net.JwApi
import com.xynu.campus.data.net.SessionExpiredException
import com.xynu.campus.data.net.buildFreeClassroomQuery
import com.xynu.campus.data.net.isCurrentFreeRoomsRequest
import com.xynu.campus.data.repo.CredentialsStore
import com.xynu.campus.data.repo.PreferencesStore
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class JwUiState(
    val loggingIn: Boolean = false,
    val loginMessage: String = "",
    val savingCredentials: Boolean = false,
    val loadingSchedule: Boolean = false,
    val schedule: List<Course> = emptyList(),
    val scheduleError: String? = null,
    val loadingScores: Boolean = false,
    val scores: List<ScoreItem> = emptyList(),
    val scoreError: String? = null,
    val terms: List<TermOption> = emptyList(),
    val selectedTerm: TermOption? = null,
    // 课表学期独立选择（与成绩学期分离）
    val scheduleTerm: TermOption? = null,
    val scheduleTerms: List<TermOption> = emptyList(),
    // 课表 UI 状态（保持页面最后打开的状态）
    val scheduleWeek: Int? = null,         // 用户最后浏览的周次
    val scheduleIsSummer: Boolean = true,  // 夏/冬作息
    val scheduleScale: Float = 1f,         // 缩放比例
    // 开学日期（毫秒），null 表示未设置
    val termStartMillis: Long? = null,
    val currentWeek: Int? = null,
    val todayDayOfWeek: Int = 1,
    val loggedIn: Boolean = false,
    val checkingLogin: Boolean = false,
    val sessionExpired: Boolean = false,
    // 空教室
    val loadingRooms: Boolean = false,
    val rooms: List<String> = emptyList(),
    val roomsError: String? = null,
    val roomsQuery: String = "",
)

/**
 * 教务 ViewModel：直连学校 5 条线路（App 内自动容错，无需自建后端）。
 */
class JwViewModel(application: Application) : AndroidViewModel(application) {

    private val api = JwApi(HttpClientFactory.create())
    private val _state = MutableStateFlow(JwUiState())
    val state: StateFlow<JwUiState> = _state.asStateFlow()
    private var freeRoomsJob: Job? = null
    private var freeRoomsRequestId = 0L
    private var scheduleRequestId = 0L
    private var scheduleTermsRequestId = 0L
    private var scoreTermsRequestId = 0L
    private var scoreRequestId = 0L

    private fun invalidateRequests() {
        freeRoomsJob?.cancel()
        freeRoomsRequestId++
        scheduleRequestId++
        scheduleTermsRequestId++
        scoreTermsRequestId++
        scoreRequestId++
    }

    private fun handleSessionExpired(error: SessionExpiredException) {
        invalidateRequests()
        AppCookieJar.clear(error.host)
        _state.value = _state.value.copy(
            loggedIn = false,
            sessionExpired = true,
            loadingSchedule = false,
            loadingScores = false,
            loadingRooms = false,
            schedule = emptyList(),
            scores = emptyList(),
            terms = emptyList(),
            rooms = emptyList(),
            scheduleError = null,
            scoreError = null,
            roomsError = null,
            roomsQuery = "",
            loginMessage = "教务系统登录已过期，请重新登录"
        )
    }

    fun consumeSessionExpired() {
        _state.value = _state.value.copy(sessionExpired = false)
    }

    init {
        // 加载开学日期 + 按系统日历计算当前周 + 星期，并持续刷新（跨天/跨周自动跳）
        viewModelScope.launch {
            val start = try {
                withContext(Dispatchers.IO) { PreferencesStore.termStartOnce(getApplication()) }
            } catch (_: Exception) { null }
            while (true) {
                // 每次都从 state 读最新的开学日期（用户可能在设置页改过）
                val currentStart = _state.value.termStartMillis ?: start
                val week = PreferencesStore.currentWeek(currentStart)
                val dow = PreferencesStore.todayDayOfWeek()
                _state.value = _state.value.copy(
                    termStartMillis = currentStart,
                    currentWeek = week,
                    todayDayOfWeek = dow
                )
                // 每 60 秒用系统日历重算一次；App 在前台跨天/跨周时自动更新周次与今日高亮
                delay(60_000L)
            }
        }
        // 恢复用户上次选中的课表学期
        viewModelScope.launch {
            val saved = try {
                withContext(Dispatchers.IO) { PreferencesStore.selectedTerm(getApplication()) }
            } catch (_: Exception) { null }
            if (saved != null) {
                val t = TermOption(saved.first, saved.second, saved.third)
                _state.value = _state.value.copy(scheduleTerm = t)
            }
        }
        // 有已存凭据则自动登录，登录成功后自动加载课表
        autoLogin()
    }

    fun login(username: String, password: String, saveCredentials: Boolean) {
        if (username.isBlank() || password.isBlank()) {
            _state.value = _state.value.copy(loginMessage = "请输入学号和密码")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(
                loggingIn = true,
                loginMessage = "",
                sessionExpired = false
            )
            val result = try {
                withContext(Dispatchers.IO) { api.login(username.trim(), password) }
            } catch (e: Exception) {
                JwLoginResult(false, "登录失败：${e.message ?: "网络异常"}，请检查网络后重试")
            }
            _state.value = _state.value.copy(
                loggingIn = false,
                loginMessage = result.message
            )
            if (result.success) {
                _state.value = _state.value.copy(loggedIn = true, loginMessage = "登录成功")
                if (saveCredentials) {
                    launch {
                        try {
                            withContext(Dispatchers.IO) {
                                CredentialsStore.saveJw(getApplication(), username.trim(), password)
                            }
                        } catch (_: Exception) { }
                    }
                }
            }
        }
    }

    /** 自动使用已存凭据登录 */
    fun autoLogin() {
        viewModelScope.launch {
            val cred = try {
                withContext(Dispatchers.IO) {
                    CredentialsStore.jwCredentials(getApplication()).first()
                }
            } catch (_: Exception) { null } ?: return@launch
            if (cred.username.isBlank()) return@launch
            _state.value = _state.value.copy(loggingIn = true)
            val result = try {
                withContext(Dispatchers.IO) { api.login(cred.username, cred.password) }
            } catch (e: Exception) {
                JwLoginResult(false, "自动登录失败：${e.message ?: "网络异常"}")
            }
            if (result.success) {
                _state.value = _state.value.copy(loggingIn = false, loggedIn = true)
                loadSchedule()
            } else {
                try { withContext(Dispatchers.IO) { CredentialsStore.clearJw(getApplication()) } } catch (_: Exception) { }
                _state.value = _state.value.copy(loggingIn = false, loginMessage = result.message)
            }
        }
    }

    fun logout() {
        invalidateRequests()
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { CredentialsStore.clearJw(getApplication()) } } catch (_: Exception) { }
            api.hosts.forEach(AppCookieJar::clear)
            _state.value = JwUiState()
        }
    }

    fun loadSchedule(term: TermOption? = null) {
        val useTerm = term ?: _state.value.scheduleTerm
        val requestId = ++scheduleRequestId
        viewModelScope.launch {
            _state.value = _state.value.copy(loadingSchedule = true, scheduleError = null)
            try {
                val list = withContext(Dispatchers.IO) {
                    if (useTerm != null) api.fetchSchedule(useTerm.xnm, useTerm.xqm)
                    else api.fetchSchedule()
                }
                if (requestId != scheduleRequestId) return@launch
                _state.value = _state.value.copy(
                    loadingSchedule = false,
                    schedule = list,
                    scheduleTerm = useTerm ?: _state.value.scheduleTerm
                )
            } catch (e: SessionExpiredException) {
                if (requestId == scheduleRequestId) handleSessionExpired(e)
            } catch (e: Exception) {
                if (requestId != scheduleRequestId) return@launch
                _state.value = _state.value.copy(
                    loadingSchedule = false,
                    scheduleError = e.message ?: "加载课表失败"
                )
            }
        }
    }

    /** 切换课表学期 */
    fun selectScheduleTerm(term: TermOption) {
        _state.value = _state.value.copy(scheduleTerm = term)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    PreferencesStore.setSelectedTerm(getApplication(), term.xnm, term.xqm, term.label)
                }
            } catch (_: Exception) { }
        }
        loadSchedule(term)
    }

    /** 加载课表学期候选 */
    fun loadScheduleTerms() {
        val requestId = ++scheduleTermsRequestId
        viewModelScope.launch {
            try {
                val terms = withContext(Dispatchers.IO) { api.fetchTermOptions() }
                if (requestId != scheduleTermsRequestId) return@launch
                _state.value = _state.value.copy(scheduleTerms = terms)
            } catch (e: SessionExpiredException) {
                if (requestId == scheduleTermsRequestId) handleSessionExpired(e)
            } catch (_: Exception) { }
        }
    }

    /** 保存课表 UI 状态（用户切走/返回时保持） */
    fun saveScheduleUiState(week: Int?, isSummer: Boolean, scale: Float) {
        _state.value = _state.value.copy(
            scheduleWeek = week,
            scheduleIsSummer = isSummer,
            scheduleScale = scale
        )
    }

    /** 设置开学日期 */
    fun setTermStart(millis: Long) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    PreferencesStore.setTermStart(getApplication(), millis)
                }
            } catch (_: Exception) { }
            val week = PreferencesStore.currentWeek(millis)
            _state.value = _state.value.copy(
                termStartMillis = millis,
                currentWeek = week
            )
        }
    }

    fun loadTerms() {
        val requestId = ++scoreTermsRequestId
        viewModelScope.launch {
            // 先恢复用户上次选中的成绩学期
            val saved = try {
                withContext(Dispatchers.IO) { PreferencesStore.selectedScoreTerm(getApplication()) }
            } catch (_: Exception) { null }
            val savedTerm = saved?.let { TermOption(it.first, it.second, it.third) }

            try {
                val terms = withContext(Dispatchers.IO) { api.fetchTermOptions() }
                if (requestId != scoreTermsRequestId) return@launch
                // 优先用保存的学期（在 terms 列表里能找到的话），否则用第一个
                val initial = savedTerm?.let { s -> terms.firstOrNull { it.xnm == s.xnm && it.xqm == s.xqm } }
                    ?: terms.firstOrNull()
                    ?: TermOption(
                        "${java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) - 1}",
                        "3", "当前学期"
                    )
                _state.value = _state.value.copy(terms = terms, selectedTerm = initial)
                loadScores(initial)
            } catch (e: SessionExpiredException) {
                if (requestId == scoreTermsRequestId) handleSessionExpired(e)
            } catch (_: Exception) {
                if (requestId != scoreTermsRequestId) return@launch
                // 学期获取失败：兜底用当前学年
                val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
                val terms = listOf(
                    TermOption("${year - 1}", "3", "${year - 1}-${year} 学年第1学期"),
                    TermOption("${year - 1}", "12", "${year - 1}-${year} 学年第2学期"),
                )
                val first = savedTerm?.let { s -> terms.firstOrNull { it.xnm == s.xnm && it.xqm == s.xqm } }
                    ?: terms.firstOrNull()
                _state.value = _state.value.copy(terms = terms, selectedTerm = first)
                first?.let { loadScores(it) }
            }
        }
    }

    fun selectTerm(term: TermOption) {
        scoreTermsRequestId++
        _state.value = _state.value.copy(selectedTerm = term)
        // 持久化成绩学期选择，下次进入成绩页时恢复
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    PreferencesStore.setSelectedScoreTerm(getApplication(), term.xnm, term.xqm, term.label)
                }
            } catch (_: Exception) { }
        }
        loadScores(term)
    }

    fun loadScores(term: TermOption) {
        val requestId = ++scoreRequestId
        viewModelScope.launch {
            _state.value = _state.value.copy(loadingScores = true, scoreError = null)
            try {
                val list = withContext(Dispatchers.IO) { api.fetchScores(term.xnm, term.xqm) }
                if (requestId != scoreRequestId) return@launch
                _state.value = _state.value.copy(loadingScores = false, scores = list)
            } catch (e: SessionExpiredException) {
                if (requestId == scoreRequestId) handleSessionExpired(e)
            } catch (e: Exception) {
                if (requestId != scoreRequestId) return@launch
                _state.value = _state.value.copy(
                    loadingScores = false,
                    scoreError = e.message ?: "加载成绩失败"
                )
            }
        }
    }

    /** 空教室查询；新请求会取消并取代旧请求。 */
    fun loadFreeRooms(jc: Int = 1) {
        freeRoomsJob?.cancel()
        val requestId = ++freeRoomsRequestId
        val pendingQuery = buildFreeClassroomQuery(LocalDate.now(), jc, term = null, termSource = "正在读取教务当前选项")
        freeRoomsJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                loadingRooms = true,
                roomsError = null,
                roomsQuery = pendingQuery.display
            )
            try {
                val result = withContext(Dispatchers.IO) { api.fetchFreeClassroomsWithQuery(jc) }
                if (!isCurrentFreeRoomsRequest(requestId, freeRoomsRequestId)) return@launch
                _state.value = _state.value.copy(
                    loadingRooms = false,
                    rooms = result.rooms,
                    roomsQuery = result.query.display,
                    roomsError = null
                )
            } catch (e: SessionExpiredException) {
                if (isCurrentFreeRoomsRequest(requestId, freeRoomsRequestId)) handleSessionExpired(e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isCurrentFreeRoomsRequest(requestId, freeRoomsRequestId)) return@launch
                _state.value = _state.value.copy(
                    loadingRooms = false,
                    roomsError = e.message ?: "空教室查询失败"
                )
            }
        }
    }
}