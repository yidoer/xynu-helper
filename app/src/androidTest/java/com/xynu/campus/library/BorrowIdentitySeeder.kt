package com.xynu.campus.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xynu.campus.library.model.SeatSession
import com.xynu.campus.library.store.IdentityStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机 E2E 用的「身份播种器」——不是一个断言型测试，是一个受控的开发工具。
 *
 * 为什么需要它：Android 10+ 限制后台读剪贴板，Android 15 更是**无法**用 `adb shell cmd
 * clipboard` 写入剪贴板，所以「微信里复制链接 → 点我复制好了」这条主路径在真机上
 * 无法自动跑通。为了验证「借阅明细、馆藏检索在真实凭据下真的能渲染」，
 * 需要绕过剪贴板这一步，直接把 openid 写进加密存储。
 *
 * **凭据绝不写进源码**：openid 通过 instrumentation 参数传入，例如
 *
 * ```
 * adb shell am instrument -w \
 *   -e class com.xynu.campus.library.BorrowIdentitySeeder \
 *   -e libraryOpenId <openid> \
 *   com.xynu.campus.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * 不带参数时本测试自动 skip（`assumeTrue`），所以常规 `connectedAndroidTest`
 * 不会被它影响，也不会因为缺少凭据而红。
 */
@RunWith(AndroidJUnit4::class)
class BorrowIdentitySeeder {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun seedBorrowIdentityFromInstrumentationArgs() {
        val args = InstrumentationRegistry.getArguments()
        val openid = args.getString("libraryOpenId")

        assumeTrue("未传 -e libraryOpenId，跳过播种", !openid.isNullOrBlank())

        val store = IdentityStore.get(ctx)
        store.saveBorrowIdentity(openid!!)

        // 写进去要能读回来，否则后面的 E2E 看到的是「没有身份」，
        // 会让人误以为是 UI bug。
        val back = store.borrowIdentity()
        assertEquals("播种后必须能读回同一个 openid", openid, back?.openid)
        assertTrue("播种后 hasBorrowIdentity 应为 true", store.hasBorrowIdentity())

        // EncryptedSharedPreferences 用的是 apply()（异步落盘）。instrumentation
        // 跑完会直接杀掉进程，异步写队列来不及 flush，磁盘上就只剩 keyset、
        // 没有 openid——表现为「播种显示 OK，但 App 里还是没身份」。
        // 这里显式等一下，让 apply() 的写队列落盘。
        Thread.sleep(1200)
    }

    @Test
    fun clearBorrowIdentityFromInstrumentationArgs() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("未传 -e libraryClear=1，跳过清理", args.getString("libraryClear") == "1")

        IdentityStore.get(ctx).clearBorrowIdentity()
        assertTrue(!IdentityStore.get(ctx).hasBorrowIdentity())
    }

    /**
     * 播种选座会话（用于复现「配过 Cookie 之后冷启动闪退」）。
     *
     * 用法：
     * ```
     * adb shell am instrument -w \
     *   -e class com.xynu.campus.library.BorrowIdentitySeeder#seedSeatSessionFromInstrumentationArgs \
     *   -e librarySeatCookie "wechatSESS_ID=...; Authorization=...; FROM_CODE=..." \
     *   com.xynu.campus.test/androidx.test.runner.AndroidJUnitRunner
     * ```
     *
     * 这条路径的意义：选座会话是**进入高级档的唯一门槛**。高级档才能在
     * `SeatViewModel.init` 里一路走到 `loadLibraries()`，也就才会碰到那个
     * 「属性声明在 init 之后 → 读到 null → NPE」的冷启动崩溃。
     * 没有它就没法在真机上把那个 bug 复现出来，只能靠读代码推断。
     */
    @Test
    fun seedSeatSessionFromInstrumentationArgs() {
        val args = InstrumentationRegistry.getArguments()
        val raw = args.getString("librarySeatCookie")

        assumeTrue("未传 -e librarySeatCookie，跳过播种", !raw.isNullOrBlank())

        val parsed = SeatSession.parseRawCookie(raw!!)
        assertTrue(
            "Cookie 解析失败（至少要能拿到 wechatSESS_ID 和 Authorization）。" +
                "原始长度=${raw.length}",
            parsed != null,
        )

        val store = IdentityStore.get(ctx)
        store.saveSeatSession(parsed!!)
        store.markSeatVerified()

        val back = store.seatSession()
        assertEquals("播种后必须能读回同一个 sess", parsed.wechatSessId, back?.wechatSessId)
        assertEquals("播种后必须能读回同一个 auth", parsed.authorization, back?.authorization)
        assertTrue("播种后 isComplete 必须为 true（否则进不了高级档）", back!!.isComplete)

        // 同 borrow：EncryptedSharedPreferences 是 apply()，要等落盘
        Thread.sleep(1200)
    }

    /** 清掉选座会话，让 App 退回基础档（验证不再闪退后要能清干净）。 */
    @Test
    fun clearSeatSessionFromInstrumentationArgs() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "未传 -e librarySeatClear=1，跳过清理",
            args.getString("librarySeatClear") == "1",
        )

        val store = IdentityStore.get(ctx)
        store.clearSeatSession()
        assertTrue("清理后不该再有选座会话", store.seatSession() == null)
    }
}
