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

// 空教室：按楼栋分组的教室列表与教室卡片（从 FreeClassroomScreen.kt 拆出，代码未改）

/**
 * 结果列表：按**教学楼**分组。
 *
 * 用户找教室的实际路径是「我去综合教学楼 → 哪间空着」，而不是在 500 多条里
 * 逐条扫。分组后每组给出数量，配合吸顶标题，长列表也能快速定位。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun RoomList(rooms: List<FreeRoom>, modifier: Modifier = Modifier) {
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
internal fun FreeRoom.buildingOrOther(): String = building.ifBlank { "其他场地" }

private val DIGIT_RUN = Regex("\\d+")

/**
 * 楼栋名排序键：把名字里的数字补零到固定宽度，使「综合教学楼2」排在
 * 「综合教学楼10」之前（直接按字符串排会得到 10 < 2 的错误顺序）。
 */
internal fun naturalKey(name: String): String =
    DIGIT_RUN.replace(name) { m -> m.value.padStart(4, '0') }

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
