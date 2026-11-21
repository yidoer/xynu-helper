package com.xynu.campus.ui.jw

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 学期日期选择的两个共享弹窗。
 *
 * 早期两处（设置页、课表页）各自用「年 / 月 / 日」三个 `OutlinedTextField`，
 * 并把值直接绑成 Int：
 *     onValueChange = { year = it.toIntOrNull() ?: year }
 * 清空输入框时 `""` 转 Int 得到 null，旧值被原样写回，于是出现
 * 「数字总剩一位删不掉」。改日历选择后不存在输入框，这个状态同步问题随之消失。
 */

/** 设置开学日期（第 1 周第 1 天） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermStartPickerDialog(
    currentMillis: Long?,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val pickState = rememberDatePickerState(
        initialSelectedDateMillis = currentMillis ?: System.currentTimeMillis()
    )
    // 未选日期时禁用「保存」，避免 null 被当成合法开学日期
    val picked = pickState.selectedDateMillis

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置开学日期") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "选择本学期的开学日期（第 1 周第 1 天）。" +
                        "课表与空教室会自动定位到当前周并显示对应日期。",
                    fontSize = 12.sp,
                    color = Color(0xFF6B7280)
                )
                Box(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    DatePicker(
                        state = pickState,
                        showModeToggle = false,
                        title = null,
                        headline = null
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { picked?.let(onConfirm) }, enabled = picked != null) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/**
 * 选一个日期，查看它属于第几周。
 *
 * @param onPick 回调选中的毫秒时间戳；调用方用 [TermCalendar.weekOf] 换算周次
 * @param requireTermStart 未设置开学日期时为 true，弹窗提示先去设置
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateJumpDialog(
    initialMillis: Long?,
    requireTermStart: Boolean,
    title: String = "选择日期",
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val pickState = rememberDatePickerState(
        initialSelectedDateMillis = remember(initialMillis) {
            initialMillis ?: System.currentTimeMillis()
        }
    )
    val picked = pickState.selectedDateMillis

    // 不要用 DatePickerDialog 的 title 槽：该槽按 headline 字号排版，
    // 长中文句子（「选择日期，跳到对应周次」）会被撑成超大灰字并折行，
    // 顶部灰块也和文字错位，看起来像被裁掉一截。
    // 统一改用 AlertDialog：标题走普通对话框标题样式，日历本体隐藏自带的
    // title/headline，只留月份头 + 日期网格，整块日历才能完整显示。
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (requireTermStart) "请先设置开学日期" else title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (requireTermStart) {
                    Text(
                        "开学日期还没有设置，无法把日期换算成第几周。请先到「我的课表」" +
                            "右上角齿轮里设置开学日期。",
                        fontSize = 12.sp,
                        color = Color(0xFF6B7280)
                    )
                }
                Box(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    DatePicker(
                        state = pickState,
                        showModeToggle = false,
                        title = null,
                        headline = null
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { picked?.let(onPick) }, enabled = picked != null) {
                Text("确定")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
