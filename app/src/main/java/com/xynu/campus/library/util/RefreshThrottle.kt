package com.xynu.campus.library.util

/**
 * 上游请求节流器。
 *
 * 任务书两条约束的交汇点：
 * 1. 「明确切掉高频轮询」——不能因为用户狂点或 Compose 重组而刷爆上游
 * 2. 验收标准「刷新间隔 <3s 一律被钳制」
 *
 * 抽成独立类而不是塞进 ViewModel，是因为：
 * - ViewModel 依赖 Android（Application + EncryptedSharedPreferences），
 *   在 JVM 单测里跑不起来（Robolectric 无 Keystore provider）
 * - 这段逻辑是纯数学，没有理由被 Android 绑死
 *
 * 线程安全：`synchronized` 足够。竞争窗口只有"两个协程同时想刷新"，
 * 而这里是 UI 事件驱动，粒度粗，不需要 CAS 自旋。
 */
class RefreshThrottle(
    /** 两次放行之间的最小间隔，默认 3 秒（任务书硬要求）。 */
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    /** 可注入的时钟，便于确定性测试。 */
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * 上次放行时间。
     *
     * 用 [Long.MIN_VALUE] 而不是 0 作为哨兵，是因为 0 是一个合法的时钟读数
     * （测试里注入的时钟就从 0 开始）。用 0 会让"首次刷新"和"刚刷过"无法区分，
     * 表现为 `minIntervalMs` 较大时首次调用被误拦。
     */
    private var lastAcquiredAt = NEVER

    /**
     * 尝试获取一次刷新许可。
     *
     * @return true 表示可以发请求（并且已记下本次时间）；false 表示被节流
     */
    @Synchronized
    fun tryAcquire(): Boolean {
        val now = clock()
        if (lastAcquiredAt != NEVER && now - lastAcquiredAt < minIntervalMs) return false
        lastAcquiredAt = now
        return true
    }

    /** 距下次可用还需等待的毫秒数，0 表示现在就能刷（UI 可用于禁用按钮）。 */
    @Synchronized
    fun remainingMs(): Long {
        if (lastAcquiredAt == NEVER) return 0L
        val elapsed = clock() - lastAcquiredAt
        return (minIntervalMs - elapsed).coerceAtLeast(0L)
    }

    /** 重置，只给测试与"用户手动强制刷新"用。 */
    @Synchronized
    fun reset() {
        lastAcquiredAt = NEVER
    }

    companion object {
        /**
         * 任务书明确要求 ≥3s。
         *
         * 为什么是 3 而不是 1：上游 traceint 对异常频率有风控，
         * 而选座本身是"抢"的场景，本地多刷几次并不会提高成功率——
         * 只会有账号被限制的风险。
         */
        const val DEFAULT_MIN_INTERVAL_MS = 3000L

        /** "从未放行过"的哨兵值。 */
        private const val NEVER = Long.MIN_VALUE
    }
}
