package com.xynu.campus.library.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.platform.LocalContext
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

// 馆藏检索：书目详情页（从 CatalogScreen.kt 拆出，代码未改）

// ============================================================
// 详情页
// ============================================================

/**
 * 书目详情 + 馆藏复本。
 *
 * 用户真正关心的是「这本书现在能不能借、在哪层哪个书库」——所以馆藏表放在
 * 第一条，书目信息（简介、主题、分类）排在后面，不做成必须点的折叠面板。
 */
@Composable
fun CatalogDetailScreen(
    vm: CatalogViewModel,
    onBack: () -> Unit,
    /** 当前这本是否已收藏。 */
    isFavorite: Boolean = false,
    onToggleFavorite: (CatalogDetailParser.Detail, bookId: String?) -> Unit = { _, _ -> },
) {
    val state by vm.state.collectAsState()
    val detail = state.detail

    Scaffold(
        containerColor = Color(0xFFF5F6FA),
        topBar = {
            AppTopBar(
                title = detail?.title ?: state.detailTitleHint ?: "书目详情",
                onBack = onBack,
                action = {
                    if (detail != null) {
                        IconButton(
                            onClick = { onToggleFavorite(detail, state.detailBookId) },
                            modifier = Modifier.size(38.dp),
                        ) {
                            Icon(
                                if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = if (isFavorite) "取消收藏" else "收藏",
                                tint = if (isFavorite) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color(0xFF3A3A3C)
                                },
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(Modifier.height(2.dp))

            when {
                state.detailLoading -> LoadingHint()

                state.detailError != null -> Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ErrorHint(
                        message = state.detailError!!,
                        onRetry = { vm.retryDetail() },
                    )
                    // 有原始详情地址时给一条真实可走的出口。
                    // 否则 `bookIdOf` 一旦失配（上游换了 id 形态/路径），
                    // 用户手里明明有这个地址却只能看到一句"打不开详情"。
                    val fallback = state.detailFallbackUrl
                    if (!fallback.isNullOrBlank()) {
                        val ctx = LocalContext.current
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    ctx.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(fallback))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("在浏览器打开这条书目")
                        }
                    }
                }

                detail == null -> Text(
                    "没有可显示的书目信息",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> DetailContent(detail)
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun DetailContent(d: CatalogDetailParser.Detail) {
    // ① 馆藏复本：最影响决策，放最前
    HoldingsCard(d)

    // ② 书目信息
    if (!d.isEmpty) {
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("书目信息", fontSize = 14.sp, fontWeight = FontWeight.Bold)

                InfoRow("题名", d.title)
                InfoRow("著者", d.author)
                InfoRow("ISBN", d.isbn)
                InfoRow("价格", d.price)
                InfoRow("出版社", d.publisher)
                InfoRow("出版日期", d.publishDate)
                InfoRow("载体形态", d.physicalForm)
                InfoRow("丛编项", d.series)
                InfoRow("主题词", d.subject)
                InfoRow("中图分类", d.clcClass)

                d.summary?.let { s ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    Text("内容简介", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(
                        s,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun HoldingsCard(d: CatalogDetailParser.Detail) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (d.availableCount > 0) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            } else {
                Color.White
            },
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("馆藏情况", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(
                    when {
                        d.holdings.isEmpty() -> "暂无复本记录"
                        d.availableCount > 0 -> "${d.availableCount} 本在架"
                        else -> "全部借出"
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (d.availableCount > 0) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                )
            }

            if (d.holdings.isEmpty()) {
                Text(
                    "服务器没有返回复本记录，可能是刚下单还没上架。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                d.holdings.forEachIndexed { i, h ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                h.library ?: "未知书库",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            val sub = listOfNotNull(h.callNo, h.barcode?.let { "条码 $it" })
                                .joinToString(" · ")
                            if (sub.isNotEmpty()) {
                                Text(
                                    sub,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            h.status ?: "—",
                            fontSize = 12.sp,
                            color = if (h.isAvailable) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(60.dp),
        )
        Text(value, fontSize = 12.sp, modifier = Modifier.weight(1f))
    }
}
