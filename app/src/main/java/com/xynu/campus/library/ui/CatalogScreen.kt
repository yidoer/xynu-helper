package com.xynu.campus.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xynu.campus.library.net.CatalogRepository
import com.xynu.campus.library.parser.CatalogDetailParser
import com.xynu.campus.library.parser.CatalogSearchParser
import com.xynu.campus.library.vm.CatalogViewModel
import com.xynu.campus.ui.common.AppTopBar

/**
 * 应用内馆藏检索（免登录，校内校外网络都能用）。
 *
 * 页面骨架从上到下只有三层：
 * 1. 顶栏（与主页同款：白底、48dp、红点品牌标）
 * 2. 搜索框 + 检索按钮
 * 3. 检索字段的紧凑标签行
 *
 * 之所以把「检索字段」做成一行可横滑的矮标签，而不是一整排 FilterChip：
 * FilterChip 自带 32dp 高度加 8dp 外边距，四个就会吃掉近百像素，
 * 把正文（结果列表）压到屏幕下半部分。这里收到 28dp、字号 12sp，
 * 让结果列表尽可能往上长——用户来这页是为了看结果，不是为了看选项。
 */
@Composable
fun CatalogScreen(
    vm: CatalogViewModel,
    onBack: () -> Unit,
    onOpenDetail: (detailUrl: String?, titleHint: String) -> Unit,
    /** 已收藏的书目 id 集合（来自全局收藏，用于渲染实心/空心爱心）。 */
    favoriteIds: Set<String> = emptySet(),
    onToggleFavorite: (CatalogSearchParser.Book) -> Unit = {},
) {
    val state by vm.state.collectAsState()

    Scaffold(
        containerColor = Color(0xFFF5F6FA),
        topBar = { AppTopBar(title = "馆藏检索", onBack = onBack) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            SearchBar(
                keyword = state.keyword,
                onKeywordChange = vm::onKeywordChange,
                onSearch = vm::search,
                enabled = !state.loading,
            )

            SearchTypeRow(
                selected = state.searchtype,
                onSelect = vm::onSearchtypeChange,
            )

            when {
                state.loading -> LoadingHint()
                // 失败但有旧结果时，别把用户手里那 10 条整屏换成错误页。
                //
                // 原来 `error != null` 优先于 `books.isEmpty()`，只要出错就整屏 ErrorHint，
                // 页码头部和翻页按钮全没了 —— CatalogViewModel 里"失败保留旧页"
                // 的设计意图在界面上拿不到任何收益，用户只能盲点重试。
                // 现在：有旧结果就保留列表，错误降到顶部窄条 + 重试按钮。
                state.error != null && state.books.isEmpty() -> ErrorHint(
                    message = state.error!!,
                    onRetry = { vm.retry() },
                    modifier = Modifier.weight(1f),
                )
                state.books.isEmpty() -> EmptyHint(
                    submitted = state.submittedKeyword,
                    modifier = Modifier.weight(1f),
                )
                else -> Column(Modifier.weight(1f)) {
                    if (state.error != null) {
                        InlineErrorBar(
                            message = state.error!!,
                            onRetry = { vm.retry() },
                        )
                    }
                    BookList(
                        state = state,
                        favoriteIds = favoriteIds,
                        onOpenDetail = { book -> onOpenDetail(book.detailUrl, book.title) },
                        onToggleFavorite = onToggleFavorite,
                        onPrev = vm::prevPage,
                        onNext = vm::nextPage,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchBar(
    keyword: String,
    onKeywordChange: (String) -> Unit,
    onSearch: () -> Unit,
    enabled: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = keyword,
            onValueChange = onKeywordChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("输入书名 / 作者 / 索书号", fontSize = 14.sp) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            textStyle = MaterialTheme.typography.bodyMedium,
            leadingIcon = {
                Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            },
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = onSearch,
            enabled = enabled && keyword.isNotBlank(),
            modifier = Modifier.height(50.dp),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 18.dp),
        ) { Text("检索", fontSize = 14.sp) }
    }
}

/**
 * 检索字段行：紧凑标签，可横滑。
 *
 * 四个字段在窄屏上刚好能并排放开；更窄的机器上会自动横向滚动，
 * 而不是换行把正文再顶下去一行。
 */
@Composable
private fun SearchTypeRow(selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CatalogViewModel.SEARCH_TYPES.forEach { (label, value) ->
            SearchTypeTag(
                label = label,
                selected = selected == value,
                onClick = { onSelect(value) },
            )
        }
    }
}

@Composable
private fun SearchTypeTag(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary else Color.White,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) Color.White else Color(0xFF3A3A3C),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun BookList(
    state: CatalogViewModel.UiState,
    favoriteIds: Set<String>,
    onOpenDetail: (CatalogSearchParser.Book) -> Unit,
    onToggleFavorite: (CatalogSearchParser.Book) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        // 结果统计 + 翻页挤在同一行，只占 ~28dp。
        // 用户滚到列表底部时也能看到「共几条、第几页」，不必回头看顶部。
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                buildString {
                    append("共 ")
                    append(state.total?.toString() ?: "?")
                    append(" 条")
                    if (state.pageIndex > 1) append(" · 第 ${state.pageIndex} 页")
                },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (state.hasPrev) {
                CompactPageButton("上一页", enabled = !state.loading, onClick = onPrev)
            }
            if (state.hasNext) {
                CompactPageButton("下一页", enabled = !state.loading, onClick = onNext)
            }
        }

        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.books) { book ->
                BookRow(
                    book = book,
                    isFavorite = favoriteIdOf(book) in favoriteIds,
                    onClick = { onOpenDetail(book) },
                    onToggleFavorite = { onToggleFavorite(book) },
                )
            }
        }
    }
}

/** 翻页按钮做成矮文字按钮，避免 Material 默认 48dp 的最小触控高度把行撑高。 */
@Composable
private fun CompactPageButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        modifier = Modifier.height(28.dp),
    ) {
        Text(label, fontSize = 11.sp)
    }
}

/** 收藏用的稳定 id：优先用书目 id（与详情页一致），拿不到才退回书名。 */
private fun favoriteIdOf(book: CatalogSearchParser.Book): String =
    CatalogRepository.bookIdOf(book.detailUrl) ?: book.title

@Composable
private fun BookRow(
    book: CatalogSearchParser.Book,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    book.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                book.author?.let {
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                book.callNo?.let {
                    Text("索书号：$it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                book.publisher?.let {
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    book.mediaType?.let {
                        Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.weight(1f))
                    Text("看馆藏", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
            IconButton(onClick = onToggleFavorite, modifier = Modifier.size(40.dp)) {
                Icon(
                    if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (isFavorite) "取消收藏" else "收藏",
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary else Color(0xFFB0B0B4),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
internal fun LoadingHint() {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            Spacer(Modifier.height(8.dp))
            Text("正在检索…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ErrorHint(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("检索没成功", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text(
                message,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
private fun InlineErrorBar(message: String, onRetry: () -> Unit) {
    // 列表还在时的错误呈现：不抢占内容区，只说"这一页没翻成功"。
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) { Text("重试", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun EmptyHint(submitted: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (submitted.isBlank()) {
                Text("输入关键词开始检索", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text(
                    "这个页面不用登录，校内外网络都能用。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("没有找到「$submitted」", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "换个检索字段试试：书名用「题名」，作者用「作者」，分类号用「索书号」。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
