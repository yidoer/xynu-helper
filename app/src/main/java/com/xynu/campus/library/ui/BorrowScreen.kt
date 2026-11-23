package com.xynu.campus.library.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xynu.campus.library.model.BindState
import com.xynu.campus.library.parser.BorrowSummaryParser
import com.xynu.campus.library.util.HtmlText
import com.xynu.campus.library.vm.BorrowViewModel
import com.xynu.campus.ui.common.AppTopBar

/**
 * F3 借阅查询页：按三态渲染。
 *
 * - 缺 openid → 引导去 F1 向导
 * - 未绑读者证 → 引导去微信绑定（只读提示，不做 in-app 绑定）
 * - 已绑定 → 展示落地页原文（摘要解析器等真实 fixture）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BorrowScreen(
    vm: BorrowViewModel,
    onBack: () -> Unit,
    onOpenWizard: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    Scaffold(
        containerColor = Color(0xFFF5F6FA),
        topBar = {
            // 顶栏与主页同款（AppTopBar：白底、48dp、红点品牌标），
            // 不再用 Material 默认的 TopAppBar —— 那个在大字号下会撑到 64dp，
            // 和主页并排切换时高度会跳。
            AppTopBar(
                title = "我的借阅",
                onBack = onBack,
                action = {
                    if (state.hasOpenId) {
                        IconButton(onClick = { vm.refresh() }, modifier = Modifier.size(38.dp)) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "刷新",
                                tint = Color(0xFF3A3A3C),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            when {
                !state.hasOpenId -> EmptyIdentityCard(onOpenWizard)
                state.loading -> LoadingCard()
                state.bindState == BindState.UNBOUND_CARD -> UnboundCard(onOpenWizard)
                state.bindState == BindState.MISSING_OPENID -> MissingOpenIdCard(onOpenWizard)
                state.bindState == BindState.BOUND -> BoundContent(
                    html = state.landingHtml,
                    summary = state.summary,
                    landingUrl = state.landingUrl,
                    onOpenInBrowser = { url -> openInBrowser(context, url) },
                )
                // 有身份但还没查到过上游（或上次查询失败）：给「去查一次」的入口，
                // 不要复用 EmptyIdentityCard —— 那会说「还没有借阅身份」，
                // 和底部「本机保存的身份：…」直接冲突。
                else -> PendingCheckCard(onRefresh = { vm.refresh() })
            }

            state.message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.hasOpenId) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "本机保存的身份：${state.openidMasked ?: "***"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { vm.forgetIdentity() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("清除本机身份") }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun EmptyIdentityCard(onOpenWizard: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("还没有借阅身份", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "需要在微信里做一次复制操作。整个过程约 30 秒，只需要做一次。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onOpenWizard, modifier = Modifier.fillMaxWidth()) {
                Text("开始引导")
            }
        }
    }
}

@Composable
private fun PendingCheckCard(onRefresh: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("身份已保存", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "还没查到借阅记录。点下面按钮向图书馆服务器查询一次。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                Text("查询借阅")
            }
        }
    }
}

@Composable
private fun LoadingCard() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.width(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text("正在查询…", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun UnboundCard(onOpenWizard: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("身份已保存，但还没绑定读者证", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "本应用不代替微信做绑定。请到微信「图书馆公众号 → 个人中心」完成读者证绑定，然后回到这里点刷新。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onOpenWizard, modifier = Modifier.fillMaxWidth()) {
                Text("重新引导")
            }
        }
    }
}

@Composable
private fun MissingOpenIdCard(onOpenWizard: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("服务器没有接受这个身份", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "请重新从微信复制一次借阅信息页面的链接。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onOpenWizard, modifier = Modifier.fillMaxWidth()) {
                Text("重新引导")
            }
        }
    }
}

@Composable
private fun BoundContent(
    html: String?,
    summary: BorrowSummaryParser.Summary?,
    landingUrl: String?,
    onOpenInBrowser: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("借阅信息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            // 主路径：明细已经在应用内解析出来了。
            // 摘要 + 逐本书单都在，用户不需要跳浏览器。
            if (summary != null) {
                SummaryCard(summary)
                if (summary.items.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "借阅中",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    summary.items.forEach { BorrowItemRow(it) }
                }
                if (!html.isNullOrBlank()) {
                    var showRaw by remember(html) { mutableStateOf(false) }
                    TextButton(onClick = { showRaw = !showRaw }, modifier = Modifier.height(32.dp)) {
                        Text(
                            if (showRaw) "收起原文" else "查看服务器原文",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    if (showRaw) {
                        Text(
                            HtmlText.stripTags(html),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                landingUrl?.let {
                    Text(
                        // 只显示主机名，不显示完整地址：
                        // 那个 URL 里带着 openid 和 sn（相当于本次会话凭证），
                        // 直接铺在界面上会被截图/录屏/旁人看走，等同于明文泄露。
                        "数据来源：${hostOf(it)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            // 拿不到 HTML（或解析不出结构）：只报告「身份有效」并给出实时换新的地址。
            // 这一步仍然有用——新地址带服务端刚签发的 sn，不会像用户自己复制的旧链接那样「登录失效」。
            Text(
                "身份有效，服务器已放行。",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                if (html.isNullOrBlank()) {
                    "这次没有拿到借阅明细内容。下面是刚刚实时换到的新地址，" +
                        "不会像旧链接那样提示「登录失效」。"
                } else {
                    "没有识别出借阅字段，可以打开下面的地址看原始页面。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            landingUrl?.let { url ->
                Button(
                    onClick = { onOpenInBrowser(url) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("在浏览器打开借阅页") }
            }
        }
    }
}

/**
 * 从 URL 里只取 `host[:port]`，丢掉 path/query。
 *
 * 借阅落地地址的 query 里是 `openid` + `sn`，两者合起来就是本次会话的凭证。
 * 界面上只该告诉用户「数据来自哪台服务器」，不该把凭证摊开。
 *
 * 注意兜底分支：解析失败时**绝不能**回退成原始 url。
 * 原来写的是 `.getOrDefault(url)`，与上面这段注释直接矛盾——
 * 上游一旦返回含非法 URI 字符的地址（例如未编码的中文），
 * `Text("数据来源：$url")` 会把 openid + sn 整条铺在界面上，截图即泄露。
 * 现在任何失败路径都只返回一个固定占位。
 */
private fun hostOf(url: String): String =
    runCatching {
        val u = java.net.URI(url)
        val host = u.host ?: return@runCatching SAFE_HOST_FALLBACK
        if (u.port > 0) "$host:${u.port}" else host
    }.getOrDefault(SAFE_HOST_FALLBACK)

/** 解析不出 host 时的占位：只说"图书馆服务器"，不暴露任何 URL 片段。 */
private const val SAFE_HOST_FALLBACK = "学校图书馆服务器"

/** 一本借阅中的书。应还日期是用户最关心的，放最显眼的位置。 */
@Composable
private fun BorrowItemRow(item: BorrowSummaryParser.Item) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                item.title ?: "（无题名）",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item.dueDay?.let {
                    Text(
                        "应还 $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium,
                    )
                }
                item.renewCount?.let {
                    Text(
                        "已续借 $it 次",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item.barcode?.let {
                Text(
                    "条码 $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 用系统浏览器打开借阅业务页。
 *
 * 只在「解析不出结构」时作为兜底出口。主路径已经在应用内渲染明细，
 * 这里保留浏览器入口是因为：万一本校换模板导致解析失效，用户仍能看到原始页面，
 * 不至于「应用里什么都没有、也没别的路」。
 */
private fun openInBrowser(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** 结构化摘要卡：解析成功时展示，失败时整卡不出现。 */
@Composable
private fun SummaryCard(summary: BorrowSummaryParser.Summary) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            summary.headline(),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )

        val rows = buildList {
            summary.borrowable?.let { add("还可借" to "$it 册") }
            summary.historyTotal?.let { add("借阅历史" to "$it 册") }
            summary.dueDate?.let { add("最近应还" to it) }
            summary.renewCount?.let { add("续借次数" to "$it 次") }
            summary.unpaid?.let { add("欠费" to it) }
            summary.readerMasked?.let { add("读者证" to it) }
        }

        rows.forEach { (label, value) ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            }
        }
    }
}
