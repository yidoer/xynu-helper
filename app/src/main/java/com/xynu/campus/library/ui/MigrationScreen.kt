package com.xynu.campus.library.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xynu.campus.library.migrate.IdentityMigration
import com.xynu.campus.library.store.IdentityStore
import com.xynu.campus.ui.common.AppTopBar

/**
 * F5 身份迁移页。
 *
 * 核心 UX 原则：**先警告，后操作**。
 * 迁移码里是 openid + Cookie，等于账号本体；不把这句话说在最前面，
 * 用户就会把它当普通配置随手发到群里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MigrationScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { IdentityStore.get(ctx) }
    val clipboard = LocalClipboardManager.current

    var exported by remember { mutableStateOf<IdentityMigration.Payload?>(null) }
    var importText by remember { mutableStateOf("") }
    var resultMessage by remember { mutableStateOf("") }
    var confirmExport by remember { mutableStateOf(false) }
    var confirmImport by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color(0xFFF5F6FA),
        topBar = { AppTopBar(title = "身份迁移", onBack = onBack) },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WarningCard()

            if (resultMessage.isNotBlank()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Text(resultMessage, Modifier.padding(12.dp), fontSize = 13.sp)
                }
            }

            // ---- 导出 ----
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("导出到新手机", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "生成一段迁移码，复制到新手机的同一个页面粘贴即可。" +
                            "注意：这段码只能你自己看，不要发到群里或发给任何人。",
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { confirmExport = true }) { Text("生成迁移码") }

                    exported?.let { p ->
                        Spacer(Modifier.height(12.dp))
                        Text(p.summary, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        ) {
                            SelectionContainer {
                                Text(
                                    p.text,
                                    Modifier.padding(10.dp),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                clipboard.setText(androidx.compose.ui.text.AnnotatedString(p.text))
                                resultMessage = "迁移码已复制到剪贴板。请立刻粘贴到新手机，然后清空剪贴板。"
                            }) { Text("复制迁移码") }
                        }
                    }
                }
            }

            // ---- 导入 ----
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("从旧手机导入", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    // Compose 的 Text 不解析 Markdown，这里用 buildAnnotatedString 做局部加粗，
                    // 否则字面上的 ** 会直接显示给用户。
                    Text(
                        buildAnnotatedString {
                            append("粘贴旧手机生成的迁移码。导入会")
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                append("整体替换")
                            }
                            append("本机现有身份，不是合并。")
                        },
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        label = { Text("迁移码") },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { confirmImport = true },
                        enabled = importText.isNotBlank(),
                    ) { Text("导入") }
                }
            }

            // ---- 清空 ----
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("清空本机身份", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "把 openid 和选座 Cookie 从这台手机彻底删掉。删之前记得先导出，否则找不回来。",
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = {
                        store.clearAll()
                        exported = null
                        resultMessage = "已清空本机的图书馆身份。"
                    }) { Text("清空") }
                }
            }
        }
    }

    if (confirmExport) {
        AlertDialog(
            onDismissRequest = { confirmExport = false },
            title = { Text("确认导出？") },
            text = {
                Text(
                    "导出的内容包含你的 openid 和选座 Cookie，等同于你的图书馆账号。" +
                        "拿到这段码的人可以用你的身份查借阅、占座位。\n\n" +
                        "确定要生成吗？",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmExport = false
                    exported = IdentityMigration.export(store)
                    resultMessage = if (exported == null) {
                        "本机还没有保存任何图书馆身份，没有东西可以导出。先去完成一次身份配置。"
                    } else {
                        "迁移码已生成，请复制。"
                    }
                }) { Text("生成") }
            },
            dismissButton = {
                TextButton(onClick = { confirmExport = false }) { Text("取消") }
            },
        )
    }

    if (confirmImport) {
        AlertDialog(
            onDismissRequest = { confirmImport = false },
            title = { Text("确认导入？") },
            text = {
                Text(
                    "导入会清空并覆盖本机现有的图书馆身份。" +
                        "如果这台手机上已经配置过，原来的配置会丢失。\n\n" +
                        "确定要导入吗？",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmImport = false
                    when (val r = IdentityMigration.import(store, importText)) {
                        is IdentityMigration.ImportResult.Success -> {
                            val parts = buildList {
                                if (r.hasBorrow) add("借阅身份")
                                if (r.hasSeat) add("选座 Cookie")
                            }
                            resultMessage = "导入成功：${parts.joinToString("、")}。" +
                                if (r.hasSeat) "选座 Cookie 还没验证过，进选座页会用一次网络确认。" else ""
                            importText = ""
                        }

                        is IdentityMigration.ImportResult.Invalid -> {
                            resultMessage = r.reason
                        }
                    }
                }) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = { confirmImport = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun WarningCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "⚠️ 迁移码 = 你的账号",
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "这段码里装的是 openid 和选座 Cookie。它们不是「设置」，" +
                    "而是图书馆系统认你为你的凭据——谁拿到，谁就能以你的名义借书、占座。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "所以：只在你自己两台设备之间传；传完把聊天记录和剪贴板清掉；" +
                    "不要截图发人，不要存网盘。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
