package com.xynu.campus.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xynu.campus.ui.common.AppTopBar
import com.xynu.campus.ui.jw.JwViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    jwVm: JwViewModel? = null
) {
    var showTermStartDialog by remember { mutableStateOf(false) }
    var termStartText by remember { mutableStateOf("未设置") }

    // 读取开学日期
    LaunchedEffect(jwVm) {
        jwVm?.state?.value?.termStartMillis?.let { millis ->
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            termStartText = fmt.format(Date(millis))
        }
    }
    val state = jwVm?.state?.collectAsState()?.value
    LaunchedEffect(state?.termStartMillis) {
        state?.termStartMillis?.let { millis ->
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            termStartText = fmt.format(Date(millis))
        }
    }

    if (showTermStartDialog && jwVm != null) {
        TermStartDialog(
            currentMillis = state?.termStartMillis,
            onConfirm = { millis ->
                jwVm.setTermStart(millis)
                showTermStartDialog = false
            },
            onDismiss = { showTermStartDialog = false }
        )
    }

    Scaffold(
        containerColor = Color(0xFFF5F6FA),
        topBar = {
            AppTopBar(title = "设置与关于", onBack = onBack)
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // 课表设置
            if (jwVm != null) {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(Modifier.padding(4.dp)) {
                        Text(
                            "课表",
                            fontSize = 12.sp,
                            color = Color(0xFF8E8E93),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        SettingRow("开学日期", subtitle = termStartText) {
                            showTermStartDialog = true
                        }
                        if (state?.currentWeek != null) {
                            HorizontalDivider(color = Color(0xFFF0F0F0), modifier = Modifier.padding(horizontal = 16.dp))
                            SettingRow("当前周次", subtitle = "第 ${state.currentWeek} 周 · 周${"一二三四五六日"[state.todayDayOfWeek - 1]}") { }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(Modifier.padding(4.dp)) {
                    var showAbout by remember { mutableStateOf(false) }
                    SettingRow("关于本应用") { showAbout = true }
                    HorizontalDivider(color = Color(0xFFF0F0F0), modifier = Modifier.padding(horizontal = 16.dp))
                    SettingRow("数据来源说明") {
                        showAbout = true
                    }
                    HorizontalDivider(color = Color(0xFFF0F0F0), modifier = Modifier.padding(horizontal = 16.dp))
                    SettingRow("免责声明") {
                        showAbout = true
                    }
                    if (showAbout) {
                        AboutDialog(onDismiss = { showAbout = false })
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(Modifier.padding(4.dp)) {
                    SettingRow("版本 1.1.0") { }
                }
            }
        }
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String? = null, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = Color(0xFF1C1C1E))
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, fontSize = 12.sp, color = Color(0xFF8E8E93))
            }
        }
        if (onClick != null) {
            Text(">", color = Color(0xFFC7C7CC), fontSize = 14.sp)
        }
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
        title = { Text("关于本应用") },
        text = {
            Text(
                "「信师大」是一款面向信阳师范大学学生的一站式校园服务应用，\n\n" +
                    "整合了：\n" +
                    "· 教务系统：课表、成绩查询\n" +
                    "· 图书馆：馆藏检索、借阅查询、借阅历史、收藏书架\n" +
                    "· 校园资讯：教务处、图书馆通知\n\n" +
                    "本应用为个人开发的非官方工具，与学校无隶属关系。\n" +
                    "登录信息仅保存在本机（Keystore 加密），本应用不会上传任何个人数据。",
                fontSize = 13.sp
            )
        }
    )
}

@Composable
private fun TermStartDialog(
    currentMillis: Long?,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val cal = Calendar.getInstance()
    var year by remember {
        mutableStateOf(
            currentMillis?.let {
                val c = Calendar.getInstance().apply { timeInMillis = it }
                c.get(Calendar.YEAR)
            } ?: cal.get(Calendar.YEAR)
        )
    }
    var month by remember {
        mutableStateOf(
            currentMillis?.let {
                val c = Calendar.getInstance().apply { timeInMillis = it }
                c.get(Calendar.MONTH) + 1
            } ?: 9
        )
    }
    var day by remember {
        mutableStateOf(
            currentMillis?.let {
                val c = Calendar.getInstance().apply { timeInMillis = it }
                c.get(Calendar.DAY_OF_MONTH)
            } ?: 1
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置开学日期") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "选择本学期的开学日期（第 1 周第 1 天），课表将自动定位到当前周并显示日期。",
                    fontSize = 12.sp,
                    color = Color(0xFF6B7280)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = year.toString(),
                        onValueChange = { year = it.toIntOrNull() ?: year },
                        label = { Text("年") },
                        modifier = Modifier.weight(1.2f)
                    )
                    OutlinedTextField(
                        value = month.toString(),
                        onValueChange = { month = (it.toIntOrNull() ?: month).coerceIn(1, 12) },
                        label = { Text("月") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = day.toString(),
                        onValueChange = { day = (it.toIntOrNull() ?: day).coerceIn(1, 31) },
                        label = { Text("日") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val c = Calendar.getInstance().apply {
                    set(year, month - 1, day, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                onConfirm(c.timeInMillis)
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
