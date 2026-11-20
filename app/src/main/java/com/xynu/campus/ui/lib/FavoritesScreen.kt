package com.xynu.campus.ui.lib

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xynu.campus.ui.common.AppTopBar
import com.xynu.campus.ui.common.EmptyHint
import com.xynu.campus.ui.theme.XynuRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    vm: LibViewModel,
    onBack: () -> Unit,
    onOpenBook: (String) -> Unit
) {
    val state by vm.state.collectAsState()

    Scaffold(
        containerColor = Color(0xFFF5F6FA),
        topBar = {
            AppTopBar(title = "收藏书架", onBack = onBack)
        }
    ) { padding ->
        if (state.favorites.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Favorite,
                        null,
                        tint = Color(0xFFC7C7CC),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("暂无收藏图书", color = Color(0xFF8E8E93), fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("在检索结果或详情页点 ♡ 即可收藏", color = Color(0xFFC7C7CC), fontSize = 12.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text(
                        "共 ${state.favorites.size} 本收藏",
                        fontSize = 13.sp,
                        color = Color(0xFF8E8E93),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                items(state.favorites, key = { it.id }) { book ->
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        modifier = Modifier.fillMaxWidth().clickable { onOpenBook(book.id) }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    book.title,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF1C1C1E),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (book.author.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(book.author, fontSize = 12.sp, color = Color(0xFF6B7280), maxLines = 1)
                                }
                                if (book.callNumber.isNotBlank()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text("索书号：${book.callNumber}", fontSize = 11.sp, color = Color(0xFF8E8E93))
                                }
                            }
                            IconButton(onClick = {
                                vm.toggleFavorite(com.xynu.campus.data.model.BookItem(
                                    id = book.id,
                                    title = book.title,
                                    author = book.author,
                                    callNumber = book.callNumber,
                                    isbn = "",
                                    publisher = "",
                                    detailUrl = "",
                                    holdingCount = "",
                                    canBorrowCount = ""
                                ))
                            }) {
                                Icon(Icons.Filled.Delete, "取消收藏", tint = Color(0xFFC7C7CC))
                            }
                        }
                    }
                }
            }
        }
    }
}
