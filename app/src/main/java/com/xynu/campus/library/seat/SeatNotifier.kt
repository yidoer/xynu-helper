package com.xynu.campus.library.seat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.xynu.campus.R

/**
 * 座位提醒通知 + 三级跳转。
 *
 * 任务书 §2 要求的三级跳转（微信打不开时逐级降级）：
 * 1. `weixin://` —— 直接起微信
 * 2. `weixin://dl/business/?t=<token>` 等 —— 微信内业务链接
 * 3. `https://` 网页兜底 —— 浏览器打开提示页
 *
 * 之所以要三级：Android 上微信的 scheme 在不同机型/版本上行为不一致，
 * 只有真正 try 才能知道哪一级能过，因此逐级降级而不是一次失败就报错。
 */
object SeatNotifier {

    const val CHANNEL_ID = "library_seat_reminder"
    const val NOTIFICATION_ID = 2001

    private const val REQ_WEIXIN = 1001
    private const val REQ_FALLBACK = 1002

    /** 三级跳转地址，按优先级排列。 */
    sealed interface JumpTarget {
        val uri: String
        val label: String
    }

    /** 一级：直接唤醒微信。 */
    data object WeixinApp : JumpTarget {
        override val uri = "weixin://"
        override val label = "打开微信"
    }

    /** 二级：微信内跳转到图书馆公众号的座位预约入口。 */
    data class WeixinBusiness(val token: String) : JumpTarget {
        override val uri get() = "weixin://dl/business/?t=$token"
        override val label = "打开微信选座页"
    }

    /** 三级：浏览器兜底提示页。 */
    data class WebFallback(val url: String) : JumpTarget {
        override val uri get() = url
        override val label = "在浏览器中打开"
    }

    /**
     * 三级跳转链：给定 from_code 拼出完整降级序列。
     *
     * from_code 由 hall 页动态提取（不硬编码），这里只负责拼参数。
     */
    fun jumpChain(fromCode: String?): List<JumpTarget> = buildList {
        add(WeixinApp)
        if (!fromCode.isNullOrBlank()) {
            add(WeixinBusiness(fromCode))
        }
        add(
            WebFallback(
                "https://wechat.v2.traceint.com/index.php/reserve/index.html" +
                    "?f=wechat" +
                    if (!fromCode.isNullOrBlank()) {
                        // from_code 是上游给的短码，但它是**外部输入**：
                        // 直接插值的话，一个含 `&`/`#` 的 from_code 就能往 URL 里注入额外参数。
                        // 来源是 IdentityStore.fromCode() 或上游解析，风险低但没理由不做。
                        "&from_code=" + Uri.encode(fromCode)
                    } else {
                        ""
                    },
            ),
        )
    }

    /** 建通知渠道（O+ 必需）。幂等。 */
    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "图书馆选座提醒",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "到点提醒你去抢座位。完全本地，不联网。"
            },
        )
    }

    /**
     * 弹提醒通知。
     *
     * 文案要点（任务书要求）：明说「全自动选座需要一次性 Cookie 配置」，
     * 不能暗示本应用已经能自动抢座。
     */
    fun showReminder(ctx: Context, fromCode: String? = null) {
        val chain = jumpChain(fromCode)
        val primary = chain.first()

        val contentIntent = buildPendingIntent(ctx, primary, REQ_WEIXIN)

        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("该去选座了")
            .setContentText("图书馆座位现在可以去抢了。点这里打开微信。")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "图书馆座位现在可以去抢了。点这里打开微信选座。\n\n" +
                        "提示：全自动选座需要先做一次 Cookie 配置（设置 → 选座高级档）。",
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        runCatching {
            // ensureChannel 也必须在 runCatching 里面。
            // 它以前在 try 之外：建渠道在个别 ROM/被 MDM 管控的设备上会抛
            // （SecurityException / IllegalArgumentException），
            // 而这个函数是从 Worker.doWork() 调的 —— 一抛就是 Worker 直接 fail，
            // WorkManager 会按退避策略反复重试，用户侧表现为"到点了但什么都没有"。
            ensureChannel(ctx)
            NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, notification)
        }.onFailure {
            // 缺 POST_NOTIFICATIONS 权限时 notify 会抛异常；不崩，只记日志
            com.xynu.campus.library.util.LibraryLog.w(
                "SeatNotifier",
                "通知发送失败（可能缺少通知权限）：${it.message}",
            )
        }
    }

    private fun buildPendingIntent(
        ctx: Context,
        target: JumpTarget,
        requestCode: Int,
    ): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target.uri)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            ctx,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 按三级顺序尝试打开，返回第一个成功的级别（null 表示全失败）。
     *
     * 调用方需传入自己的 ctx；每次失败都 fallback 到下一级，
     * 而不是弹"打开失败"了事。
     */
    fun openWithFallback(ctx: Context, chain: List<JumpTarget>): JumpTarget? {
        for (target in chain) {
            val ok = runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target.uri)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
                true
            }.getOrDefault(false)
            if (ok) return target
        }
        return null
    }
}
