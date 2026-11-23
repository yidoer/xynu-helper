package com.xynu.campus.library.seat

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.xynu.campus.library.util.LibraryLog
import java.util.concurrent.TimeUnit

/**
 * F4 基础档：本地提醒（**零凭据、零网络**）。
 *
 * 设计要点（任务书 F4 基础档）：
 * - 用户只配置「提醒窗口」（例如每天 07:50）
 * - 到点发一条本地通知「该去选座了」，点通知跳微信
 * - 全程不发任何网络请求，不需要 Cookie，不需要 openid
 *
 * 因此这里的 WorkRequest **不设** `NetworkType` 约束——连"需要网络"都不声明，
 * 从机制上保证基础档不会因为联网而泄露任何东西。
 */
class SeatReminderScheduler(private val ctx: Context) {

    /**
     * 安排每日提醒。
     *
     * @param hour 24 小时制小时
     * @param minute 分钟
     */
    fun scheduleDaily(hour: Int, minute: Int) {
        val now = java.util.Calendar.getInstance()
        val target = (now.clone() as java.util.Calendar).apply {
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            // 今天已过点则顺延到明天，避免立即触发
            if (before(now)) add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        val delay = target.timeInMillis - now.timeInMillis

        val request = PeriodicWorkRequestBuilder<SeatReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            // 明确不给网络约束：基础档零网络
            .setConstraints(Constraints.Builder().build())
            .addTag(TAG)
            .build()

        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
        LibraryLog.i(TAG_LOG, "每日提醒已安排 %02d:%02d（零网络）".format(hour, minute))
    }

    fun cancel() {
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
        LibraryLog.i(TAG_LOG, "每日提醒已取消")
    }

    companion object {
        const val WORK_NAME = "library_seat_reminder"
        const val TAG = "library_seat"
        private const val TAG_LOG = "SeatReminder"
    }
}

/**
 * 提醒 Worker：只负责弹通知，不碰网络。
 *
 * 注意这里**故意不注入**任何 repository/client——依赖面上就没有网络能力。
 */
class SeatReminderWorker(
    ctx: Context,
    params: WorkerParameters,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        // from_code 从加密存储读回：Worker 里没有 ViewModel，而通知上的
        // 「打开选座页」按钮需要它才能落到本校页面。读不到就给 null，
        // SeatNotifier 会自动降级成通用跳转。
        val fromCode = runCatching {
            com.xynu.campus.library.store.IdentityStore.get(applicationContext).fromCode()
        }.getOrNull()?.ifBlank { null }
        SeatNotifier.showReminder(applicationContext, fromCode)
        return Result.success()
    }
}
