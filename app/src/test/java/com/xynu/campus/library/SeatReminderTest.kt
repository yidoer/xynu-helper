package com.xynu.campus.library

import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.xynu.campus.library.seat.SeatNotifier
import com.xynu.campus.library.seat.SeatReminderScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * M3 验收：F4 基础档「零凭据、零网络」。
 *
 * 关键断言（任务书 §6.10）：基础档不得产生任何网络请求。
 * 这里从**机制层面**验证：入队的工作请求没有任何网络约束、
 * Worker 类不持有任何网络依赖、通知文案明确说明高级档才需要 Cookie。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SeatReminderTest {

    private lateinit var ctx: android.content.Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx, config)
    }

    @Test
    fun `scheduling creates work request without network constraint`() {
        SeatReminderScheduler(ctx).scheduleDaily(hour = 7, minute = 50)

        val wm = WorkManager.getInstance(ctx)
        val infos: List<WorkInfo> = wm.getWorkInfosForUniqueWork(SeatReminderScheduler.WORK_NAME)
            .get()
        assertEquals("应恰好入队 1 个唯一周期任务", 1, infos.size)

        val constraints = infos.first().constraints
        assertEquals(
            "基础档不得要求网络（NOT_REQUIRED 才是零网络）",
            NetworkType.NOT_REQUIRED,
            constraints.requiredNetworkType,
        )
        assertFalse(
            "不得要求充电等额外约束（会推迟提醒）",
            constraints.requiresCharging(),
        )
    }

    @Test
    fun `scheduling is idempotent via unique work update`() {
        val scheduler = SeatReminderScheduler(ctx)
        scheduler.scheduleDaily(7, 50)
        scheduler.scheduleDaily(8, 10)

        val infos = WorkManager.getInstance(ctx)
            .getWorkInfosForUniqueWork(SeatReminderScheduler.WORK_NAME).get()
        assertEquals("重复安排不应产生第二个任务", 1, infos.size)
    }

    @Test
    fun `cancel removes the reminder`() {
        val scheduler = SeatReminderScheduler(ctx)
        scheduler.scheduleDaily(7, 50)
        scheduler.cancel()

        val infos = WorkManager.getInstance(ctx)
            .getWorkInfosForUniqueWork(SeatReminderScheduler.WORK_NAME).get()
        assertTrue(
            "取消后任务应处于 CANCELLED",
            infos.isEmpty() || infos.all { it.state == WorkInfo.State.CANCELLED },
        )
    }

    @Test
    fun `worker declares no network capability`() {
        // 反射检查 Worker 类不持有任何 okhttp / repository 字段，
        // 从依赖面证明基础档没有联网能力
        val fields = com.xynu.campus.library.seat.SeatReminderWorker::class.java.declaredFields
        val suspicious = fields.filter {
            val n = it.type.name
            n.contains("okhttp", ignoreCase = true) ||
                n.contains("Repository", ignoreCase = true) ||
                n.contains("GraphQl", ignoreCase = true) ||
                n.contains("Client", ignoreCase = true)
        }
        assertTrue("Worker 不应持有网络依赖，实际发现: $suspicious", suspicious.isEmpty())
    }

    // ---------- 三级跳转 ----------

    @Test
    fun `jump chain has three levels in order`() {
        val chain = SeatNotifier.jumpChain("FROMCODE123")
        assertEquals("必须有三级兜底", 3, chain.size)

        assertEquals("一级必须是 weixin://", "weixin://", chain[0].uri)
        assertTrue("二级必须是微信业务链接", chain[1].uri.startsWith("weixin://dl/business/"))
        assertTrue("三级必须是网页兜底", chain[2].uri.startsWith("https://"))
    }

    @Test
    fun `jump chain degrades to two levels without from_code`() {
        val chain = SeatNotifier.jumpChain(null)
        assertEquals("无 from_code 时退化为两级", 2, chain.size)
        assertEquals("weixin://", chain[0].uri)
        assertTrue(chain[1].uri.startsWith("https://"))
        assertFalse(
            "无 from_code 不得拼出空参链接",
            chain[1].uri.contains("from_code="),
        )
    }

    @Test
    fun `web fallback points at traceint reserve entry`() {
        val chain = SeatNotifier.jumpChain("ABC")
        val web = chain.last()
        assertTrue(web.uri.contains("wechat.v2.traceint.com"))
        assertTrue(web.uri.contains("reserve/index.html"))
        assertTrue(web.uri.contains("from_code=ABC"))
    }

    @Test
    fun `channel is created with reminder importance`() {
        SeatNotifier.ensureChannel(ctx)
        val mgr = ctx.getSystemService(android.app.NotificationManager::class.java)
        val channel = mgr.getNotificationChannel(SeatNotifier.CHANNEL_ID)
        assertNotNull("必须创建通知渠道", channel)
        assertEquals("图书馆选座提醒", channel.name.toString())
    }

    /**
     * 回归：**这条原来是个假测试**。
     *
     * 原版只有一句 `SeatNotifier.showReminder(ctx, fromCode = null)`，
     * 注释写"要求不崩"。问题是 `showReminder` 内部整个 `notify` 包在
     * `runCatching { }` 里，异常会被吞掉并记一条 WARN 日志——
     * 也就是说**即使通知一条都没发出去，这个测试也会绿**。
     *
     * 它偏偏还是「缺 POST_NOTIFICATIONS 权限时不崩」这条唯一的行为测试，
     * 于是"通知完全坏掉"和"通知正常"在测试层面长得一模一样。
     *
     * 改成断言真正发生了通知投递：Robolectric 的 `ShadowNotificationManager`
     * 能拿到已发出的通知列表。发不出来就是失败，而不是静默通过。
     */
    @Test
    fun `reminder is actually posted to the notification manager`() {
        val mgr = ctx.getSystemService(android.app.NotificationManager::class.java)

        SeatNotifier.showReminder(ctx, fromCode = null)

        val posted = shadowOf(mgr).allNotifications
        assertEquals(
            "showReminder 必须真的投递一条通知；" +
                "如果这里是 0，说明 notify 抛了异常被 runCatching 吞了，" +
                "而旧版测试对此完全无感",
            1,
            posted.size,
        )

        val n = posted.first()
        assertNotNull("通知必须带 PendingIntent，否则点不动", n.contentIntent)
        assertEquals("该去选座了", n.extras.getString(android.app.Notification.EXTRA_TITLE))
    }

    /**
     * 文案红线（任务书 §6.10）：不能暗示"本应用能全自动抢座"。
     *
     * 这条也是原版没有的：`bigText` 里那句"全自动选座需要先做一次 Cookie 配置"
     * 是验收明确要求的内容，之前只存在于实现里，没有任何测试盯着它。
     * 有人顺手把这句话删掉或改写，测试不会响。
     */
    @Test
    fun `reminder text does not imply automatic seat grabbing`() {
        val mgr = ctx.getSystemService(android.app.NotificationManager::class.java)

        SeatNotifier.showReminder(ctx, fromCode = null)

        val n = shadowOf(mgr).allNotifications.first()
        val bigText = n.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        assertTrue("缺少 bigText 文案", bigText.isNotBlank())

        assertTrue(
            "文案必须明确说明「全自动选座需要一次性 Cookie 配置」，" +
                "否则用户会以为装上就能自动抢座。实际文案：$bigText",
            bigText.contains("Cookie") && bigText.contains("全自动选座"),
        )
        assertFalse(
            "不得出现「自动帮你抢」「已为你预约成功」这类暗示已经代抢的措辞。实际文案：$bigText",
            bigText.contains("已为你") || bigText.contains("帮你抢"),
        )
    }
}
