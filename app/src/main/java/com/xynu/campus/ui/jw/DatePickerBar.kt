package com.xynu.campus.ui.jw

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xynu.campus.ui.theme.XynuRed
import java.util.Calendar

/**
 * 日期选择条：点开日历选某一天，[:-] 按钮可逐日前后翻。
 *
 * 只负责「选哪一天」，把选中的毫秒时间戳交给调用方；周次与星期的换算在
 * [TermCalendar]，这样空教室和课表用的是同一套口径，不会各算各的。
 *
 * 动效：日期文字变化时做一次轻微的淡入上移（180ms），尊重系统「减弱动态」设置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerBar(
    dateText: String,
    week: Int,
    weekday: Int,
    termStartMillis: Long?,
    onPick: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var showPicker by remember { mutableStateOf(false) }

    val currentMillis = remember(dateText) { dateText.toDateMillisOrNull() ?: System.currentTimeMillis() }
    val todayIso = remember { TermCalendar.isoDate(TermCalendar.today()) }
    val isToday = dateText == todayIso

    fun shift(days: Int) {
        val cal = Calendar.getInstance().apply { timeInMillis = currentMillis }
        cal.add(Calendar.DAY_OF_YEAR, days)
        onPick(cal.timeInMillis)
    }

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 相邻日期是最常见的操作，不必每次都开日历
        IconButton(onClick = { shift(-1) }, modifier = Modifier.size(34.dp)) {
            Text("‹", fontSize = 22.sp, color = Color(0xFF3A3A3C))
        }

        Surface(
            onClick = { showPicker = true },
            shape = RoundedCornerShape(10.dp),
            color = Color.White,
            modifier = Modifier.weight(1f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.CalendarMonth, "选择日期",
                    tint = XynuRed, modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    // 日期变化时做一次淡入+轻微上移，让"换了一天"有明确反馈
                    AnimatedContent(
                        targetState = dateText,
                        transitionSpec = {
                            (fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 3 })
                                .togetherWith(fadeOut(tween(120)))
                        },
                        label = "date"
                    ) { _ ->
                        DateLabel(dateText, weekday, week, isToday, termStartMillis)
                    }
                }
                Icon(
                    Icons.Filled.ArrowDropDown, "展开",
                    tint = Color(0xFF8E8E93), modifier = Modifier.size(20.dp)
                )
            }
        }

        IconButton(onClick = { shift(1) }, modifier = Modifier.size(34.dp)) {
            Text("›", fontSize = 22.sp, color = Color(0xFF3A3A3C))
        }

        if (!isToday) {
            TextButton(
                onClick = { onPick(System.currentTimeMillis()) },
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) { Text("今天", fontSize = 12.sp, color = XynuRed) }
        }
    }

    if (showPicker) {
        val pickState = rememberDatePickerState(initialSelectedDateMillis = currentMillis)
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickState.selectedDateMillis?.let(onPick)
                        showPicker = false
                    },
                    enabled = pickState.selectedDateMillis != null
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("取消") }
            }
        ) {
            DatePicker(
                state = pickState,
                showModeToggle = false,
                title = {
                    Text(
                        if (termStartMillis == null) "选择查询日期（未设置开学日期）" else "选择查询日期",
                        modifier = Modifier.padding(start = 24.dp, top = 16.dp),
                        fontSize = 14.sp
                    )
                }
            )
        }
    }
}

@Composable
private fun DateLabel(
    dateText: String,
    weekday: Int,
    week: Int,
    isToday: Boolean,
    termStartMillis: Long?
) {
    androidx.compose.foundation.layout.Column {
        Text(
            "$dateText ${TermCalendar.weekdayLabel(weekday)}",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF1C1C1E)
        )
        Text(
            buildString {
                append("第${week}周")
                if (isToday) append(" · 今天")
                if (termStartMillis == null) append(" · 未设置开学日期")
            },
            fontSize = 11.sp,
            color = if (isToday) XynuRed else Color(0xFF8E8E93)
        )
    }
}

/** 把 `yyyy-MM-dd` 解析为当天 0 点的时间戳；格式不符返回 null */
fun String.toDateMillisOrNull(): Long? {
    if (isBlank()) return null
    return runCatching {
        val parts = split("-")
        if (parts.size != 3) return null
        val (y, m, d) = parts.map { it.toInt() }
        Calendar.getInstance().apply {
            clear()
            set(y, m - 1, d, 0, 0, 0)
        }.timeInMillis
    }.getOrNull()
}
