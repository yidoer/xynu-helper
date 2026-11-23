package com.xynu.campus.library

import com.xynu.campus.library.util.RefreshThrottle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验收标准 11 的一条：刷新间隔 **<3s 一律被钳制**。
 *
 * 为什么认真测这个：
 * 上游 traceint 有风控，而选座是"抢"的场景，用户会本能地狂点刷新。
 * 如果本地不钳制，结果是"越急越容易被限制"，体验反而更差。
 */
class RefreshThrottleTest {

    @Test
    fun `first acquire always succeeds`() {
        val t = RefreshThrottle { 1_000_000L }
        assertTrue(t.tryAcquire())
    }

    @Test
    fun `default interval is three seconds`() {
        assertEquals(
            "任务书写死的 ≥3s，不能因为'想快一点'就调小",
            3000L,
            RefreshThrottle.DEFAULT_MIN_INTERVAL_MS,
        )
    }

    @Test
    fun `second acquire within interval is blocked`() {
        var now = 1_000_000L
        val t = RefreshThrottle(clock = { now })

        assertTrue(t.tryAcquire())
        // 1ms 后：必须拦住
        now += 1
        assertFalse("1ms 后刷新必须被钳制", t.tryAcquire())
        // 2999ms 后：仍然拦住
        now = 1_000_000L + 2999
        assertFalse("2999ms 后仍必须被钳制", t.tryAcquire())
        // 恰好 3000ms：放行
        now = 1_000_000L + 3000
        assertTrue("恰好 3s 应放行", t.tryAcquire())
    }

    @Test
    fun `remaining reports wait time and reaches zero`() {
        var now = 5_000_000L
        val t = RefreshThrottle(clock = { now })

        assertEquals("初始状态可直接刷新", 0L, t.remainingMs())
        assertTrue(t.tryAcquire())
        assertEquals(3000L, t.remainingMs())

        now += 1000
        assertEquals(2000L, t.remainingMs())

        now += 2500
        assertEquals("已经超过间隔，应归零而不是负数", 0L, t.remainingMs())
    }

    @Test
    fun `reset allows immediate refresh`() {
        var now = 1_000L
        val t = RefreshThrottle(clock = { now })
        assertTrue(t.tryAcquire())
        assertFalse(t.tryAcquire())

        t.reset()
        assertTrue("reset 后应立刻可刷（供强制刷新用）", t.tryAcquire())
    }

    @Test
    fun `rapid burst allows only one request`() {
        var now = 10_000L
        val t = RefreshThrottle(clock = { now })
        var allowed = 0
        // 模拟用户 100ms 内连点 30 次
        repeat(30) {
            if (t.tryAcquire()) allowed++
            now += 100
        }
        assertEquals(
            "3 秒窗口内最多放行 1 次，实际 $allowed 次",
            1,
            allowed,
        )
    }

    @Test
    fun `custom interval is respected`() {
        var now = 0L
        val t = RefreshThrottle(minIntervalMs = 10_000L, clock = { now })
        assertTrue(t.tryAcquire())
        now += 9_999
        assertFalse(t.tryAcquire())
        now += 1
        assertTrue(t.tryAcquire())
    }

    @Test
    fun `clock going backwards does not allow a flood`() {
        // 系统时间被回拨（用户改时间/时区切换）时，不能让节流失效
        var now = 1_000_000L
        val t = RefreshThrottle(clock = { now })
        assertTrue(t.tryAcquire())

        now = 500_000L // 时间回退
        // 负 elapsed 仍小于间隔，应继续拦住（fail-closed 方向）
        assertFalse("时钟回拨不应解锁刷新", t.tryAcquire())
    }
}
