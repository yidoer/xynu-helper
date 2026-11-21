package com.xynu.campus.ui.jw

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xynu.campus.data.model.FreeRoom
import com.xynu.campus.ui.common.AppTopBar
import com.xynu.campus.ui.common.EmptyHint
import com.xynu.campus.ui.common.ErrorHint
import com.xynu.campus.ui.common.LoadingBox
import com.xynu.campus.ui.theme.XynuRed
import kotlinx.coroutines.launch

/**
 * 空教室查询。
 *
 * 真实接口是正方 V9 的 `cdjy/cdjy_cxKxcdlb.html`（空闲场地），服务端要的是
 * 「周次位掩码 + 星期 + 节次位掩码」。这里让用户直接选**日期**，周次与星期
 * 由 [TermCalendar] 从开学日期换算，避免手选周次与星期时出现对不上的组合。
 *
 * 布局取舍（为什么不是把筛选条全铺在页面顶部）：
 *  - 页面上方原本堆了「日期条 + 节次 12 个 chip + 校区 chip + 查询条件说明」四行，
 *    在 1080×1920 的机器上占掉约 1/5 屏高，真正能看结果的区域被压得很窄；
 *  - 而「节次」12 个、「校区」4 个属于一次性设好就不再动的条件，
 *    把它们常驻在列表上方，等于用固定成本换很低的收益。
 * 所以改成移动端通行的两层结构：**顶部一行可读的条件摘要（点开即筛选）+
 * 底部抽屉承载全部筛选项**，把纵向空间还给结果列表。参考优设《移动端筛选交互
 * 设计》对「顶部下拉抽屉 / 底部上拉抽屉」的拆解：筛选入口要常驻可见、
 * 当前生效条件要随时可感知，两者都满足，同时不挤占内容区。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeClassroomScreen(
    vm: JwViewModel,
    onBack: () -> Unit,
    onNeedLogin: () -> Unit
) {
    val state by vm.state.collectAsState()
    var showFilterSheet by remember { mutableStateOf(false) }
    // 教学楼是纯客户端视图过滤（接口没有这个查询参数），所以留在 UI 层，
    // 不影响 ViewModel 的请求状态，切换时不需要重新联网。
    var buildingFilter by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.loggedIn) {
        if (state.loggedIn && state.roomsAlignedToToday.not() && !state.loadingRooms) {
            vm.loadFreeRooms()
        }
    }

    // 换校区/换日期后，原来选中的教学楼可能已经不存在，避免留下空列表
    LaunchedEffect(state.rooms) {
        if (buildingFilter.isNotBlank() && state.rooms.none { it.buildingOrOther() == buildingFilter }) {
            buildingFilter = ""
        }
    }

    // 选完日期就收起抽屉，用户能立刻看到结果变化
    fun pickDateThenClose(millis: Long) {
        vm.loadFreeRooms(dateMillis = millis)
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) showFilterSheet = false
        }
    }

    if (showFilterSheet) {
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            sheetState = sheetState,
            containerColor = Color.White,
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            FilterSheetContent(
                state = state,
                buildingFilter = buildingFilter,
                onBuilding = { buildingFilter = it },
                onSection = { vm.loadFreeRooms(section = it) },
                onDate = { pickDateThenClose(it) },
                onCampus = {
                    buildingFilter = ""
                    vm.loadFreeRooms(campusId = it)
                },
                onJumpToday = {
                    vm.loadFreeRooms(dateMillis = System.currentTimeMillis())
                },
                onDone = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                        if (!sheetState.isVisible) showFilterSheet = false
                    }
                }
            )
        }
    }

    Scaffold(
        containerColor = Color(0xFFF5F6FA),
        topBar = {
            AppTopBar(
                title = "空教室查询",
                onBack = onBack,
                action = {
                    IconButton(
                        onClick = { if (state.loggedIn) vm.loadFreeRooms() },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            Icons.Filled.Refresh, "刷新",
                            tint = Color(0xFF3A3A3C), modifier = Modifier.size(20.dp)
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (!state.loggedIn) {
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
            return@Scaffold
        }

        Column(Modifier.fillMaxSize().padding(padding)) {
            // 顶部只保留一条可点的条件摘要：一眼看清「看的是哪天的哪一节、哪个校区」
            FilterSummaryBar(
                state = state,
                buildingFilter = buildingFilter,
                onOpen = { showFilterSheet = true }
            )

            when {
                state.loadingRooms -> LoadingBox(Modifier.weight(1f), "正在查询空教室…")
                state.roomsError != null -> ErrorHint(
                    state.roomsError!!,
                    onRetry = { vm.loadFreeRooms() },
                    modifier = Modifier.weight(1f),
                    onSecondary = onNeedLogin
                )
                state.rooms.isEmpty() -> EmptyHint("该周次、星期和节次暂无空闲教室", Modifier.weight(1f))
                else -> RoomList(
                    rooms = remember(state.rooms, buildingFilter) {
                        if (buildingFilter.isBlank()) state.rooms
                        else state.rooms.filter { it.buildingOrOther() == buildingFilter }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * 条件摘要条：把当前生效的三个条件压成一行，点击展开筛选抽屉。
 *
 * 之所以不直接把 chip 平铺：12 个节次 + 4 个校区必定要横向滑动，滑动条本身
 * 又会被右边内容挤到贴边（此前「周次条被裁掉」就是这个原因）。摘要条只有一个
 * 高度 44dp 的可点区域，既符合「筛选入口常驻可见」，也不抢内容的纵向空间。
 */
@Composable
private fun FilterSummaryBar(
    state: JwUiState,
    buildingFilter: String,
    onOpen: () -> Unit
) {
    val campusName = remember(state.roomsCampuses, state.roomsCampusId) {
        state.roomsCampuses.firstOrNull { it.id == state.roomsCampusId }?.name ?: "选择校区"
    }
    val dateLabel = remember(state.roomsDate, state.roomsWeek, state.roomsWeekday) {
        val md = state.roomsDate.takeLast(5).ifBlank { "--" }
        "第${state.roomsWeek}周 ${TermCalendar.weekdayLabel(state.roomsWeekday)} $md"
    }

    Surface(color = Color.White, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Tune, "筛选",
                tint = XynuRed, modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                // 条件变化时做一次淡入，用户能感知到「筛选确实生效了」
                Crossfade(
                    targetState = "$campusName｜$dateLabel｜第${state.roomsSection}节",
                    animationSpec = tween(180),
                    label = "summary"
                ) { key ->
                    val parts = key.split("｜")
                    Text(
                        parts.getOrElse(0) { "" },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1C1C1E),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    if (buildingFilter.isBlank()) "$dateLabel · 第${state.roomsSection}节"
                    else "$dateLabel · 第${state.roomsSection}节 · $buildingFilter",
                    fontSize = 11.sp,
                    color = Color(0xFF8E8E93),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "筛选",
                fontSize = 12.sp,
                color = XynuRed,
                fontWeight = FontWeight.Medium
            )
            Icon(
                Icons.Filled.ExpandMore, null,
                tint = XynuRed, modifier = Modifier.size(16.dp)
            )
        }
    }
}

/** 底部抽屉：日期 / 节次 / 校区 / 教学楼 集中在这里选 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FilterSheetContent(
    state: JwUiState,
    buildingFilter: String,
    onBuilding: (String) -> Unit,
    onSection: (Int) -> Unit,
    onDate: (Long) -> Unit,
    onCampus: (String) -> Unit,
    onJumpToday: () -> Unit,
    onDone: () -> Unit
) {
    var showDatePicker by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    // 抽屉内容可能比屏幕高（四个维度 + 日期弹窗），整体可滚动
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 560.dp)
            .verticalScroll(scroll)
            .padding(bottom = 24.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "筛选条件",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1C1C1E)
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDone) { Text("完成") }
        }

        // —— 日期 ——
        FilterSectionTitle("日期")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = { showDatePicker = true },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(dateDisplay(state), fontSize = 14.sp)
            }
            Spacer(Modifier.width(8.dp))
            AssistChip(
                onClick = onJumpToday,
                label = { Text("今天", fontSize = 12.sp) }
            )
        }

        // —— 节次 ——
        // 12 个节次用 FlowRow 折成两行，避免横向滑动时后半段（第 7-12 节）
        // 藏在屏幕外——「看不见的选项等于不存在」。
        FilterSectionTitle("节次")
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            (1..12).forEach { value ->
                val selected = state.roomsSection == value
                FilterChip(
                    selected = selected,
                    onClick = { if (!selected) onSection(value) },
                    label = { Text("第${value}节", fontSize = 12.sp) }
                )
            }
        }

        // —— 校区（各区数据独立，因此不提供「全校」）——
        FilterSectionTitle("校区")
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            state.roomsCampuses.filter { it.id.isNotBlank() }.forEach { campus ->
                val selected = state.roomsCampusId == campus.id
                FilterChip(
                    selected = selected,
                    onClick = { if (!selected) onCampus(campus.id) },
                    label = { Text(campus.name, fontSize = 12.sp) }
                )
            }
        }

        // —— 教学楼（客户端过滤，只影响浏览，不重新联网）——
        val buildings = remember(state.rooms) {
            state.rooms.map { it.buildingOrOther() }
                .distinct()
                .sortedBy { naturalKey(it) }
        }
        if (buildings.size > 1) {
            FilterSectionTitle("教学楼（当前 ${state.rooms.size} 间 / ${buildings.size} 栋）")
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                FilterChip(
                    selected = buildingFilter.isBlank(),
                    onClick = { if (buildingFilter.isNotBlank()) onBuilding("") },
                    label = { Text("全部楼栋", fontSize = 12.sp) }
                )
                buildings.forEach { building ->
                    val selected = buildingFilter == building
                    FilterChip(
                        selected = selected,
                        onClick = { onBuilding(if (selected) "" else building) },
                        label = { Text(building, fontSize = 12.sp) }
                    )
                }
            }
        }
    }

    if (showDatePicker) {
        DateJumpDialog(
            initialMillis = state.roomsDate.toDateMillisOrNull(),
            requireTermStart = state.termStartMillis == null,
            title = "选择查询日期",
            onPick = { millis ->
                showDatePicker = false
                onDate(millis)
            },
            onDismiss = { showDatePicker = false }
        )
    }
}

@Composable
private fun FilterSectionTitle(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = Color(0xFF6C6C70),
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp)
    )
}

private fun dateDisplay(state: JwUiState): String {
    if (state.roomsDate.isBlank()) return "选择日期"
    return "${state.roomsDate} ${TermCalendar.weekdayLabel(state.roomsWeekday)}"
}

/**
 * 结果列表：按**教学楼**分组。
 *
 * 用户找教室的实际路径是「我去综合教学楼 → 哪间空着」，而不是在 500 多条里
 * 逐条扫。分组后每组给出数量，配合吸顶标题，长列表也能快速定位。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun RoomList(rooms: List<FreeRoom>, modifier: Modifier = Modifier) {
    // 组内保持接口返回的编号顺序；组间按「楼栋名 + 数字」自然排序，
    // 这样「综合教学楼2」排在「综合教学楼3」前面，而不是按字符串排到后面。
    // 未标注楼栋的归到最后一组。
    val groups = remember(rooms) {
        rooms.groupBy { it.buildingOrOther() }
            .toList()
            .sortedWith(
                compareBy(
                    { if (it.first == "其他场地") 1 else 0 },
                    { naturalKey(it.first) }
                )
            )
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "__count__") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "共 ${rooms.size} 间空闲场地 · ${groups.size} 栋楼",
                    fontSize = 12.sp,
                    color = Color(0xFF6C6C70)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "按教学楼分组",
                    fontSize = 11.sp,
                    color = Color(0xFF8E8E93)
                )
            }
        }
        groups.forEach { (building, list) ->
            stickyHeader(key = "h_$building") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF5F6FA))
                        .padding(horizontal = 2.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(width = 3.dp, height = 13.dp)
                            .background(XynuRed, RoundedCornerShape(2.dp))
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        building,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1C1C1E)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${list.size} 间",
                        fontSize = 11.sp,
                        color = Color(0xFF8E8E93)
                    )
                }
            }
            items(list, key = { it.code.ifBlank { it.name } }) { room ->
                RoomCard(room)
            }
        }
    }
}

/** 楼栋名，缺失时归到「其他场地」，避免分组和筛选用空串当 key */
private fun FreeRoom.buildingOrOther(): String = building.ifBlank { "其他场地" }

/**
 * 楼栋名排序键：把名字里的数字补零到固定宽度，使「综合教学楼2」排在
 * 「综合教学楼10」之前（直接按字符串排会得到 10 < 2 的错误顺序）。
 */
private fun naturalKey(name: String): String =
    Regex("\\d+").replace(name) { m -> m.value.padStart(4, '0') }

@Composable
private fun RoomCard(room: FreeRoom) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        room.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF1C1C1E)
                    )
                    if (room.category.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFFEAF3FF)
                        ) {
                            Text(
                                room.category,
                                fontSize = 10.sp,
                                color = Color(0xFF1565C0),
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                if (room.subtitle.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(room.subtitle, fontSize = 11.sp, color = Color(0xFF8E8E93))
                }
            }
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "空闲",
                tint = Color(0xFF2E7D32),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
