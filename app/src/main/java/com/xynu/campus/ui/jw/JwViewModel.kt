package com.xynu.campus.ui.jw

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xynu.campus.data.model.CampusOption
import com.xynu.campus.data.model.Course
import com.xynu.campus.data.model.FreeRoom
import com.xynu.campus.data.model.JwLoginResult
import com.xynu.campus.data.model.ScoreItem
import com.xynu.campus.data.model.TermOption
import com.xynu.campus.data.net.AppCookieJar
import com.xynu.campus.data.net.HttpClientFactory
import com.xynu.campus.data.net.JwApi
import com.xynu.campus.data.net.RequestCoordinator
import com.xynu.campus.data.net.SessionExpiredException
import com.xynu.campus.data.net.buildFreeClassroomQuery
import com.xynu.campus.data.net.freeClassroomErrorMessage
import com.xynu.campus.data.repo.CredentialsStore
import com.xynu.campus.data.repo.PreferencesStore
import java.time.LocalDate
import java.util.Calendar
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
    val rooms: List<FreeRoom> = emptyList(),
    val roomsError: String? = null,
    val roomsQuery: String = "",
    val roomsWeek: Int = 1,
    val roomsWeekday: Int = 1,
    val roomsSection: Int = 1,
    /** 用户选中的查询日期（yyyy-MM-dd）；周次与星期都由它推导，不再单独手选 */
    val roomsDate: String = "",
    /** 当前校区筛选；空串 = 未确定（首次进入尚未读到校区列表时） */
    val roomsCampusId: String = "",
    /** 教务页面提供的校区选项（用于切换筛选） */
    val roomsCampuses: List<CampusOption> = emptyList(),
    val roomsTruncated: Boolean = false,
    /** 首次进入空教室页面时，是否已把日期对齐到「今天」 */
    val roomsAlignedToToday: Boolean = false,
)

/**
 * 教务 ViewModel：直连学校 5 条线路（App 内自动容错，无需自建后端）。
 */
class JwViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        /**
         * 首次进入空教室页面时默认选中的校区：校本部校区。
         *
         * 各校区数据互不重叠且量级差异大（校本部 516 / 淮河 141 / 理工 90），
         * 主校区是绝大多数用户要找的，作为默认值最省一步操作。
         */
        private const val DEFAULT_ROOMS_CAMPUS_ID = "1"
    }

    private val api = JwApi(HttpClientFactory.create())
    private val _state = MutableStateFlow(JwUiState())
    val state: StateFlow<JwUiState> = _state.asStateFlow()
    private var freeRoomsJob: Job? = null
    private val freeRoomsRequests = RequestCoordinator()
    /**
     * 已保存校区的读取完成信号。
     *
     * DataStore 是异步的，首次查询可能抢在「恢复上次选中校区」之前跑完，
     * 那样就会先用空校区查一次、再被判成「首次进入」落到默认校区，
     * 用户上次的选择被无声覆盖。查询先等这个信号，读完再决定用哪个校区。
     */
    private val roomsCampusReady = kotlinx.coroutines.CompletableDeferred<Unit>()
    private var scheduleRequestId = 0L
    private var scheduleTermsRequestId = 0L
    private var scoreTermsRequestId = 0L
    private var scoreRequestId = 0L

    private fun invalidateRequests() {
        freeRoomsJob?.cancel()
        freeRoomsRequests.invalidate()
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
            roomsWeek = 1,
            roomsWeekday = 1,
            roomsSection = 1,
            roomsTruncated = false,
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
        // 恢复空教室上次选中的校区（各区数据独立，没有「全校」可回落）
        viewModelScope.launch {
            try {
                val savedCampus = withContext(Dispatchers.IO) {
                    PreferencesStore.roomsCampusOnce(getApplication())
                }
                if (!savedCampus.isNullOrBlank()) {
                    _state.value = _state.value.copy(roomsCampusId = savedCampus)
                }
            } catch (_: Exception) {
                // 读不到就按「没有历史选择」处理，走默认校区
            } finally {
                roomsCampusReady.complete(Unit)
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

    /**
     * 决定本次查询应该用哪个校区。
     *
     * 规则（与用户确认过）：记住上次选中的校区，首次默认主校区「校本部校区」。
     * 只在 [current] 为空（还没定过校区）时才重新决定，避免每次查询都把用户
     * 手动切换的校区覆盖回去。
     */
    private fun resolveRoomsCampus(campuses: List<CampusOption>, current: String): String {
        val options = campuses.filter { it.id.isNotBlank() }
        if (current.isNotBlank() && options.any { it.id == current }) return current
        return options.firstOrNull { it.id == DEFAULT_ROOMS_CAMPUS_ID }?.id
            ?: options.firstOrNull()?.id
            ?: current
    }

    private fun persistRoomsCampus(campusId: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    PreferencesStore.setRoomsCampus(getApplication(), campusId)
                }
            } catch (_: Exception) { }
        }
    }

    /**
     * 空教室查询：用户只选「哪一天 + 第几节 + 哪个校区」，周次和星期由日期自动推算。
     *
     * 之所以不让用户单独选周次和星期：教务接口要的是 `zcd`（周次位掩码）+
     * `xqj`（星期），这两个值必须互相自洽，手选很容易出现「第 10 周的周二」
     * 这种和真实日历对不上的组合。让用户点日历选日期，再由 App 换算，
     * 既不会选错，也不需要在两个下拉框之间来回对照。
     *
     * 校区不再有「全校」选项：各校区是互不重叠的独立数据集，教务页面的
     * `select#xqh_id` 默认只选中「理工学院」，照抄会漏掉校本部、淮河等校区。
     * 这里的处理是「记住用户上次选的校区，首次默认校本部校区」，
     * 由 [resolveRoomsCampus] 在拿到校区列表后落定。
     */
    fun loadFreeRooms(
        section: Int? = null,
        dateMillis: Long? = null,
        campusId: String? = null
    ) {
        freeRoomsJob?.cancel()
        val requestId = freeRoomsRequests.next()
        val snapshot = _state.value
        val targetSection = (section ?: snapshot.roomsSection).coerceIn(1, 12)

        // 目标日期：显式传入 > 沿用上次选择 > 今天
        val targetDateMillis = dateMillis
            ?: snapshot.roomsDate.toDateMillisOrNull()
            ?: System.currentTimeMillis()
        val targetDate = Calendar.getInstance().apply { timeInMillis = targetDateMillis }

        // 周次由开学日期换算；开学日期未设置时无从换算，退化为第 1 周
        val week = TermCalendar.weekOfTerm(snapshot.termStartMillis, targetDateMillis) ?: 1
        val weekday = TermCalendar.dayOfWeekOf(targetDateMillis)
        val dateText = TermCalendar.isoDate(targetDate)

        freeRoomsJob = viewModelScope.launch {
            // 先等「上次选中校区」读完，否则可能用空校区查一次并覆盖掉用户的选择
            roomsCampusReady.await()
            val targetCampusId = campusId ?: _state.value.roomsCampusId

            val pendingQuery = buildFreeClassroomQuery(
                date = LocalDate.of(
                    targetDate.get(Calendar.YEAR),
                    targetDate.get(Calendar.MONTH) + 1,
                    targetDate.get(Calendar.DAY_OF_MONTH)
                ),
                section = targetSection,
                week = week,
                weekday = weekday,
                campusId = targetCampusId,
                term = null,
                termSource = "正在读取教务当前选项"
            )
            _state.value = _state.value.copy(
                loadingRooms = true,
                roomsError = null,
                roomsSection = targetSection,
                roomsWeek = week,
                roomsWeekday = weekday,
                roomsDate = dateText,
                roomsCampusId = targetCampusId,
                roomsAlignedToToday = true,
                roomsQuery = pendingQuery.display
            )
            try {
                val result = withContext(Dispatchers.IO) {
                    api.fetchFreeClassroomsWithQuery(
                        jc = targetSection,
                        week = week,
                        weekday = weekday,
                        campusId = targetCampusId
                    )
                }
                if (!freeRoomsRequests.isCurrent(requestId)) return@launch

                // 校区是互不重叠的独立数据集，没有「全校」这个概念可用。
                // 首次进入时还不知道有哪些校区（列表随响应返回），先按空校区取一次，
                // 拿到列表后立刻定到默认校区并重查；之后一律沿用已记住的选择。
                val resolvedCampus = resolveRoomsCampus(result.campuses, targetCampusId)
                if (resolvedCampus != targetCampusId) {
                    if (!freeRoomsRequests.isCurrent(requestId)) return@launch
                    _state.value = _state.value.copy(
                        roomsCampuses = result.campuses,
                        roomsCampusId = resolvedCampus
                    )
                    persistRoomsCampus(resolvedCampus)
                    loadFreeRooms(campusId = resolvedCampus)
                    return@launch
                }

                _state.value = _state.value.copy(
                    loadingRooms = false,
                    rooms = result.items,
                    roomsQuery = result.query.display,
                    roomsError = null,
                    roomsCampuses = result.campuses,
                    roomsCampusId = resolvedCampus,
                    roomsTruncated = result.truncated
                )
                // 用户手动切换校区时 resolvedCampus 就等于请求值，走不到上面的重查分支，
                // 所以这里补一次写入——否则「记住上次选的校区」只对自动落默认值生效。
                if (resolvedCampus.isNotBlank()) persistRoomsCampus(resolvedCampus)
            } catch (e: SessionExpiredException) {
                if (freeRoomsRequests.isCurrent(requestId)) handleSessionExpired(e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!freeRoomsRequests.isCurrent(requestId)) return@launch
                _state.value = _state.value.copy(
                    loadingRooms = false,
                    roomsError = freeClassroomErrorMessage(e)
                )
            }
        }
    }
}