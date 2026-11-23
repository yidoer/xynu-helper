package com.xynu.campus.library

import com.xynu.campus.library.crypto.OpenIdExtractor
import com.xynu.campus.library.model.SeatSession
import com.xynu.campus.library.store.IdentityStore
import com.xynu.campus.library.testutil.FakeSharedPreferences
import com.xynu.campus.library.util.LibraryLog
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 验收标准 10 的日志红线部分：任何日志不得出现完整凭据。
 *
 * 做法：把 [LibraryLog] 的 sink 换成内存收集器，跑完全部写入路径后
 * 逐条断言日志里既没有完整 openid、也没有完整 cookie / JWT。
 */
class LibraryLogRedactionTest {

    private val captured = mutableListOf<LibraryLog.Entry>()
    private val originalSink = LibraryLog.sink

    private val fullOpenId = "oABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
    private val fullSess = "SESS_SECRET_VALUE_1234567890"
    private val fullAuth = "eyJhbGciOiJIUzI1NiJ9.SECRETPAYLOAD.SIGNATURE"

    @Before
    fun installCapturingSink() {
        captured.clear()
        LibraryLog.sink = { captured.add(it) }
    }

    @After
    fun restoreSink() {
        LibraryLog.sink = originalSink
    }

    @Test
    fun `saving identities never logs full credentials`() {
        val store = IdentityStore.forTest(FakeSharedPreferences())

        store.saveBorrowIdentity(fullOpenId)
        store.saveSeatSession(
            SeatSession(
                wechatSessId = fullSess,
                authorization = fullAuth,
                fromCode = "FROMCODE",
            ),
        )

        assertTrue("应当产生了日志（否则这个测试没意义）", captured.isNotEmpty())

        captured.forEach { entry ->
            val m = entry.message
            assertFalse("日志不得含完整 openid: $m", m.contains(fullOpenId))
            assertFalse("日志不得含完整 wechatSESS_ID: $m", m.contains(fullSess))
            assertFalse("日志不得含完整 JWT: $m", m.contains(fullAuth))
        }
    }

    @Test
    fun `openid log is masked in ox triple star form`() {
        val store = IdentityStore.forTest(FakeSharedPreferences())
        store.saveBorrowIdentity(fullOpenId)

        val line = captured.first { it.message.contains("saveBorrowIdentity") }.message
        assertTrue("必须是 openid=oX*** 形式，实际: $line", line.contains("openid=oA***"))
    }

    @Test
    fun `seat log uses cookie triple star`() {
        val store = IdentityStore.forTest(FakeSharedPreferences())
        store.saveSeatSession(
            SeatSession(fullSess, fullAuth, "FROMCODE"),
        )

        val line = captured.first { it.message.contains("saveSeatSession") }.message
        assertTrue("必须含 cookie=***，实际: $line", line.contains("cookie=***"))
    }

    @Test
    fun `mask helper is safe for short and null input`() {
        assertFalse(OpenIdExtractor.mask(null).contains("null"))
        assertFalse(OpenIdExtractor.mask("").contains("=".repeat(2)))
        // 极短输入不能被原样输出
        assertFalse(OpenIdExtractor.mask("oA").contains("oA"))
    }
}
