package com.xynu.campus.ui.jw

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xynu.campus.ui.theme.XynuRed
import java.util.Calendar

/**
 * 学期日期选择的两个共享弹窗。
 *
 * 早期两处（设置页、课表页）各自用「年 / 月 / 日」三个 `OutlinedTextField`，
 * 并把值直接绑成 Int：
 *     onValueChange = { year = it.toIntOrNull() ?: year }
 * 清空输入框时 `""` 转 Int 得到 null，旧值被原样写回，于是出现
 * 「数字总剩一位删不掉」。改日历选择后不存在输入框，这个状态同步问题随之消失。
 *
 * ## 为什么不再用 M3 的 DatePicker
 *
 * 前后踩了三次坑，都是围绕 M3 `DatePicker` 的排版：
 *
 * 1. 用 `DatePickerDialog` + 自定义 `title` 槽：该槽按 headlineLarge（32sp 级）
 *    渲染，长中文提示被撑成巨字并折行，顶部灰底只盖住一部分 → 像被裁掉一截。
 * 2. 改用 `AlertDialog` + `text` 槽放 `DatePicker`：`AlertDialog` 的内容宽度被
 *    压到平台默认 ~280dp，而月历是 7 列固定网格（约 360dp），第 7 列被裁掉。
 * 3. 容器换回 `DatePickerDialog`、标题与日历平级摆放：`DatePicker` 内部
 *    `fillMaxHeight` 会把同级的标题压成 0 高度，标题直接消失。
 * 4. 期间还试过给日历外套 `horizontalScroll`：横向滚动使宽度约束变成无限，
 *    月历内部 `Row { weight(...) }` 在无限约束下测量死循环，App 直接 ANR。
 *
 * 除了这些约束问题，M3 月历本身的排版也不适合这里：它跟随系统 Locale，
 * 英文环境下标题是 `November 2026`、表头是 `S M T W T F S`，和全中文界面混排；
 * 行高与内边距是内置常量，改不动；周首随 Locale 可能是周日，而课表页固定
 * 按「周一…周日」展示，两边对不上。
 *
 * 所以这里自绘一个月历网格，排版参数全部显式给定：
 * - 周首固定周一，与课表表头一致
 * - 行高固定 46dp、日期字号 15sp、表头 11sp，行与行等高，切换月份不会跳动
 * - 固定 6 行网格（不足补空），底部的按钮位置稳定，不会随月份上下窜
 *
 * ## 排版规格
 *
 * 字号只分三档，避免「17/16/15/11」这种挤在一起分不清主次的排法：
 * 标题行 17sp、正文数字 15sp、辅助说明 11–12sp。
 *
 * | 元素 | 字号 | 字重 | 行高/尺寸 | 颜色 |
 * |---|---|---|---|---|
 * | 弹窗标题 | 17sp | SemiBold | — | 0xFF1C1C1E |
 * | 说明文字 | 12sp | Normal | lineHeight 18sp | 0xFF8E8E93 |
 * | 月份标题 | 15sp | Medium | 行高 44dp | 0xFF1C1C1E |
 * | 星期表头 | 11sp | Normal | 行高 28dp | 0xFF9CA3AF |
 * | 日期数字 | 15sp | Normal / 选中·今天 SemiBold | 单元格 42dp，圆 36dp | 选中白 / 今天红 / 其余 0xFF1C1C1E |
 * | 底部按钮 | 14sp | Medium | 高 40dp | XynuRed / 取消 0xFF6B7280 |
 *
 * 单元格 42dp 略小于列宽（约 50dp），横向留白比纵向多一点，
 * 视觉上比等宽等高的正方格更松弛，也不会显得挤。
 */

private const val DAY_MILLIS = 86_400_000L

/** 星期表头：固定周一为首，与课表页表头口径一致 */
private val WEEKDAY_HEADERS = listOf("一", "二", "三", "四", "五", "六", "日")

/**
 * 某年某月的日期网格，固定 42 格（6 行 × 7 列）。
 * 值为 null 表示该格留空（属于上/下月的日子不显示，减少视觉噪音）。
 */
private fun monthGrid(year: Int, month0: Int): List<Int?> {
    val cal = Calendar.getInstance().apply {
        clear()
        set(year, month0, 1)
    }
    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    // 周一=0 … 周日=6
    val lead = when (cal.get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY -> 0
        Calendar.TUESDAY -> 1
        Calendar.WEDNESDAY -> 2
        Calendar.THURSDAY -> 3
        Calendar.FRIDAY -> 4
        Calendar.SATURDAY -> 5
        else -> 6
    }
    return List(42) { idx -> (idx - lead + 1).takeIf { it in 1..daysInMonth } }
}

/** 某天 00:00 的时间戳（本机时区） */
private fun millisOf(year: Int, month0: Int, day: Int): Long =
    Calendar.getInstance().apply {
        clear()
        set(year, month0, day)
    }.timeInMillis

/** 时间戳 → (年, 月0, 日) */
private fun ymdOf(millis: Long): Triple<Int, Int, Int> {
    val cal = Calendar.getInstance().apply { timeInMillis = millis }
    return Triple(
        cal.get(Calendar.YEAR),
        cal.get(Calendar.MONTH),
        cal.get(Calendar.DAY_OF_MONTH)
    )
}

/**
 * 自绘月历。
 *
 * @param selectedMillis 当前选中日期，null 表示尚未选择
 * @param onSelect 点选某天的回调
 */
@Composable
private fun MonthCalendar(
    selectedMillis: Long?,
    onSelect: (Long) -> Unit
) {
    val today = remember { ymdOf(System.currentTimeMillis()) }
    // 初始展示月份跟随已选日期，没选过就展示今天所在月
    val initial = remember(selectedMillis) {
        ymdOf(selectedMillis ?: System.currentTimeMillis())
    }
    var shownYear by remember(selectedMillis) { mutableStateOf(initial.first) }
    var shownMonth by remember(selectedMillis) { mutableStateOf(initial.second) }

    val cellDays = remember(shownYear, shownMonth) { monthGrid(shownYear, shownMonth) }
    val selected = selectedMillis?.let { ymdOf(it) }

    Column(Modifier.fillMaxWidth()) {
        // ── 月份切换条 ────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .height(44.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    if (shownMonth == 0) {
                        shownMonth = 11; shownYear -= 1
                    } else {
                        shownMonth -= 1
                    }
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Filled.ChevronLeft, "上个月",
                    tint = Color(0xFF6B7280), modifier = Modifier.size(20.dp)
                )
            }
            Text(
                "${shownYear}年${shownMonth + 1}月",
                modifier = Modifier.weight(1f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF1C1C1E),
                textAlign = TextAlign.Center
            )
            IconButton(
                onClick = {
                    if (shownMonth == 11) {
                        shownMonth = 0; shownYear += 1
                    } else {
                        shownMonth += 1
                    }
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Filled.ChevronRight, "下个月",
                    tint = Color(0xFF6B7280), modifier = Modifier.size(20.dp)
                )
            }
        }

        // ── 星期表头 ──────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .height(28.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WEEKDAY_HEADERS.forEach { label ->
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    fontSize = 11.sp,
                    color = Color(0xFF9CA3AF),
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(Modifier.height(2.dp))

        // ── 日期网格：固定 6 行，行高一致 ──────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            cellDays.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { day ->
                        DayCell(
                            day = day,
                            isSelected = day != null &&
                                selected?.let { it.first == shownYear && it.second == shownMonth && it.third == day } == true,
                            isToday = day != null &&
                                today.first == shownYear && today.second == shownMonth && today.third == day,
                            onClick = { day?.let { onSelect(millisOf(shownYear, shownMonth, it)) } }
                        )
                    }
                }
            }
        }
    }
}

/** 单个日期格：整格可点（46dp 高），选中态为实心圆，今天为红圈 */
@Composable
private fun RowScope.DayCell(
    day: Int?,
    isSelected: Boolean,
    isToday: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(42.dp)
            .then(if (day != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (day == null) return@Box
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (isSelected) XynuRed else Color.Transparent)
                .then(
                    if (isToday && !isSelected) {
                        Modifier.border(1.dp, XynuRed, CircleShape)
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                day.toString(),
                fontSize = 15.sp,
                fontWeight = if (isSelected || isToday) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    isSelected -> Color.White
                    isToday -> XynuRed
                    else -> Color(0xFF1C1C1E)
                }
            )
        }
    }
}

/** 弹窗外壳：统一圆角、留白与底部按钮位置 */
@Composable
private fun CalendarDialogShell(
    title: String,
    subtitle: String?,
    confirmText: String,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    selectedMillis: Long?,
    onSelect: (Long) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        // 关掉平台默认宽度（~280dp），否则 7 列网格放不下会把第 7 列裁掉
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color.White,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            Column(Modifier.padding(top = 18.dp, bottom = 6.dp)) {
                // 标题区
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        title,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1C1C1E)
                    )
                    subtitle?.let {
                        Text(
                            it,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = Color(0xFF8E8E93)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))

                MonthCalendar(selectedMillis = selectedMillis, onSelect = onSelect)

                // 按钮区：右对齐，与标题左对齐形成对角，避免整块内容居中显得散
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss, modifier = Modifier.height(40.dp)) {
                        Text("取消", fontSize = 14.sp, color = Color(0xFF6B7280))
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(
                        onClick = onConfirm,
                        enabled = confirmEnabled,
                        modifier = Modifier.height(40.dp)
                    ) {
                        Text(
                            confirmText,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (confirmEnabled) XynuRed else Color(0xFFC7C7CC)
                        )
                    }
                }
            }
        }
    }
}

/** 设置开学日期（第 1 周第 1 天） */
@Composable
fun TermStartPickerDialog(
    currentMillis: Long?,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var picked by remember { mutableStateOf(currentMillis) }

    CalendarDialogShell(
        title = "设置开学日期",
        subtitle = "选择本学期第 1 周第 1 天。课表与空教室会自动定位到当前周并显示对应日期。",
        confirmText = "保存",
        // 未选日期时禁用，避免 null 被当成合法开学日期
        confirmEnabled = picked != null,
        onConfirm = { picked?.let(onConfirm) },
        onDismiss = onDismiss,
        selectedMillis = picked,
        onSelect = { picked = it }
    )
}

/**
 * 选一个日期，查看它属于第几周。
 *
 * @param onPick 回调选中的毫秒时间戳；调用方用 [TermCalendar.weekOf] 换算周次
 * @param requireTermStart 未设置开学日期时为 true，弹窗顶部提示先去设置
 */
@Composable
fun DateJumpDialog(
    initialMillis: Long?,
    requireTermStart: Boolean,
    title: String = "选择日期",
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var picked by remember(initialMillis) { mutableStateOf(initialMillis) }

    CalendarDialogShell(
        title = if (requireTermStart) "请先设置开学日期" else title,
        subtitle = if (requireTermStart) {
            "开学日期还没有设置，无法把日期换算成第几周。" +
                "请先到「我的课表」右上角齿轮里设置开学日期。"
        } else null,
        confirmText = "确定",
        confirmEnabled = picked != null,
        onConfirm = { picked?.let(onPick) },
        onDismiss = onDismiss,
        selectedMillis = picked,
        onSelect = { picked = it }
    )
}
