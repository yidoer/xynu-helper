package com.xynu.campus.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xynu.campus.data.model.NewsItem
import com.xynu.campus.ui.common.EmptyHint
import com.xynu.campus.ui.common.FeatureTile
import com.xynu.campus.ui.common.SectionCard
import com.xynu.campus.ui.theme.XynuBlue
import com.xynu.campus.ui.theme.XynuRed
import com.xynu.campus.ui.theme.XynuGold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSchedule: () -> Unit,
    onOpenScore: () -> Unit,
    onOpenBookSearch: () -> Unit,
    onOpenMyBorrow: () -> Unit,
    onOpenFreeClassroom: () -> Unit = {},
    onOpenFavorites: () -> Unit = {},
    onOpenSettings: () -> Unit,
    onOpenJwLogin: () -> Unit,
    onOpenLibLogin: () -> Unit,
    onOpenWeb: (String, String?) -> Unit = { _, _ -> },
    vm: HomeViewModel = viewModel()
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    // 校园卡入口：弹窗选择（完美校园无网页版，数据在支付宝小程序/APP中）
    var showCardDialog by remember { mutableStateOf(false) }
    val openCampusCard: () -> Unit = { showCardDialog = true }

    if (showCardDialog) {
        CardEntryDialog(
            onDismiss = { showCardDialog = false },
            onOpenWeb = onOpenWeb,
            context = context
        )
    }

    Scaffold(
        containerColor = Color(0xFFF5F6FA),
        topBar = {
            // 紧凑顶栏：单行、~48dp，整体约占屏幕八分之一
            Surface(color = Color.White, shadowElevation = 0.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(48.dp)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 品牌标识：红点 + 简写校名
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .background(XynuRed, CircleShape)
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            "信师大",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1C1C1E)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "一站式校园服务",
                            fontSize = 10.sp,
                            color = Color(0xFF8E8E93),
                            modifier = Modifier.padding(top = 1.dp)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置", tint = Color(0xFF3A3A3C), modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 欢迎横幅
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = XynuRed)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Hi，师大人 👋", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Text("课表 · 成绩 · 图书馆 · 一网打尽", color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp)
                        }
                        Text("📘", fontSize = 34.sp)
                    }
                }
            }

            // 快捷功能
            item {
                SectionCard(title = "常用服务") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        FeatureTile(Icons.Filled.CalendarMonth, "我的课表", tint = XynuRed, onClick = onOpenSchedule, modifier = Modifier.weight(1f))
                        FeatureTile(Icons.Filled.Scoreboard, "成绩查询", tint = XynuBlue, onClick = onOpenScore, modifier = Modifier.weight(1f))
                        FeatureTile(Icons.Filled.Search, "馆藏检索", tint = XynuGold, onClick = onOpenBookSearch, modifier = Modifier.weight(1f))
                        FeatureTile(Icons.Filled.MeetingRoom, "空教室", tint = Color(0xFF2E7D32), onClick = onOpenFreeClassroom, modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        FeatureTile(Icons.Filled.School, "教务登录", tint = XynuRed, onClick = onOpenJwLogin, modifier = Modifier.weight(1f))
                        // 我的借阅：原生页（读者信息 + 当前借阅 + 借阅历史 + 续借）
                        FeatureTile(Icons.Filled.Book, "我的借阅", tint = XynuGold, onClick = onOpenMyBorrow, modifier = Modifier.weight(1f))
                        // 收藏书架：本地收藏的图书
                        FeatureTile(Icons.Filled.Favorite, "收藏书架", tint = XynuRed, onClick = onOpenFavorites, modifier = Modifier.weight(1f))
                        // 座位预约优先进入超星微信大厅，由真实页面处理座位入口和 OAuth。
                        FeatureTile(Icons.Filled.EventSeat, "座位预约", tint = Color(0xFF2E7D32),
                            onClick = { onOpenWeb(SEAT_RESERVATION_HALL_URL, "座位预约") }, modifier = Modifier.weight(1f))
                    }
                }
            }

            // 教务动态
            item {
                SectionCard(
                    title = "教务通知",
                    trailing = {
                        TextButton(
                            onClick = { onOpenWeb("http://jwc.xynu.edu.cn/tzgg.htm", "教务通知") },
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("查看更多", fontSize = 12.sp, color = XynuRed)
                        }
                    }
                ) {
                    if (state.jwcNews.isEmpty() && !state.loading) {
                        EmptyHint("暂无更新")
                    } else {
                        state.jwcNews.take(5).forEach { news ->
                            NewsRow(news) { onOpenWeb(news.url, news.title) }
                            HorizontalDivider(color = Color(0xFFF0F0F0))
                        }
                    }
                }
            }

            // 图书馆公告
            item {
                SectionCard(
                    title = "图书馆通知",
                    trailing = {
                        TextButton(
                            onClick = { onOpenWeb("http://lib.xynu.edu.cn/", "图书馆通知") },
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("查看更多", fontSize = 12.sp, color = XynuRed)
                        }
                    }
                ) {
                    if (state.libNews.isEmpty() && !state.loading) {
                        EmptyHint("暂无更新")
                    } else {
                        state.libNews.take(5).forEach { news ->
                            NewsRow(news) { onOpenWeb(news.url, news.title) }
                            HorizontalDivider(color = Color(0xFFF0F0F0))
                        }
                    }
                }
            }

            // 页脚
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Text("© 信阳师范大学 一站式服务（非官方）", fontSize = 11.sp, color = Color(0xFF8E8E93))
                }
            }
        }
    }
}

@Composable
private fun NewsRow(news: NewsItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(width = 3.dp, height = 12.dp)
                .background(XynuRed, RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(8.dp))
        Text(
            news.title,
            fontSize = 14.sp,
            color = Color(0xFF1C1C1E),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (news.date.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(news.date, fontSize = 11.sp, color = Color(0xFF8E8E93))
        }
    }
}

/** 用系统浏览器打开官方链接（已改为应用内 WebView，此函数保留供未来扩展） */
internal fun openInBrowser(context: android.content.Context, url: String) {
    try {
        val intent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse(url)
        )
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (_: Exception) { }
}

/**
 * 校园卡 / 水卡入口选择对话框。
 * 信阳师大校园卡由"完美校园"运营（无网页版，数据在支付宝小程序/APP），
 * 水卡由"易校园"运营。这里提供官方可信入口，避免在 App 内做资金操作。
 */
@Composable
private fun CardEntryDialog(
    onDismiss: () -> Unit,
    onOpenWeb: (String, String?) -> Unit,
    context: android.content.Context
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("校园卡 / 水卡") },
        text = {
            Column {
                Text(
                    "校园卡由「完美校园」运营，水卡由「易校园」运营。二者均无公开网页版，以下为官方可信入口：",
                    fontSize = 13.sp,
                    color = Color(0xFF6B7280)
                )
                Spacer(Modifier.height(14.dp))

                // 校园卡入口
                Text("🏫 校园卡（完美校园）", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { onDismiss(); onOpenWeb("https://www.17wanxiao.com/", "完美校园官网") },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("打开完美校园官网（下载APP）", fontSize = 13.sp) }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = {
                        onDismiss()
                        // 支付宝内打开完美校园小程序
                        try {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("alipays://platformapi/startapp?appId=20000067")
                            )
                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            onOpenWeb("https://www.17wanxiao.com/", "完美校园官网")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("在支付宝中打开完美校园小程序", fontSize = 13.sp) }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { onDismiss(); onOpenWeb("https://open.17wanxiao.com/", "完美校园开放平台") },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("完美校园开放平台（开发者）", fontSize = 13.sp) }

                Spacer(Modifier.height(14.dp))

                // 水卡入口
                Text("🚿 水卡（易校园）", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = {
                        onDismiss()
                        // 一键拉起应用商店安装易校园（官方包名）
                        var opened = false
                        try {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("market://details?id=cn.com.yunma.school.app")
                            )
                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                            opened = true
                        } catch (_: Exception) { }
                        if (!opened) {
                            try {
                                val intent = android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://www.hzsun.com/")
                                )
                                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            } catch (_: Exception) { }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("安装易校园 APP（水卡充值）", fontSize = 13.sp) }
                Spacer(Modifier.height(6.dp))
                Text(
                    "提示：在应用商店搜索「完美校园」或「易校园」下载 APP 后，绑定校园卡即可查余额、充值。",
                    fontSize = 12.sp,
                    color = Color(0xFFB0B0B4)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}