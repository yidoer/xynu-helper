package com.xynu.campus.ui.jw

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xynu.campus.data.model.CampusOption
import com.xynu.campus.data.model.FreeRoom
import com.xynu.campus.ui.common.AppTopBar
import com.xynu.campus.ui.common.EmptyHint
import com.xynu.campus.ui.common.ErrorHint
import com.xynu.campus.ui.common.LoadingBox
import com.xynu.campus.ui.theme.XynuRed

/**
 * 空教室查询。
 *
 * 真实接口是正方 V9 的 `cdjy/cdjy_cxKxcdlb.html`（空闲场地），服务端要的是
 * 「周次位掩码 + 星期 + 节次位掩码」。这里让用户直接选**日期**，周次与星期
 * 由 [TermCalendar] 从开学日期换算，避免手选周次与星期时出现对不上的组合。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeClassroomScreen(
    vm: JwViewModel,
    onBack: () -> Unit,
    onNeedLogin: () -> Unit
) {
    val state by vm.state.collectAsState()

    LaunchedEffect(state.loggedIn) {
        if (state.loggedIn && state.roomsAlignedToToday.not() && !state.loadingRooms) {
            vm.loadFreeRooms()
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
            // 日期：点开日历直接选某一天，周次与星期由日期推算
            DatePickerBar(
                dateText = state.roomsDate,
                week = state.roomsWeek,
                weekday = state.roomsWeekday,
                termStartMillis = state.termStartMillis,
                onPick = { millis -> vm.loadFreeRooms(dateMillis = millis) }
            )
            // 节次
            FilterRow(
                label = "节次",
                options = 12,
                selected = state.roomsSection,
                render = { "第${it}节" },
                onSelect = { vm.loadFreeRooms(section = it) }
            )
            // 校区：默认「全校」。教务页面默认只选中一个校区，照抄会漏查其它校区
            CampusRow(
                campuses = state.roomsCampuses,
                selectedId = state.roomsCampusId,
                onSelect = { vm.loadFreeRooms(campusId = it) }
            )

            Text(
                if (state.roomsQuery.isBlank()) "按当前周次、星期和节次查询空闲教室"
                else state.roomsQuery,
                fontSize = 11.sp,
                color = Color(0xFF8E8E93),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
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
                else -> {
                    // 全校模式下按校区给出分布，让用户知道结果覆盖哪些校区
                    val byCampus = remember(state.rooms) {
                        state.rooms.groupingBy { it.campus.ifBlank { "未标注校区" } }.eachCount()
                    }
                    Text(
                        buildString {
                            append("共 ${state.rooms.size} 间空闲场地")
                            if (state.roomsCampusId.isBlank() && byCampus.size > 1) {
                                append(" · ")
                                append(byCampus.entries.joinToString(" / ") { "${it.key} ${it.value}" })
                            }
                            if (state.roomsTruncated) append("（结果可能被截断）")
                        },
                        fontSize = 12.sp,
                        color = Color(0xFF6C6C70),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(state.rooms, key = { it.code.ifBlank { it.name } }) { room ->
                            RoomCard(room, showCampus = state.roomsCampusId.isBlank())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CampusRow(
    campuses: List<CampusOption>,
    selectedId: String,
    onSelect: (String) -> Unit
) {
    // 只有真的读到多个校区时才显示这一行，避免占位
    if (campuses.none { it.id.isNotBlank() }) return

    Column(Modifier.fillMaxWidth()) {
        Text(
            "校区",
            fontSize = 13.sp,
            color = Color(0xFF3A3A3C),
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 「全校」永远排第一：不传 xqh_id 时服务端返回全校汇总
            item {
                FilterChip(
                    selected = selectedId.isBlank(),
                    onClick = { onSelect("") },
                    label = { Text("全校", fontSize = 12.sp) }
                )
            }
            items(campuses.filter { it.id.isNotBlank() }, key = { it.id }) { campus ->
                FilterChip(
                    selected = selectedId == campus.id,
                    onClick = { onSelect(campus.id) },
                    label = { Text(campus.name, fontSize = 12.sp) }
                )
            }
        }
    }
}

@Composable
private fun FilterRow(
    label: String,
    options: Int,
    selected: Int,
    render: (Int) -> String,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 11.sp,
            color = Color(0xFF8E8E93),
            modifier = Modifier.width(30.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(options) { index ->
                val value = index + 1
                FilterChip(
                    selected = selected == value,
                    onClick = { if (selected != value) onSelect(value) },
                    label = { Text(render(value), fontSize = 11.sp) },
                    modifier = Modifier.height(30.dp)
                )
            }
        }
    }
}

@Composable
private fun RoomCard(room: FreeRoom, showCampus: Boolean = false) {
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
                                color = Color(0xFF2C6FBB),
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                    // 全校模式下多个校区混排，必须标出校区才分得清
                    if (showCampus && room.campus.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFFF2F2F7)
                        ) {
                            Text(
                                room.campus,
                                fontSize = 10.sp,
                                color = Color(0xFF6C6C70),
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
