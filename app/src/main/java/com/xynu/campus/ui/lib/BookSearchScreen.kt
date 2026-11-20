package com.xynu.campus.ui.lib

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xynu.campus.data.model.BookItem
import com.xynu.campus.ui.common.AppTopBar
import com.xynu.campus.ui.common.ErrorHint
import com.xynu.campus.ui.common.LoadingBox
import com.xynu.campus.ui.theme.XynuGold
import com.xynu.campus.ui.theme.XynuRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookSearchScreen(
    vm: LibViewModel,
    onBack: () -> Unit,
    onOpenDetail: (String) -> Unit = {},
    onOpenFavorites: () -> Unit = {}
) {
    val state by vm.state.collectAsState()
    var query by remember { mutableStateOf("") }
    var field by remember { mutableStateOf("title") }

    Scaffold(
        containerColor = Color(0xFFF5F6FA),
        topBar = {
            AppTopBar(
                title = "馆藏检索",
                onBack = onBack,
                action = {
                    IconButton(onClick = onOpenFavorites, modifier = Modifier.size(38.dp)) {
                        Icon(
                            Icons.Filled.Favorite,
                            contentDescription = "收藏书架",
                            tint = XynuRed,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 搜索框
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入书名 / 作者 / 关键词") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    leadingIcon = { Icon(Icons.Filled.Search, null) }
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { vm.search(query) },
                    enabled = query.isNotBlank() && !state.searching,
                    modifier = Modifier.height(56.dp)
                ) {
                    Text("搜索")
                }
            }

            // 字段切换
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("题名" to "title", "作者" to "creatorSearch", "主题" to "subject", "ISBN" to "isbnSearch").forEach { (label, value) ->
                    FilterChip(
                        selected = field == value,
                        onClick = { field = value },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            when {
                state.searching -> LoadingBox(Modifier.weight(1f), "正在检索…")
                state.searchError != null -> ErrorHint(state.searchError!!, onRetry = { vm.search(query) }, modifier = Modifier.weight(1f))
                state.books.isEmpty() -> {
                    if (state.searchQuery.isNotBlank()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text("未找到相关图书", color = Color(0xFF8E8E93))
                        }
                    } else {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text("输入关键词开始检索馆藏", color = Color(0xFF8E8E93), fontSize = 14.sp)
                        }
                    }
                }
                else -> BookList(state, vm, onOpenDetail, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun BookList(
    state: LibUiState,
    vm: LibViewModel,
    onOpenDetail: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(state.books, key = { it.id + it.title }) { book ->
            BookCard(book, state, vm, onOpenDetail)
        }
    }
}

@Composable
private fun BookCard(
    book: BookItem,
    state: LibUiState,
    vm: LibViewModel,
    onOpenDetail: (String) -> Unit
) {
    val holdings = state.holdings[book.id]
    val loadingHoldings = state.holdingBookId == book.id
    var expanded by remember { mutableStateOf(false) }
    val isFav = state.favorites.any { it.id == book.id }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    book.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF1C1C1E),
                    modifier = Modifier.weight(1f).clickable { onOpenDetail(book.id) },
                    maxLines = 2
                )
                // 收藏按钮
                IconButton(onClick = { vm.toggleFavorite(book) }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = if (isFav) "取消收藏" else "收藏",
                        tint = if (isFav) XynuRed else Color(0xFFC7C7CC),
                        modifier = Modifier.size(20.dp)
                    )
                }
                if (book.holdingCount.isNotBlank()) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        book.holdingCount,
                        fontSize = 11.sp,
                        color = XynuGold,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            if (book.author.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(book.author, fontSize = 12.sp, color = Color(0xFF6B7280), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (book.callNumber.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text("索书号：${book.callNumber}", fontSize = 12.sp, color = Color(0xFF8E8E93))
            }
            if (book.publisher.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(book.publisher, fontSize = 12.sp, color = Color(0xFF8E8E93), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            if (holdings != null && expanded) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = Color(0xFFF0F0F0))
                Spacer(Modifier.height(6.dp))
                holdings.forEach { h ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            h.callNumber,
                            fontSize = 11.sp,
                            color = Color(0xFF4B5563),
                            modifier = Modifier.weight(1.2f),
                            maxLines = 1
                        )
                        Text(
                            h.location,
                            fontSize = 11.sp,
                            color = Color(0xFF6B7280),
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val available = h.status.contains("在馆") || h.status.contains("可借")
                        Text(
                            h.status.ifBlank { "—" },
                            fontSize = 11.sp,
                            color = if (available) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(0.8f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { onOpenDetail(book.id) }) {
                    Text("详情", fontSize = 12.sp)
                }
                TextButton(onClick = {
                    expanded = !expanded
                    if (expanded && holdings == null && !loadingHoldings) {
                        vm.loadHoldings(book.id)
                    }
                }) {
                    Text(
                        if (expanded) "收起馆藏" else "查看馆藏",
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
