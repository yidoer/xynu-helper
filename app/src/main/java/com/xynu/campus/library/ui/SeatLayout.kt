package com.xynu.campus.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xynu.campus.library.model.Seat
import com.xynu.campus.library.model.SeatVisual
import com.xynu.campus.library.seat.SeatNotifier
import com.xynu.campus.library.seat.SeatReminderScheduler
import com.xynu.campus.library.vm.SeatViewModel
import com.xynu.campus.ui.common.AppTopBar
import kotlinx.coroutines.delay

// 选座：座位图、图例与单个座位格子（从 SeatScreen.kt 拆出，代码未改）

@Composable
internal fun SeatLayoutView(
    state: SeatViewModel.UiState,
    onSelectSeat: (String) -> Unit,
    onReserve: () -> Unit,
    onBack: () -> Unit,
) {
    val layout = state.layout
    Column {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← 返回列表") }
            Text(
                layout?.libName?.takeIf { it.isNotBlank() } ?: "座位图",
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
        }

        state.rule?.let {
            Card {
                Text(
                    it.hint(),
                    Modifier.padding(10.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        if (layout == null) {
            Text("座位图没有取到。", fontSize = 13.sp)
            return@Column
        }

        Text(
            if (layout.hasServerCounts) {
                "空闲 ${layout.seatsFree} / 共 ${layout.seatsTotal}"
            } else {
                "空闲 ${layout.freeCount}"
            },
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(8.dp))
        SeatLegend()
        Spacer(Modifier.height(10.dp))

        // 座位网格：横竖都可滚，套一层描边卡片，让它看起来像"一张图"而不是散落的点。
        // 纵向也必须能滚 —— 大馆 max_y 能到 40+ 行，只给横向滚动会直接顶出屏幕。
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(10.dp),
                )
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    RoundedCornerShape(10.dp),
                ),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(8.dp),
            ) {
                Column {
                    layout.grid().forEach { row ->
                        Row {
                            row.forEach { seat ->
                                SeatCell(
                                    seat = seat,
                                    selected = seat?.key == state.selectedSeatKey,
                                    onClick = { seat?.let { onSelectSeat(it.key) } },
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        val selected = layout.seats.firstOrNull { it.key == state.selectedSeatKey }
        // 底部操作条：常驻高度，避免选中/未选中时整块跳一下
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                selected?.let { "已选：${it.name.ifBlank { it.key }}" } ?: "点一个空闲座位",
                Modifier.weight(1f),
                fontSize = 13.sp,
                maxLines = 1,
            )
            Button(
                onClick = onReserve,
                enabled = selected != null && selected.isFree && !state.loading,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text("预约这个座位", fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun SeatLegend() {
    // 措辞逐字取自上游 menuList（ti_prereserve.js）：
    //   有人 / 暂离 / 监督 / 可选 / 已选 / 入口
    // 图例文字直接采用上游 `menuList` 的原词（有人/暂离/监督/可选/已选），
    // 不再自己造词。此前把绿标成「可预约」、灰标成「不可用」、橙标成「暂离」，
    // 三个词全部与颜色错位 —— 用户照图例去找绿色"可预约"座位，点上去毫无反应，
    // 会以为是 App 坏了。实际绿 = 上游「已选」，`status==true` 所以本来就点不动。
    //
    // 「入口」不列：它是 type>=2 的非座位图标，不在可选范围内。
    // 固定两行（3+3），不用 FlowRow —— 省掉 ExperimentalLayoutApi，
    // 也保证不同字号下不会突然变三行把座位图挤走。
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LegendDot(SeatVisual.FREE, "可选")
            LegendDot(SeatVisual.OCCUPIED, "有人")
            LegendDot(null, "我选的", selectedSwatch = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LegendDot(SeatVisual.AWAY, "暂离")
            LegendDot(SeatVisual.SUPERVISED, "监督")
            LegendDot(SeatVisual.SELECTED, "已选")
        }
    }
}

@Composable
private fun LegendDot(
    visual: SeatVisual?,
    label: String,
    selectedSwatch: Boolean = false,
) {
    val color = when {
        selectedSwatch -> SELECTED_BLUE
        visual != null -> visual.color()
        else -> Color.White
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(11.dp)
                .background(color, RoundedCornerShape(3.dp))
                .border(1.dp, SeatVisual.FREE.strokeColor(), RoundedCornerShape(3.dp)),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val CELL_SIZE_DP = 28
private val CELL_SIZE = CELL_SIZE_DP.dp

/**
 * 已选座位色。**必须用上游的蓝色，不能用主题色。**
 *
 * 上游 `ti_prereserve.js`：`selectedStyle:{background:"#3D9BFE",border:"none"}`。
 * 我们主题色是品牌红（顶栏那个点），用它画已选会和「已占」的 `#F36F6F`
 * 撞成一团红 —— 用户根本分不清"我选的"和"别人占的"。
 */
private val SELECTED_BLUE = Color(0xFF3D9BFE)

private fun SeatVisual.color(): Color = when (this) {
    SeatVisual.FREE -> Color(0xFFFFFFFF)
    SeatVisual.SELECTED -> Color(0xFF53B175)
    SeatVisual.AWAY -> Color(0xFFDADADA)
    SeatVisual.SUPERVISED -> Color(0xFFFE9B4B)
    SeatVisual.OCCUPIED -> Color(0xFFF36F6F)
}

/** 状态 → 描边色。只有白座位用浅灰边框（上游 `8rpx solid #F2F2F2`）。 */
@Composable
private fun SeatVisual.strokeColor(): Color = when (this) {
    SeatVisual.FREE -> Color(0xFFD9D9D9)
    else -> Color.Transparent
}

/**
 * 单个座位格。
 *
 * 尺寸固定 28dp：一个馆最多 ~40 列，加横向滚动足够看清，
 * 不做缩放（缩放会让点击热区小于 48dp 触控推荐值）。
 *
 * 非座位元素（桌子/柱子/窗户…）**不再留空白**：上游给每种都配了图标，
 * 留空会让座位图看起来像一堆散落的点，认不出哪里是桌子哪里是门。
 */
@Composable
private fun SeatCell(
    seat: Seat?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    if (seat == null) {
        Spacer(Modifier.size(CELL_SIZE))
        return
    }

    // 过道（type 缺失/0）：留空
    if (!seat.isSeatBlock) {
        val glyph = seat.furnitureGlyph
        if (glyph == null) {
            Spacer(Modifier.size(CELL_SIZE))
        } else {
            Box(
                Modifier.size(CELL_SIZE),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    glyph,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                )
            }
        }
        return
    }

    val visual = seat.visual
    val bg = if (selected) SELECTED_BLUE else visual.color()

    Box(
        modifier = Modifier
            .padding(1.dp)
            .size(CELL_SIZE - 2.dp)
            .background(bg, RoundedCornerShape(4.dp))
            .border(
                width = 1.dp,
                color = if (selected) SELECTED_BLUE else visual.strokeColor(),
                shape = RoundedCornerShape(4.dp),
            )
            .then(
                if (seat.isFree) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            seat.name.takeLast(2),
            fontSize = 8.sp,
            maxLines = 1,
            color = if (selected || visual.darkBackground) Color.White else Color(0xFF666666),
        )
    }
}
