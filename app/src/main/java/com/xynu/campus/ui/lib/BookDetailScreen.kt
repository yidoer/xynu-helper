package com.xynu.campus.ui.lib

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xynu.campus.data.model.BookDetail
import com.xynu.campus.ui.common.AppTopBar
import com.xynu.campus.ui.common.ErrorHint
import com.xynu.campus.ui.common.LoadingBox
import com.xynu.campus.ui.theme.XynuRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    bookId: String,
    vm: LibViewModel,
    onBack: () -> Unit
) {
    val state by vm.state.collectAsState()

    LaunchedEffect(bookId) {
        vm.loadBookDetail(bookId)
    }

    val isFav = state.favorites.any { it.id == bookId }

    Scaffold(
        containerColor = Color(0xFFF5F6FA),
        topBar = {
            AppTopBar(
                title = "图书详情",
                onBack = onBack,
                action = {
                    state.bookDetail?.let { detail ->
                        IconButton(onClick = { vm.toggleFavorite(detail) }) {
                            Icon(
                                if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = if (isFav) "取消收藏" else "收藏",
                                tint = if (isFav) XynuRed else Color(0xFF3A3A3C)
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        when {
            state.loadingDetail -> LoadingBox(Modifier.padding(padding), "正在加载详情…")
            state.detailError != null -> ErrorHint(
                state.detailError!!,
                onRetry = { vm.loadBookDetail(bookId) },
                modifier = Modifier.padding(padding)
            )
            state.bookDetail == null -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { Text("未找到图书详情", color = Color(0xFF8E8E93)) }
            else -> DetailContent(state.bookDetail!!, Modifier.padding(padding))
        }
    }
}

@Composable
private fun DetailContent(detail: BookDetail, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 标题卡
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(detail.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1C1C1E))
                if (detail.author.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(detail.author, fontSize = 13.sp, color = Color(0xFF6B7280))
                }
            }
        }

        // 书目信息
        InfoCard("书目信息") {
            if (detail.callNumber.isNotBlank()) InfoRow("索书号", detail.callNumber)
            if (detail.isbn.isNotBlank()) InfoRow("ISBN", detail.isbn)
            if (detail.publisher.isNotBlank()) InfoRow("出版发行", detail.publisher)
            if (detail.publishDate.isNotBlank()) InfoRow("出版时间", detail.publishDate)
            if (detail.pages.isNotBlank()) InfoRow("载体形态", detail.pages)
            if (detail.price.isNotBlank()) InfoRow("价格", detail.price)
            if (detail.category.isNotBlank()) InfoRow("中图分类", detail.category)
            if (detail.subject.isNotBlank()) InfoRow("主题词", detail.subject)
            if (detail.language.isNotBlank()) InfoRow("语种", detail.language)
        }

        // 内容简介
        if (detail.summary.isNotBlank()) {
            InfoCard("内容简介") {
                Text(detail.summary, fontSize = 13.sp, color = Color(0xFF3A3A3C), lineHeight = 20.sp)
            }
        }

        // 目录
        if (detail.toc.isNotBlank()) {
            InfoCard("目录") {
                Text(detail.toc, fontSize = 12.sp, color = Color(0xFF3A3A3C), lineHeight = 18.sp)
            }
        }

        // 馆藏分布
        if (detail.holdings.isNotEmpty()) {
            InfoCard("馆藏分布 (${detail.holdings.size})") {
                detail.holdings.forEachIndexed { idx, h ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                if (h.callNumber.isNotBlank()) {
                                    Text(h.callNumber, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1C1C1E))
                                }
                                if (h.location.isNotBlank()) {
                                    Text(h.location, fontSize = 12.sp, color = Color(0xFF6B7280))
                                }
                                if (h.barcode.isNotBlank()) {
                                    Text("条码：${h.barcode}", fontSize = 11.sp, color = Color(0xFF8E8E93))
                                }
                            }
                            val available = h.status.contains("在馆") || h.status.contains("可借")
                            Box(
                                modifier = Modifier
                                    .background(
                                        (if (available) Color(0xFF2E7D32) else Color(0xFFB3261E)).copy(alpha = 0.12f),
                                        RoundedCornerShape(6.dp)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    h.status.ifBlank { "—" },
                                    fontSize = 11.sp,
                                    color = if (available) Color(0xFF2E7D32) else Color(0xFFB3261E),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        if (idx < detail.holdings.size - 1) {
                            Spacer(Modifier.height(6.dp))
                            HorizontalDivider(color = Color(0xFFF0F0F0))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1C1C1E))
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, fontSize = 12.sp, color = Color(0xFF8E8E93), modifier = Modifier.width(72.dp))
        Text(value, fontSize = 13.sp, color = Color(0xFF1C1C1E), modifier = Modifier.weight(1f))
    }
}
