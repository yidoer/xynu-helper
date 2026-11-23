package com.xynu.campus.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xynu.campus.library.model.BindState
import com.xynu.campus.library.vm.BorrowViewModel
import com.xynu.campus.ui.common.AppTopBar

/**
 * F1 首次引导向导。
 *
 * 设计要求（任务书 F1）：图文三步 + 「我复制好了」按钮。
 * 剪贴板读取必须由**前台用户点击**触发（Android 10+ 限制），
 * 所以这里直接调 `vm.onClipboardReadRequested(ctx)`。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BorrowWizardScreen(
    vm: BorrowViewModel,
    onBack: () -> Unit,
    onFinished: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val ctx = LocalContext.current

    Scaffold(
        containerColor = Color(0xFFF5F6FA),
        topBar = { AppTopBar(title = "开启借阅查询", onBack = onBack) },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            Text(
                "只需在微信里操作一次",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "本应用不收集任何账号信息。这一步只是把你微信里已有的借阅身份复制到本机，之后查询借阅完全离线于微信。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(4.dp))

            // 三步图文
            state.wizardTitles.forEachIndexed { index, title ->
                val step = index + 1
                // 完成态是独立维度（wizardFinished），不再靠 wizardStep 越界到 4 表达。
                // 否则三步会同时渲染成 ✓ 且没有任何一步是 current，配色退化成一体，
                // 用户看不出"已全部完成"。
                val isCurrent = !state.wizardFinished && state.wizardStep == step
                val isDone = state.wizardFinished || state.wizardStep > step
                StepCard(
                    step = step,
                    title = title,
                    description = wizardDescription(step),
                    isCurrent = isCurrent,
                    isDone = isDone,
                )
            }

            // 全部完成时给一句明确的收尾，而不是让三步都挂着 ✓ 就结束。
            if (state.wizardFinished) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "三步都做完了。回到借阅页即可看到你的借阅信息。",
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            state.message?.let { msg ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        msg,
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            if (state.loading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("正在校验…", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Button(
                    onClick = { vm.onClipboardReadRequested(ctx) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("我复制好了", fontWeight = FontWeight.SemiBold)
                }
            }

            // 手动粘贴兜底。
            //
            // 为什么要留这条路：Android 12+ 起系统对后台读剪贴板越来越严，
            // 部分机型（含 Android 15）应用内读剪贴板会拿到空内容，主按钮
            // 就只剩「没读取到剪贴板内容」这一个结果。这里让用户自己长按粘贴，
            // 后面的提取、校验、入库流程与主按钮完全一致——不是另一套逻辑。
            PasteLinkBox(
                enabled = !state.loading,
                onSubmit = vm::onSubmitLinkText,
            )

            // 已拿到 openid 但尚未绑读者证时，给「去微信绑定」引导
            if (state.bindState == BindState.UNBOUND_CARD) {
                OutlinedButton(
                    onClick = onFinished,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) { Text("我知道了，先去微信绑定") }
            }

            OutlinedButton(
                onClick = onFinished,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("稍后再说") }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StepCard(
    step: Int,
    title: String,
    description: String,
    isCurrent: Boolean,
    isDone: Boolean,
) {
    val container = when {
        isCurrent -> MaterialTheme.colorScheme.primaryContainer
        isDone -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .background(
                        if (isDone) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (isDone) "✓" else step.toString(),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun wizardDescription(step: Int): String = when (step) {
    1 -> "在微信里搜索并打开「信阳师范大学图书馆」服务号/公众号"
    2 -> "公众号底部菜单进入个人中心，再点「借阅信息」"
    3 -> "在弹出的页面点右上角「···」，选「复制链接」，然后回到本应用点下面的按钮"
    else -> ""
}

/**
 * 手动粘贴链接的兜底入口。
 *
 * 主路径是「我复制好了」直接读剪贴板；但系统剪贴板权限收紧时读不到，
 * 这条兜底就能让用户自己把链接贴进来。收起状态下只占一行文字，
 * 不抢主按钮的注意力。
 */
@Composable
private fun PasteLinkBox(
    enabled: Boolean,
    onSubmit: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }

    if (!expanded) {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("读不到剪贴板？手动粘贴链接", style = MaterialTheme.typography.bodySmall)
        }
        return
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "把刚才复制的链接整段粘到下面",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("http://mobilelib…openid=…", style = MaterialTheme.typography.bodySmall) },
                minLines = 3,
                maxLines = 5,
                enabled = enabled,
                textStyle = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onSubmit(text) },
                    enabled = enabled && text.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text("用这段链接") }
                TextButton(
                    onClick = {
                        expanded = false
                        text = ""
                    },
                ) { Text("收起") }
            }
        }
    }
}
