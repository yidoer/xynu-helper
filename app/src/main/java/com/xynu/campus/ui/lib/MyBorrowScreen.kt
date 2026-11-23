package com.xynu.campus.ui.lib

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xynu.campus.data.model.BorrowHistoryItem
import com.xynu.campus.data.model.BorrowItem
import com.xynu.campus.data.model.ReaderStats
import com.xynu.campus.ui.common.AppTopBar
import com.xynu.campus.ui.common.EmptyHint
import com.xynu.campus.ui.common.ErrorHint
import com.xynu.campus.ui.common.LoadingBox
import com.xynu.campus.ui.theme.XynuRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyBorrowScreen(
    vm: LibViewModel,
    onBack: () -> Unit,
    onNeedLogin: () -> Unit
) {
    val state by vm.state.collectAsState()
    var loadedOnce by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(0) }  // 0=当前借阅 1=借阅历史

    LaunchedEffect(Unit) {
        if (state.loggedIn && !loadedOnce) {
            vm.loadBorrowed()
            vm.loadStats()
            loadedOnce = true
        }
    }
    // 切到历史 Tab 时按需加载
    LaunchedEffect(tab, state.loggedIn) {
        if (tab == 1 && state.loggedIn && state.historyList.isEmpty() && !state.loadingHistory) {
            vm.loadHistory()
        }
    }

    Scaffold(
        containerColor = Color(0xFFF5F6FA),
        topBar = {
            AppTopBar(
                title = "我的借阅",
                onBack = onBack,
                action = {
                    IconButton(
                        onClick = {
                            if (state.loggedIn) {
                                if (tab == 0) vm.loadBorrowed() else vm.loadHistory()
                                vm.loadStats()
                            }
                        },
                        modifier = Modifier.size(38.dp)
                    ) {
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
                    Text("请先登录图书馆", color = Color.Gray)
                    Spacer(Modifier.height(8.dp))
                    Text("登录后可查看借阅记录并在线续借", fontSize = 12.sp, color = Color(0xFF8E8E93))
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onNeedLogin) { Text("去登录") }
                }
            }
            else -> Column(Modifier.fillMaxSize().padding(padding)) {
                // 读者信息卡 + 超期提醒
                state.stats?.let { stats ->
                    ReaderStatsCard(stats, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
                if ((state.stats?.overdueCount ?: 0) > 0) {
                    OverdueBanner(state.stats!!.overdueCount)
                }

                // Tab 切换：用 34dp 的矮标签代替 TabRow（TabRow 固定 48dp + 分隔线，
                // 下面还要放列表，两行选项条叠起来正文只剩半屏）。
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.White)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "当前借阅 (${state.borrowList.size})" to 0,
                        "借阅历史 (${state.historyList.size})" to 1,
                    ).forEach { (label, index) ->
                        val selected = tab == index
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (selected) XynuRed else Color(0xFFF2F3F5),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { tab = index }
                        ) {
                            Text(
                                label,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                color = if (selected) Color.White else Color(0xFF3A3A3C),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(vertical = 6.dp)
                            )
                        }
                    }
                }

                when (tab) {
                    0 -> CurrentBorrowTab(state, vm, Modifier.weight(1f))
                    else -> HistoryTab(state, vm, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ReaderStatsCard(stats: ReaderStats, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (stats.name.isNotBlank()) "👤 ${stats.name}" else "👤 我的借阅",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF1C1C1E)
                )
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                StatCell("在借", stats.borrowCount, Color(0xFF2A6BB0), Modifier.weight(1f))
                StatCell("7 天内到期", stats.soonDueCount, Color(0xFFB26A00), Modifier.weight(1f))
                StatCell("已超期", stats.overdueCount, XynuRed, Modifier.weight(1f))
                StatCell("历史借阅", stats.historyCount, Color(0xFF6B7280), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: Int, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 10.sp, color = Color(0xFF8E8E93))
    }
}

@Composable
private fun OverdueBanner(count: Int) {
    Surface(
        color = XynuRed.copy(alpha = 0.08f),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Warning, null, tint = XynuRed, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                "有 $count 本图书已超期，请尽快归还",
                fontSize = 12.sp,
                color = XynuRed,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun CurrentBorrowTab(state: LibUiState, vm: LibViewModel, modifier: Modifier = Modifier) {
    when {
        state.loadingBorrow && state.borrowList.isEmpty() ->
            LoadingBox(modifier, "正在加载借阅记录…")
        state.borrowError != null ->
            ErrorHint(state.borrowError!!, onRetry = { vm.loadBorrowed() }, modifier = modifier)
        state.borrowList.isEmpty() ->
            EmptyHint("当前没有借阅中的图书", modifier)
        else -> BorrowList(state, vm, modifier)
    }
}

@Composable
private fun HistoryTab(state: LibUiState, vm: LibViewModel, modifier: Modifier = Modifier) {
    when {
        state.loadingHistory && state.historyList.isEmpty() ->
            LoadingBox(modifier, "正在加载借阅历史…")
        state.historyError != null ->
            ErrorHint(state.historyError!!, onRetry = { vm.loadHistory() }, modifier = modifier)
        state.historyList.isEmpty() ->
            EmptyHint("暂无借阅历史\n（如学校系统未开放历史查询，此项为空）", modifier)
        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    "共 ${state.historyList.size} 条历史记录",
                    fontSize = 13.sp,
                    color = Color(0xFF8E8E93),
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            items(state.historyList, key = { it.title + it.borrowDate }) { item ->
                HistoryCard(item)
            }
        }
    }
}

@Composable
private fun HistoryCard(item: BorrowHistoryItem) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(item.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1C1C1E))
            if (item.author.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(item.author, fontSize = 12.sp, color = Color(0xFF8E8E93))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("借出日期", fontSize = 11.sp, color = Color(0xFF8E8E93))
                    Text(item.borrowDate.ifBlank { "—" }, fontSize = 13.sp, color = Color(0xFF1C1C1E))
                }
                Column(Modifier.weight(1f)) {
                    Text("归还日期", fontSize = 11.sp, color = Color(0xFF8E8E93))
                    Text(item.returnDate.ifBlank { "—" }, fontSize = 13.sp, color = Color(0xFF2E7D32))
                }
                if (item.location.isNotBlank()) {
                    Column(Modifier.weight(1f)) {
                        Text("馆藏地", fontSize = 11.sp, color = Color(0xFF8E8E93))
                        Text(item.location, fontSize = 13.sp, color = Color(0xFF1C1C1E))
                    }
                }
            }
        }
    }
}

@Composable
private fun BorrowList(state: LibUiState, vm: LibViewModel, modifier: Modifier = Modifier) {
    // 按应还日期排序：超期/即将到期的在前
    val sorted = remember(state.borrowList) {
        state.borrowList.sortedBy { parseDueMillis(it.dueDate) ?: Long.MAX_VALUE }
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                "按应还日期排序，共 ${sorted.size} 本在借",
                fontSize = 13.sp,
                color = Color(0xFF8E8E93),
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        items(sorted, key = { it.title + it.borrowDate }) { item ->
            BorrowCard(item, state, vm)
        }
    }
}

@Composable
private fun BorrowCard(item: BorrowItem, state: LibUiState, vm: LibViewModel) {
    val isRenewing = state.renewing == item.barcode
    val dueInfo = dueStatusInfo(item.dueDate)

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF1C1C1E),
                    modifier = Modifier.weight(1f)
                )
                // 到期状态徽章
                dueInfo?.let { (text, color) ->
                    Box(
                        modifier = Modifier
                            .background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(text, fontSize = 10.sp, color = color, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (item.author.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(item.author, fontSize = 12.sp, color = Color(0xFF8E8E93))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("借出日期", fontSize = 11.sp, color = Color(0xFF8E8E93))
                    Text(item.borrowDate.ifBlank { "—" }, fontSize = 13.sp, color = Color(0xFF1C1C1E))
                }
                Column(Modifier.weight(1f)) {
                    Text("应还日期", fontSize = 11.sp, color = Color(0xFF8E8E93))
                    Text(
                        item.dueDate.ifBlank { "—" },
                        fontSize = 13.sp,
                        color = dueColor(item.dueDate),
                        fontWeight = FontWeight.Medium
                    )
                }
                if (item.renewCount.isNotBlank()) {
                    Column(Modifier.weight(1f)) {
                        Text("续借次数", fontSize = 11.sp, color = Color(0xFF8E8E93))
                        Text(item.renewCount, fontSize = 13.sp, color = Color(0xFF1C1C1E))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.status.isNotBlank()) {
                    Text(item.status, fontSize = 12.sp, color = Color(0xFF2E7D32), modifier = Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if ((item.barcode.isNotBlank() || item.renewUrl != null) && item.status.contains("借")) {
                    OutlinedButton(
                        onClick = { vm.renew(item.barcode, "", item.renewUrl) },
                        enabled = !isRenewing,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        if (isRenewing) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        } else {
                            Text("续借", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

/** 到期状态：返回 null 表示正常，否则返回 (提示文本, 颜色) */
private fun dueStatusInfo(due: String): Pair<String, Color>? {
    val millis = parseDueMillis(due) ?: return null
    val now = System.currentTimeMillis()
    val diffDays = ((millis - now) / (24L * 3600 * 1000)).toInt()
    return when {
        diffDays < 0 -> "已超期 ${-diffDays} 天" to Color(0xFFB3261E)
        diffDays == 0 -> "今日到期" to Color(0xFFB3261E)
        diffDays <= 3 -> "$diffDays 天后到期" to Color(0xFFB26A00)
        diffDays <= 7 -> "$diffDays 天后到期" to Color(0xFF2A6BB0)
        else -> null
    }
}

private fun parseDueMillis(due: String): Long? {
    if (due.isBlank()) return null
    val parts = due.trim().split("-", "/", ".")
    if (parts.size < 3) return null
    return try {
        val c = java.util.Calendar.getInstance().apply {
            set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt(), 23, 59, 59)
        }
        c.timeInMillis
    } catch (_: Exception) { null }
}

private fun dueColor(due: String): Color {
    val millis = parseDueMillis(due) ?: return Color(0xFF1C1C1E)
    val now = System.currentTimeMillis()
    val diffDays = ((millis - now) / (24L * 3600 * 1000)).toInt()
    return when {
        diffDays < 0 -> Color(0xFFB3261E)
        diffDays <= 3 -> Color(0xFFB26A00)
        else -> Color(0xFF1C1C1E)
    }
}
