package com.xynu.campus.library.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
import com.xynu.campus.library.model.Seat
import com.xynu.campus.library.model.SeatVisual
import com.xynu.campus.library.seat.SeatNotifier
import com.xynu.campus.library.seat.SeatReminderScheduler
import com.xynu.campus.library.vm.SeatViewModel
import com.xynu.campus.ui.common.AppTopBar
import kotlinx.coroutines.delay

/**
 * F4 选座页。
 *
 * 两档在一个页面里，但视觉上完全分开：
 * - 基础档（默认）：只有提醒配置 + 跳微信按钮，**没有任何联网元素**
 * - 高级档：列表 → 座位图 → 当前预约
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeatScreen(
    vm: SeatViewModel,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val ctx = LocalContext.current
    var showCookieDialog by remember { mutableStateOf(false) }

    // 通知权限申请的挂起参数：授权回来后才知道要排几点。
    val pendingReminder = remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val slot = pendingReminder.value
        pendingReminder.value = null
        if (slot != null) {
            val (h, m) = slot
            // 无论授权与否都把每日提醒排上：
            //  - 授权了：到点正常弹通知；
            //  - 没授权：通知会被系统丢弃，但排程仍然存在，
            //    用户之后去系统设置里打开通知就能立刻生效，不必回来重设。
            // 唯一不做的是"假装成功了"——这里不写成功提示，交给下面的说明文案。
            SeatReminderScheduler(ctx).scheduleDaily(h, m)
            if (!granted) {
                // 复用 MessageCard 通道给一句可执行的提示。
                // 不弹系统对话框二次骚扰，只说清"去哪儿打开"。
                android.widget.Toast.makeText(
                    ctx,
                    "已设置每日提醒。系统还没允许通知，请在「设置 → 应用 → 通知」里打开，否则到点不会响。",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    Scaffold(
        containerColor = Color(0xFFF5F6FA),
        topBar = {
            // 与主页同款顶栏；档位标签仍然放右侧，但收进 38dp 的触控位里，
            // 不再用 TopAppBar 的 actions（会把标题行撑高）。
            AppTopBar(
                title = "图书馆选座",
                onBack = onBack,
                action = { TierChip(state.tier) },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp),
        ) {
            if (state.sessionInvalid) {
                InvalidSessionBanner(
                    onRepaste = { showCookieDialog = true },
                )
                Spacer(Modifier.height(8.dp))
            }

            // 会话失效时不再弹 MessageCard。
            // 上面那条红色横幅已经说了「登录状态已失效」并给了「重新粘贴 Cookie」按钮，
            // 再叠一条紫色提示说同一件事（用户实测截图里 40001 被讲了两遍），
            // 纯属噪音。
            if (state.message.isNotBlank() && !state.sessionInvalid) {
                MessageCard(state.message, onDismiss = vm::clearMessage)
                Spacer(Modifier.height(8.dp))
            }

            when {
                state.loading -> LoadingBox()
                state.tier == SeatViewModel.Tier.BASIC -> BasicTierContent(
                    reminderHour = state.reminderHour,
                    reminderMinute = state.reminderMinute,
                    onConfigureReminder = { h, m ->
                        // Android 13+ 需要在运行时申请 POST_NOTIFICATIONS。
                        //
                        // 此前全仓**没有任何**运行时申请点（grep
                        // rememberLauncherForActivityResult 为 0 命中），
                        // 而 manifest 里只写了 <uses-permission>。
                        // 结果：targetSdk 33+ 上通知默认不弹，到点了永远静默，
                        // 而 UI 上看不到任何异常 —— 提醒功能等于完全失效。
                        // 申请时机放在用户真正点「每天 xx:xx 提醒」这一刻，
                        // 不搞启动即弹。
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                ctx,
                                Manifest.permission.POST_NOTIFICATIONS,
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            pendingReminder.value = h to m
                            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            SeatReminderScheduler(ctx).scheduleDaily(h, m)
                        }
                    },
                    onOpenWeChat = {
                        // from_code 由 hall 页动态提取（SeatViewModel.loadFromCode）。
                        // 拿到了就拼「直接落到本校选座」的深链；没拿到就降级成
                        // 只开微信 + 通用网页，不会拼出指向别校的错误链接。
                        val chain = SeatNotifier.jumpChain(state.fromCode.ifBlank { null })
                        SeatNotifier.openWithFallback(ctx, chain)
                    },
                    onUnlockAdvanced = { showCookieDialog = true },
                )

                else -> AdvancedTierContent(
                    state = state,
                    onOpenLayout = vm::openLayout,
                    onSelectSeat = vm::selectSeat,
                    onReserve = vm::reserveSelectedSeat,
                    onBackToList = vm::backToLibraries,
                    onRefreshCurrent = vm::loadCurrent,
                    // throttle 会拦掉 3s 内的连点；下拉刷新属于用户显式意图，强制放过
                    onRefreshLibraries = { vm.loadLibraries(force = true) },
                    onForget = vm::forgetSession,
                )
            }
        }
    }

    if (showCookieDialog) {
        CookieDialog(
            onDismiss = { showCookieDialog = false },
            onConfirm = {
                showCookieDialog = false
                vm.submitCookie(it)
            },
        )
    }
}

@Composable
private fun TierChip(tier: SeatViewModel.Tier) {
    val (label, color) = when (tier) {
        SeatViewModel.Tier.BASIC ->
            "基础档" to MaterialTheme.colorScheme.surfaceVariant

        SeatViewModel.Tier.ADVANCED ->
            "高级档" to MaterialTheme.colorScheme.primaryContainer
    }
    Box(
        modifier = Modifier
            .padding(end = 12.dp)
            .background(color, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(label, fontSize = 12.sp)
    }
}

@Composable
private fun LoadingBox() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun MessageCard(message: String, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, Modifier.weight(1f), fontSize = 13.sp)
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    }
}

@Composable
private fun InvalidSessionBanner(onRepaste: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("登录状态已失效", fontWeight = FontWeight.SemiBold)
            Text(
                "图书馆那边的登录过期了（错误码 40001）。需要重新复制一次 Cookie 才能继续。",
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onRepaste) { Text("重新粘贴 Cookie") }
        }
    }
}

// ---------- 基础档 ----------

/**
 * 基础档 UI：**零凭据、零网络**。
 *
 * 文案必须把边界说清：本档只做提醒，不做自动抢座，
 * 全自动需要一次性 Cookie 配置（高级档）。
 */
@Composable
private fun BasicTierContent(
    reminderHour: Int,
    reminderMinute: Int,
    onConfigureReminder: (Int, Int) -> Unit,
    onOpenWeChat: () -> Unit,
    onUnlockAdvanced: () -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("到点提醒你去选座", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "设定一个提醒时间，到点会弹一条本地通知，点一下直接跳微信选座。",
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "这一档不联网、不需要登录、不保存任何账号信息。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onConfigureReminder(reminderHour, reminderMinute) }) {
                            Text("每天 %02d:%02d 提醒".format(reminderHour, reminderMinute))
                        }
                        OutlinedButton(onClick = onOpenWeChat) { Text("现在去微信") }
                    }
                }
            }
        }

        item {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("想让它自动选座？", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "全自动选座需要做一次性 Cookie 配置。配置后可以在这里直接看座位图、" +
                            "点座位、提交预约，不用每次挤微信。",
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Cookie 等同于你的登录状态，会加密保存在本机，不会上传到任何服务器。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onUnlockAdvanced) { Text("配置 Cookie，解锁高级档") }
                }
            }
        }

        item { CaptchaDisclaimer() }
    }
}

@Composable
private fun CaptchaDisclaimer() {
    Card {
        Column(Modifier.padding(16.dp)) {
            Text("关于图形验证码", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            // 用 onSurfaceVariant 而不是 outline：outline 是给描边/分隔线用的，
            // 当正文色在浅底卡片上对比度不够（实测截图上几乎看不清）。
            Text(
                "有些时段图书馆会要求图形验证码。遇到这种情况，本应用会提示你到微信里完成这一次预约，" +
                    "不会去识别验证码——那样容易被判定为异常行为，风险由你承担，不值得。",
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------- 高级档 ----------

@Composable
private fun AdvancedTierContent(
    state: SeatViewModel.UiState,
    onOpenLayout: (com.xynu.campus.library.model.SeatLibrary) -> Unit,
    onSelectSeat: (String) -> Unit,
    onReserve: () -> Unit,
    onBackToList: () -> Unit,
    onRefreshCurrent: () -> Unit,
    onRefreshLibraries: () -> Unit,
    onForget: () -> Unit,
) {
    when (state.stage) {
        SeatViewModel.Stage.LIBRARIES -> LibraryList(
            state = state,
            onOpenLayout = onOpenLayout,
            onRefresh = onRefreshLibraries,
            onForget = onForget,
        )

        SeatViewModel.Stage.LAYOUT -> SeatLayoutView(
            state = state,
            onSelectSeat = onSelectSeat,
            onReserve = onReserve,
            onBack = onBackToList,
        )

        SeatViewModel.Stage.CURRENT -> CurrentReservationView(
            state = state,
            onRefresh = onRefreshCurrent,
            onBack = onBackToList,
        )
    }
}

@Composable
private fun LibraryList(
    state: SeatViewModel.UiState,
    onOpenLayout: (com.xynu.campus.library.model.SeatLibrary) -> Unit,
    onRefresh: () -> Unit,
    onForget: () -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("图书馆列表", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onRefresh) { Text("刷新") }
            TextButton(onClick = onForget) { Text("清除 Cookie") }
        }

        if (state.libraries.isEmpty()) {
            Text(
                "没有取到图书馆列表。可能是 Cookie 已过期，或这时候还没开放。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.libraries, key = { it.libId }) { lib ->
                Card(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = lib.isOpen) { onOpenLayout(lib) },
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(lib.libName, fontWeight = FontWeight.SemiBold)
                            // isOpen 之前解析出来了却完全没用：已闭馆的馆在列表里和开放馆
                            // 长得一模一样，还能点进去，然后落到「座位图没有取到。」——
                            // 把"馆没开"表达成"我们取不到数据"。这里显式标注并禁点。
                            if (!lib.isOpen) {
                                Text(
                                    "未开放",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant,
                                            RoundedCornerShape(4.dp),
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("空闲 ${lib.seatsFree}", fontSize = 13.sp)
                            Text(
                                "总 ${lib.seatsTotal}",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (lib.libFloor.isNotBlank()) {
                                Text(
                                    lib.libFloor,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (lib.openTimeStr.isNotBlank()) {
                            Text(
                                "开放 ${lib.openTimeStr}-${lib.closeTimeStr}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
