package com.xynu.campus.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xynu.campus.library.model.Seat
import com.xynu.campus.library.model.SeatVisual
import com.xynu.campus.library.seat.SeatNotifier
import com.xynu.campus.library.seat.SeatReminderScheduler
import com.xynu.campus.library.vm.SeatViewModel
import com.xynu.campus.ui.common.AppTopBar
import kotlinx.coroutines.delay

// 选座：当前预约、到期倒计时与 Cookie 粘贴弹窗（从 SeatScreen.kt 拆出，代码未改）

@Composable
internal fun CurrentReservationView(
    state: SeatViewModel.UiState,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← 返回列表") }
            Text("我的预约", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onRefresh) { Text("刷新") }
        }

        val cur = state.current
        if (cur == null || !cur.isActive) {
            Card {
                Text(
                    "当前没有生效的预约。",
                    Modifier.padding(16.dp),
                    fontSize = 13.sp,
                )
            }
            return@Column
        }

        Card {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "${cur.libName} ${cur.seatName}",
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Text("日期 ${cur.date}", fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                ExpiryCountdown(expDate = cur.expDate, fallback = cur.expDateStr)
            }
        }
    }
}

/**
 * 到期倒计时。
 *
 * 上游 `exp_date` 是秒级时间戳；`exp_date_str` 是已格式化文本。
 * 两者都缺时显示占位，不做错误倒计时。
 */
@Composable
private fun ExpiryCountdown(expDate: Long, fallback: String) {
    var remainSec by remember(expDate) { mutableIntStateOf(0) }

    LaunchedEffect(expDate) {
        if (expDate <= 0) return@LaunchedEffect
        while (true) {
            // 上游给秒级时间戳；也兼容毫秒
            val expMs = if (expDate > 100_000_000_000L) expDate else expDate * 1000L
            val left = (expMs - System.currentTimeMillis()) / 1000L
            remainSec = left.coerceAtLeast(0).toInt()
            if (remainSec <= 0) break
            delay(1000)
        }
    }

    when {
        expDate <= 0 && fallback.isNotBlank() ->
            Text("有效期至 $fallback", fontSize = 13.sp)

        expDate <= 0 ->
            Text("到期时间未知", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        else -> {
            val h = remainSec / 3600
            val m = (remainSec % 3600) / 60
            val s = remainSec % 60
            Text(
                if (remainSec > 0) "剩余 %d:%02d:%02d".format(h, m, s) else "已到期",
                fontWeight = FontWeight.SemiBold,
                color = if (remainSec > 0) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
            )
        }
    }
}

// ---------- Cookie 输入 ----------

/**
 * Cookie 粘贴对话框。
 *
 * 关键点：明确说明"Cookie 等于账号"，与 F5 的迁移警告口径一致，
 * 不能让用户以为这是个无关紧要的配置项。
 */
@Composable
internal fun CookieDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("粘贴选座 Cookie") },
        text = {
            Column {
                Text(
                    "怎么拿 Cookie：手机上打开微信 →「我去图书馆」小程序 → " +
                        "进到座位预约页 → 用抓包工具复制请求里的 Cookie。",
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "只要粘 Cookie 里带 wechatSESS_ID 和 Authorization 这两项就行，" +
                        "其余字段有就自动用上、没有也不影响。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "⚠️ Cookie 等于你的登录状态，等同于账号本身。" +
                        "它只会加密保存在这台手机上，绝不上传。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Cookie") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank(),
            ) { Text("验证并保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
