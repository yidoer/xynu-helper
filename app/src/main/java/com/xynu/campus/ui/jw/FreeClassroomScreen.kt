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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xynu.campus.ui.common.AppTopBar
import com.xynu.campus.ui.common.EmptyHint
import com.xynu.campus.ui.common.ErrorHint
import com.xynu.campus.ui.common.LoadingBox

/**
 * 空教室查询（使用当前教务会话直连正方 V9 空教室接口）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeClassroomScreen(
    vm: JwViewModel,
    onBack: () -> Unit,
    onNeedLogin: () -> Unit
) {
    val state by vm.state.collectAsState()
    var selectedSection by remember { mutableIntStateOf(1) }

    LaunchedEffect(state.loggedIn) {
        if (state.loggedIn) vm.loadFreeRooms(selectedSection)
    }

    Scaffold(
        containerColor = Color(0xFFF5F6FA),
        topBar = {
            AppTopBar(
                title = "空教室查询",
                onBack = onBack,
                action = {
                    IconButton(onClick = { if (state.loggedIn) vm.loadFreeRooms(selectedSection) }, modifier = Modifier.size(38.dp)) {
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
            else -> Column(Modifier.fillMaxSize().padding(padding)) {
                // 节次选择
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(12) { section ->
                        FilterChip(
                            selected = selectedSection == section + 1,
                            onClick = {
                                selectedSection = section + 1
                                vm.loadFreeRooms(section + 1)
                            },
                            label = { Text("第${section + 1}节", fontSize = 12.sp) }
                        )
                    }
                }
                Text(
                    if (state.roomsQuery.isBlank()) "按当前日期和教务当前学期查询空闲教室"
                    else "查询条件：${state.roomsQuery}",
                    fontSize = 12.sp,
                    color = Color(0xFF8E8E93),
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                when {
                    state.loadingRooms -> LoadingBox(Modifier.weight(1f), "正在查询空教室…")
                    state.roomsError != null -> ErrorHint(
                        state.roomsError!!,
                        onRetry = { vm.loadFreeRooms(selectedSection) },
                        modifier = Modifier.weight(1f),
                        onSecondary = onNeedLogin
                    )
                    state.rooms.isEmpty() -> EmptyHint("该节次暂无空闲教室数据", Modifier.weight(1f))
                    else -> LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(state.rooms) { room ->
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(room, fontSize = 14.sp, color = Color(0xFF1C1C1E), modifier = Modifier.weight(1f))
                                    Icon(
                                        Icons.Filled.CheckCircle,
                                        contentDescription = "空闲",
                                        tint = Color(0xFF2E7D32),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}