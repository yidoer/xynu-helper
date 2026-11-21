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

private val SectionColors = listOf(
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

@Composable
private fun ScheduleGrid(
    courses: List<Course>,
    currentWeek: Int,
    isSummer: Boolean,
    termStartMillis: Long?,
    todayDayOfWeek: Int,
    actualCurrentWeek: Int?,
    scale: Float,
    onZoom: (Float) -> Unit,
    onWeekChange: (Int) -> Unit,
    onSeasonChange: (Boolean) -> Unit,
    onCourseClick: (Course) -> Unit,
    onDateJumpClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val totalSections = 12
    val times = ScheduleTimes.forSeason(isSummer)
    val maxWeek = remember(courses) {
        (courses.maxOfOrNull { it.endWeek } ?: 20).coerceAtLeast(20)
    }
    val weeks = (1..maxWeek).toList()

    // 缩放后的字号
    fun fs(base: Int): TextUnit = (base * scale).sp

    // 过滤当前周有课的课程
    val weekCourses = remember(courses, currentWeek) {
        courses.filter { c ->
            currentWeek in c.startWeek..c.endWeek &&
                !(c.isOddWeeks && currentWeek % 2 == 0) &&
                !(c.isEvenWeeks && currentWeek % 2 == 1)
        }
    }

    // 当前周是否是用户正在看的周（用于"今天"高亮）
    val isViewingCurrentWeek = actualCurrentWeek != null && currentWeek == actualCurrentWeek

    // 双指捏合缩放：detectTransformGestures 只处理双指手势，单指滚动正常透传
    val pinchModifier = Modifier.pointerInput(Unit) {
        detectTransformGestures { _, _, zoom, _ ->
            onZoom(zoom)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 顶部控制区
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 「第 N 周」本身就是日历入口：点它选日期，自动定位到对应周
                Surface(
                    onClick = onDateJumpClick,
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Transparent
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "第 ${currentWeek} 周",
                            fontSize = fs(15),
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF1C1C1E)
                        )
                        Spacer(Modifier.width(3.dp))
                        Icon(
                            Icons.Filled.CalendarMonth, "按日期跳转",
                            tint = Color(0xFF8E8E93), modifier = Modifier.size(14.dp)
                        )
                    }
                }
                if (isViewingCurrentWeek) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .background(XynuRed.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("本周", fontSize = fs(10), color = XynuRed, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (termStartMillis == null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "未设置开学日期",
                        fontSize = fs(11),
                        color = Color(0xFFB26A00)
                    )
                }
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("作息", fontSize = fs(11), color = Color(0xFF8E8E93))
                    Spacer(Modifier.width(6.dp))
                    FilterChip(
                        selected = isSummer,
                        onClick = { onSeasonChange(true) },
                        label = { Text("夏", fontSize = fs(12)) },
                        modifier = Modifier.height(30.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    FilterChip(
                        selected = !isSummer,
                        onClick = { onSeasonChange(false) },
                        label = { Text("冬", fontSize = fs(12)) },
                        modifier = Modifier.height(30.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    // 缩放控件（放顶栏，不挤占周次条空间）
                    IconButton(
                        onClick = { onZoom(1f / 1.15f) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Text("−", fontSize = 18.sp, color = Color(0xFF3A3A3C))
                    }
                    Text(
                        "${(scale * 100).toInt()}%",
                        fontSize = 10.sp,
                        color = Color(0xFF8E8E93),
                        modifier = Modifier.width(38.dp)
                    )
                    IconButton(
                        onClick = { onZoom(1.15f) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Text("＋", fontSize = 16.sp, color = Color(0xFF3A3A3C))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 周次条（可横滑，独占整行）
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(weeks) { week ->
                        val isCurrent = week == actualCurrentWeek
                        FilterChip(
                            selected = currentWeek == week,
                            onClick = { onWeekChange(week) },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("$week", fontSize = fs(12))
                                    if (isCurrent) {
                                        Spacer(Modifier.width(3.dp))
                                        Box(
                                            Modifier
                                                .size(5.dp)
                                                .background(
                                                    if (currentWeek == week) Color.White else XynuRed,
                                                    CircleShape
                                                )
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.height(28.dp)
                        )
                    }
                }
            }
        }

        // 课表主体（双指缩放 + 单指滚动）
        val dayColWidth = (128 * scale).dp
        val timeColWidth = (58 * scale).dp
        val cellHeight = (88 * scale).dp

        Column(
            modifier = pinchModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            // 表头行（含日期）
            Row {
                Spacer(Modifier.width(timeColWidth))
                (1..7).forEach { day ->
                    val dateCal = PreferencesStore.dateOfWeek(termStartMillis, currentWeek, day)
                    val dateText = if (dateCal != null) {
                        val m = dateCal.get(java.util.Calendar.MONTH) + 1
                        val d = dateCal.get(java.util.Calendar.DAY_OF_MONTH)
                        "%02d-%02d".format(m, d)
                    } else ""
                    val isToday = isViewingCurrentWeek && day == todayDayOfWeek
                    Column(
                        Modifier
                            .width(dayColWidth)
                            .height((36 * scale).dp)
                            .background(
                                if (isToday) XynuRed.copy(alpha = 0.1f) else Color.Transparent,
                                RoundedCornerShape(6.dp)
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            "周${"一二三四五六日"[day - 1]}",
                            fontSize = fs(13),
                            fontWeight = FontWeight.SemiBold,
                            color = when {
                                isToday -> XynuRed
                                day >= 6 -> Color(0xFFB3261E)
                                else -> Color(0xFF1C1C1E)
                            }
                        )
                        if (dateText.isNotEmpty()) {
                            Text(
                                dateText,
                                fontSize = fs(9),
                                color = if (isToday) XynuRed else Color(0xFF8E8E93)
                            )
                        }
                    }
                }
            }

            // 网格主体：固定高度背景 + 课程块叠加层
            // 课程块用 offset 精确定位到对应节次，不再撑高所在行 → 时间轴与课程块严格对齐
            Box(
                modifier = Modifier
                    .width(timeColWidth + 2.dp + dayColWidth * 7)
                    .height(cellHeight * totalSections)
            ) {
                // 背景层：时间列 + 7 天空格子（每行固定 cellHeight，时间轴稳定）
                Column {
                    (1..totalSections).forEach { section ->
                        Row(modifier = Modifier.height(cellHeight)) {
                            val t = times.getOrNull(section - 1)
                            Column(
                                modifier = Modifier.width(timeColWidth).height(cellHeight),
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text("$section", fontSize = fs(12), fontWeight = FontWeight.SemiBold, color = Color(0xFF1C1C1E))
                                if (t != null) {
                                    Text("${t.start}", fontSize = fs(9), color = Color(0xFF8E8E93))
                                    Text("${t.end}", fontSize = fs(9), color = Color(0xFF8E8E93))
                                }
                            }
                            Spacer(Modifier.width(2.dp))
                            (1..7).forEach { day ->
                                val isToday = isViewingCurrentWeek && day == todayDayOfWeek
                                Box(
                                    modifier = Modifier
                                        .width(dayColWidth)
                                        .height(cellHeight)
                                        .padding(2.dp)
                                        .background(
                                            if (isToday) XynuRed.copy(alpha = 0.04f) else Color.White,
                                            RoundedCornerShape(8.dp)
                                        )
                                )
                            }
                        }
                    }
                }

                // 课程叠加层：按 startSection 起、endSection 止精确偏移定位
                weekCourses.forEach { course ->
                    val span = course.endSection - course.startSection + 1
                    CourseCell(
                        course = course,
                        isToday = isViewingCurrentWeek && course.dayOfWeek == todayDayOfWeek,
                        scale = scale,
                        onClick = { onCourseClick(course) },
                        modifier = Modifier
                            .offset(
                                x = timeColWidth + 2.dp + dayColWidth * (course.dayOfWeek - 1),
                                y = cellHeight * (course.startSection - 1)
                            )
                            .width(dayColWidth)
                            .height(cellHeight * span - 2.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            "双指捏合或点 −/+ 缩放 · 左右滑动看星期 · 上下滑动看节次",
            fontSize = fs(11),
            color = Color(0xFF8E8E93),
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun CourseCell(
    course: Course,
    isToday: Boolean,
    scale: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val color = SectionColors[course.colorIndex % SectionColors.size]
    fun fs(base: Int): TextUnit = (base * scale).sp

    Box(
        modifier = modifier
            .padding(2.dp)
            .clickable(onClick = onClick)
            .background(color.copy(alpha = 0.92f), RoundedCornerShape(9.dp))
            .then(
                if (isToday) Modifier.border(2.dp, Color.White, RoundedCornerShape(9.dp))
                else Modifier
            )
            .padding((5 * scale).dp)
    ) {
        Column {
            Text(
                course.name,
                color = Color.White,
                fontSize = fs(13),
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            if (course.location.isNotBlank()) {
                Spacer(Modifier.height((3 * scale).dp))
                Text(
                    course.location,
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = fs(10),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (course.teacher.isNotBlank()) {
                Spacer(Modifier.height((3 * scale).dp))
                Text(
                    course.teacher,
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = fs(10),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 课程详情弹窗 */
@Composable
private fun CourseDetailDialog(
    course: Course,
    isSummer: Boolean,
    onDismiss: () -> Unit
) {
    val color = SectionColors[course.colorIndex % SectionColors.size]
    val times = ScheduleTimes.forSeason(isSummer)
    val startTime = times.getOrNull(course.startSection - 1)?.start ?: ""
    val endTime = times.getOrNull(course.endSection - 1)?.end ?: ""

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(color, CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    course.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (course.teacher.isNotBlank()) {
                    DetailRow("教师", course.teacher)
                }
                if (course.location.isNotBlank()) {
                    DetailRow("地点", course.location)
                }
                DetailRow("星期", "周${"一二三四五六日"[course.dayOfWeek - 1]}")
                DetailRow("节次", "第 ${course.startSection}-${course.endSection} 节")
                if (startTime.isNotEmpty() && endTime.isNotEmpty()) {
                    DetailRow("时间", "$startTime ~ $endTime（${if (isSummer) "夏" else "冬"}季作息）")
                }
                DetailRow("周次", if (course.weeksText.isNotBlank()) course.weeksText else "第 ${course.startWeek}-${course.endWeek} 周")
                if (course.isOddWeeks) DetailRow("模式", "单周上课")
                if (course.isEvenWeeks) DetailRow("模式", "双周上课")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            label,
            fontSize = 12.sp,
            color = Color(0xFF8E8E93),
            modifier = Modifier.width(48.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            value,
            fontSize = 13.sp,
            color = Color(0xFF1C1C1E),
            modifier = Modifier.weight(1f)
        )
    }
}
