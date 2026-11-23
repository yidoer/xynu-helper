package com.xynu.campus.ui.jw

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xynu.campus.data.model.Course
import com.xynu.campus.data.repo.PreferencesStore
import com.xynu.campus.ui.common.AppTopBar
import com.xynu.campus.ui.common.EmptyHint
import com.xynu.campus.ui.common.ErrorHint
import com.xynu.campus.ui.common.LoadingBox
import com.xynu.campus.ui.theme.XynuRed
import java.util.Calendar

internal val SectionColors = listOf(
    Color(0xFFB3261E), Color(0xFF2A6BB0), Color(0xFF2E7D32),
    Color(0xFFB26A00), Color(0xFF7B1FA2), Color(0xFF00695C),
    Color(0xFFC62828), Color(0xFF455A64)
)

private const val MIN_SCALE = 0.65f
private const val MAX_SCALE = 2.2f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    vm: JwViewModel,
    onBack: () -> Unit,
    onNeedLogin: () -> Unit
) {
    val state by vm.state.collectAsState()
    // 当前周：优先用 ViewModel 保存的用户浏览周次（切走再回来保持）；
    // 否则用系统日历推算的周次；系统周次变化时自动跟随。
    var manualWeek by remember { mutableStateOf(state.scheduleWeek) }
    var lastSystemWeek by remember { mutableStateOf(state.currentWeek) }
    LaunchedEffect(state.currentWeek) {
        if (state.currentWeek != lastSystemWeek) {
            // 系统日历推算的周次变了 → 自动跟随，清除手动覆盖
            manualWeek = null
            lastSystemWeek = state.currentWeek
        }
    }
    val currentWeek = manualWeek ?: state.currentWeek ?: 1
    // 夏/冬作息 + 缩放：从 ViewModel 恢复，变化时同步保存
    var isSummer by remember { mutableStateOf(state.scheduleIsSummer) }
    var scale by remember { mutableFloatStateOf(state.scheduleScale) }
    var detailCourse by remember { mutableStateOf<Course?>(null) }
    var showTermStartDialog by remember { mutableStateOf(false) }
    var showDateJump by remember { mutableStateOf(false) }

    // 把 UI 状态变化同步到 ViewModel（保持页面状态）
    fun persistUi(week: Int? = manualWeek, summer: Boolean = isSummer, s: Float = scale) {
        vm.saveScheduleUiState(week, summer, s)
    }

    // 进入页面即加载课表（已登录时）
    LaunchedEffect(Unit) {
        if (state.loggedIn) {
            vm.loadSchedule()
            vm.loadScheduleTerms()
        }
    }
    LaunchedEffect(state.loggedIn) {
        if (state.loggedIn) {
            vm.loadSchedule()
            vm.loadScheduleTerms()
        }
    }

    // 课程详情弹窗
    detailCourse?.let { course ->
        CourseDetailDialog(
            course = course,
            isSummer = isSummer,
            onDismiss = { detailCourse = null }
        )
    }

    // 按日期跳转：选某一天，自动落到那一周。入口在周次条的「第 N 周」胶囊上。
    if (showDateJump) {
        val targetCal = TermCalendar.dateOf(state.termStartMillis, currentWeek, 1)
            ?: TermCalendar.today()
        DateJumpDialog(
            initialMillis = targetCal.timeInMillis,
            requireTermStart = state.termStartMillis == null,
            title = "选择日期，跳到对应周次",
            onPick = { millis ->
                val w = TermCalendar.weekOfTerm(state.termStartMillis, millis)
                if (w != null) {
                    manualWeek = w
                    persistUi(week = w)
                    showDateJump = false
                } else {
                    // 开学日期未设置时无法换算周次，直接引导去设置
                    showDateJump = false
                    showTermStartDialog = true
                }
            },
            onDismiss = { showDateJump = false }
        )
    }

    // 开学日期设置弹窗
    if (showTermStartDialog) {
        TermStartPickerDialog(
            currentMillis = state.termStartMillis,
            onConfirm = { millis ->
                vm.setTermStart(millis)
                showTermStartDialog = false
            },
            onDismiss = { showTermStartDialog = false }
        )
    }

    val zoom: (Float) -> Unit = { factor ->
        scale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
    }

    Scaffold(
        containerColor = Color(0xFFF5F6FA),
        topBar = {
            AppTopBar(
                title = "我的课表",
                onBack = onBack,
                action = {
                    // 开学日期设置
                    IconButton(
                        onClick = { showTermStartDialog = true },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(Icons.Filled.Settings, "开学日期", tint = Color(0xFF3A3A3C), modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = { if (state.loggedIn) vm.loadSchedule() }, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.Filled.Refresh, "刷新", tint = Color(0xFF3A3A3C), modifier = Modifier.size(20.dp))
                    }
                }
            )
        }
    ) { padding ->
        when {
            !state.loggedIn -> {
                Column(
                    Modifier.fillMaxSize().padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("🔒", fontSize = 40.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("请先登录教务系统", color = Color.Gray)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onNeedLogin) { Text("去登录") }
                }
            }
            state.loadingSchedule -> LoadingBox(Modifier.padding(padding), "正在加载课表…")
            state.scheduleError != null -> ErrorHint(state.scheduleError!!, onRetry = { vm.loadSchedule() }, modifier = Modifier.padding(padding))
            state.schedule.isEmpty() -> EmptyHint("暂无课表数据\n若网页版课表正常，请确认已选课", Modifier.padding(padding))
            else -> ScheduleGrid(
                courses = state.schedule,
                currentWeek = currentWeek,
                isSummer = isSummer,
                termStartMillis = state.termStartMillis,
                todayDayOfWeek = state.todayDayOfWeek,
                actualCurrentWeek = state.currentWeek,
                scale = scale,
                onZoom = { f ->
                    zoom(f)
                    persistUi(s = (scale * f).coerceIn(MIN_SCALE, MAX_SCALE))
                },
                onWeekChange = {
                    manualWeek = it
                    persistUi(week = it)
                },
                onSeasonChange = {
                    isSummer = it
                    persistUi(summer = it)
                },
                onCourseClick = { detailCourse = it },
                onDateJumpClick = { showDateJump = true },
                modifier = Modifier.padding(padding)
            )
        }
    }
}
