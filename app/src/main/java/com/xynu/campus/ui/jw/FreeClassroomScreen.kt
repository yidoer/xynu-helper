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
