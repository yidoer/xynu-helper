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

// 空教室：顶部筛选条与筛选面板（从 FreeClassroomScreen.kt 拆出，代码未改）

/**
 * 条件摘要条：把当前生效的三个条件压成一行，点击展开筛选抽屉。
 *
 * 之所以不直接把 chip 平铺：12 个节次 + 4 个校区必定要横向滑动，滑动条本身
 * 又会被右边内容挤到贴边（此前「周次条被裁掉」就是这个原因）。摘要条只有一个
 * 高度 44dp 的可点区域，既符合「筛选入口常驻可见」，也不抢内容的纵向空间。
 */
@Composable
internal fun FilterSummaryBar(
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
internal fun FilterSheetContent(
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
