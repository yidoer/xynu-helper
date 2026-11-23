package com.xynu.campus.library

import com.xynu.campus.library.crypto.OpenIdExtractor
import com.xynu.campus.library.model.SeatSession
import com.xynu.campus.library.store.IdentityStore
import com.xynu.campus.library.testutil.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M1 验收：F1 openid 提取 + F2 分区加密存储。
 *
 * 对齐任务书 §6 验收标准 10（加密存储回读一致、日志无完整凭据）。
 */
class IdentityStoreTest {

    private fun newStore(): Pair<IdentityStore, FakeSharedPreferences> {
        val prefs = FakeSharedPreferences()
        return IdentityStore.forTest(prefs) to prefs
    }

    // ---------- F1：openid 提取 ----------

    @Test
    fun `extracts openid from wechat copied link`() {
        val link = "http://mobilelib.wx.chaoxing.com/weixin/hall/showNew?pid=1512&type=8" +
            "&openid=oABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
        val result = OpenIdExtractor.extract(link)
        assertTrue("应提取成功，实际 $result", result is OpenIdExtractor.Result.Success)
        assertEquals(
            "oABCDEFGHIJKLMNOPQRSTUVWXYZ012345",
            (result as OpenIdExtractor.Result.Success).openid,
        )
    }

    @Test
    fun `openid regex requires o prefix and at least 20 chars`() {
        // 长度不足
        assertTrue(
            OpenIdExtractor.extract("http://x?openid=oABC") is OpenIdExtractor.Result.Failed,
        )
        // 不以 o 开头
        assertTrue(
            OpenIdExtractor.extract("http://x?openid=aABCDEFGHIJKLMNOPQRSTUVWXYZ") is OpenIdExtractor.Result.Failed,
        )
        // 下划线与短横线允许
        val ok = OpenIdExtractor.extract("http://x?openid=oAB_CD-EFghijklmnopqrstu")
        assertTrue(ok is OpenIdExtractor.Result.Success)
    }

    @Test
    fun `gives three distinct chinese failure reasons`() {
        val empty = OpenIdExtractor.extract("") as OpenIdExtractor.Result.Failed
        val notLink = OpenIdExtractor.extract("这是一段普通文字，不是链接") as OpenIdExtractor.Result.Failed
        val malformed = OpenIdExtractor.extract("http://x?openid=bad") as OpenIdExtractor.Result.Failed

        assertEquals(OpenIdExtractor.Failure.EMPTY_CLIPBOARD, empty.reason)
        assertEquals(OpenIdExtractor.Failure.NOT_LIBRARY_LINK, notLink.reason)
        assertEquals(OpenIdExtractor.Failure.MALFORMED_OPENID, malformed.reason)

        // 三种原因的中文文案必须互不相同，用户才能区分
        val messages = setOf(empty.reason.message, notLink.reason.message, malformed.reason.message)
        assertEquals("三种失败原因文案必须不同", 3, messages.size)
        messages.forEach { assertTrue("文案应为中文: $it", it.any { c -> c.code in 0x4E00..0x9FFF }) }
    }

    @Test
    fun `null clipboard is empty not crash`() {
        assertTrue(
            OpenIdExtractor.extract(null) is OpenIdExtractor.Result.Failed,
        )
    }

    @Test
    fun `logs never contain full openid`() {
        val full = "oABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
        val masked = OpenIdExtractor.mask(full)
        assertFalse("脱敏后不得包含完整 openid", masked.contains(full))
        assertTrue("必须形如 openid=oA***", masked.startsWith("openid=o"))
        assertTrue(masked.endsWith("***"))
    }

    // ---------- F2：借阅区 ----------

    @Test
    fun `borrow identity round trips`() {
        val (store, _) = newStore()
        assertFalse(store.hasBorrowIdentity())
        assertNull(store.borrowIdentity())

        store.saveBorrowIdentity("oABCDEFGHIJKLMNOPQRSTUVWXYZ012345", boundAt = 1234L)

        assertTrue(store.hasBorrowIdentity())
        val id = store.borrowIdentity()!!
        assertEquals("oABCDEFGHIJKLMNOPQRSTUVWXYZ012345", id.openid)
        assertEquals(1234L, id.boundAt)
    }

    @Test
    fun `clearing borrow identity does not touch seat area`() {
        val (store, _) = newStore()
        store.saveBorrowIdentity("oABCDEFGHIJKLMNOPQRSTUVWXYZ012345")
        store.saveSeatSession(sampleSession())

        store.clearBorrowIdentity()

        assertFalse(store.hasBorrowIdentity())
        assertTrue("清借阅区不得影响选座区", store.hasSeatSession())
    }

    // ---------- F2：选座区 ----------

    private fun sampleSession() = SeatSession(
        wechatSessId = "SESS_abcdefghijklmnop",
        authorization = "eyJhbGciOiJIUzI1NiJ9.payload.sig",
        fromCode = "FROMCODE123",
        version = "5.5",
    )

    @Test
    fun `seat session round trips all four fields`() {
        val (store, _) = newStore()
        assertFalse(store.hasSeatSession())

        store.saveSeatSession(sampleSession(), verifiedAt = 999L)

        val s = store.seatSession()!!
        assertEquals("SESS_abcdefghijklmnop", s.wechatSessId)
        assertEquals("eyJhbGciOiJIUzI1NiJ9.payload.sig", s.authorization)
        assertEquals("FROMCODE123", s.fromCode)
        assertEquals("5.5", s.version)
        assertEquals(999L, s.verifiedAt)
        assertTrue(s.isComplete)
    }

    @Test
    fun `clearing seat does not touch borrow area`() {
        val (store, _) = newStore()
        store.saveBorrowIdentity("oABCDEFGHIJKLMNOPQRSTUVWXYZ012345")
        store.saveSeatSession(sampleSession())

        store.clearSeatSession()

        assertFalse(store.hasSeatSession())
        assertTrue("清选座区不得影响借阅区", store.hasBorrowIdentity())
    }

    @Test
    fun `partial seat session is treated as absent`() {
        val (store, _) = newStore()
        // 只有 session id，缺 Authorization / FROM_CODE
        store.saveSeatSession(
            SeatSession(wechatSessId = "only", authorization = "", fromCode = ""),
        )
        assertNull("字段不全必须视为未配置，不能拿去发请求", store.seatSession())
    }

    // ---------- F5 迁移：快照 ----------

    @Test
    fun `snapshot and restore recover both identities`() {
        val (store, _) = newStore()
        store.saveBorrowIdentity("oABCDEFGHIJKLMNOPQRSTUVWXYZ012345", boundAt = 111L)
        store.saveSeatSession(sampleSession(), verifiedAt = 222L)

        val snap = store.snapshot()
        assertFalse(snap.isEmpty)

        // 清空后恢复
        store.clearAll()
        assertFalse(store.hasBorrowIdentity())
        assertFalse(store.hasSeatSession())

        store.restore(snap)

        assertTrue(store.hasBorrowIdentity())
        assertTrue(store.hasSeatSession())
        assertEquals("oABCDEFGHIJKLMNOPQRSTUVWXYZ012345", store.borrowIdentity()!!.openid)
        assertEquals(111L, store.borrowIdentity()!!.boundAt)
        assertEquals("SESS_abcdefghijklmnop", store.seatSession()!!.wechatSessId)
        assertEquals(222L, store.seatSession()!!.verifiedAt)
    }

    @Test
    fun `empty snapshot is flagged empty`() {
        val (store, _) = newStore()
        assertTrue(store.snapshot().isEmpty)
    }

    // ---------- Cookie 解析 ----------

    @Test
    fun `parses raw cookie into four fields`() {
        val raw = "wechatSESS_ID=ABC123; Authorization=eyJhbGci.payload.sig; " +
            "FROM_CODE=XYZ; v=5.5"
        val s = SeatSession.parseRawCookie(raw)!!
        assertEquals("ABC123", s.wechatSessId)
        assertEquals("eyJhbGci.payload.sig", s.authorization)
        assertEquals("XYZ", s.fromCode)
        assertEquals("5.5", s.version)
    }

    @Test
    fun `parses cookie copied with prefix and newlines`() {
        val raw = "Cookie: wechatSESS_ID=A\r\nAuthorization=B\r\nFROM_CODE=C\r\nv=5.5"
        val s = SeatSession.parseRawCookie(raw)!!
        assertEquals("A", s.wechatSessId)
        assertEquals("B", s.authorization)
        assertEquals("C", s.fromCode)
    }

    @Test
    fun `cookie without required fields returns null`() {
        assertNull(SeatSession.parseRawCookie(""))
        assertNull(SeatSession.parseRawCookie("wechatSESS_ID=A"))
        // 「缺 FROM_CODE」不再算不完整：FROM_CODE 不是 Cookie 字段，
        // 而是 traceint 入口 URL 的 query 参数，用户真实粘贴里常常没有。
        val noFromCode = SeatSession.parseRawCookie("wechatSESS_ID=A; Authorization=B; v=5.5")
        assertNotNull("缺 FROM_CODE 仍应被接受（它来自入口 URL，不是 Cookie 字段）", noFromCode)
        assertEquals("A", noFromCode!!.wechatSessId)
        assertEquals("B", noFromCode.authorization)
        assertEquals("5.5", noFromCode.version)
        assertEquals("", noFromCode.fromCode)
        assertTrue(noFromCode.isComplete)
    }

    @Test
    fun `cookie with only one of the two required fields is rejected`() {
        // 真正不能缺的是 wechatSESS_ID 与 Authorization 这两项
        assertNull(SeatSession.parseRawCookie("wechatSESS_ID=A; v=5.5"))
        assertNull(SeatSession.parseRawCookie("Authorization=B; v=5.5"))
        assertNull(SeatSession.parseRawCookie("v=5.5; FROM_CODE=FC"))
    }

    @Test
    fun `real user pasted cookie is accepted`() {
        // 真机用户实际粘贴的**形状**：一串浏览器追踪字段 + 两个必需字段 + FROM_CODE。
        // 所有取值都是占位符（等长），只有结构是真的 —— 结构才是这条测试要守住的东西。
        val raw = "FROM_TYPE=weixin; FROM_CODE=FROMCODE%3D; v=5.5; " +
            "Hm_lvt_0000000000000000000000=1795257956; HMACCOUNT=0000000000000000; " +
            "wechatSESS_ID=" + "a".repeat(37) + "; " +
            "Authorization=" + "b".repeat(36) + "; " +
            "SERVERID=" + "c".repeat(32) + "|1795272079|1795271489"
        val s = SeatSession.parseRawCookie(raw)
        assertNotNull("用户整段粘贴必须被接受", s)
        assertEquals("a".repeat(37), s!!.wechatSessId)
        assertEquals("b".repeat(36), s.authorization)
        assertEquals("FROMCODE%3D", s.fromCode)
        assertEquals("5.5", s.version)
        assertTrue(s.isComplete)
    }

    @Test
    fun `cookie header assembled in expected order`() {
        val header = sampleSession().toCookieHeader()
        assertTrue(header.contains("wechatSESS_ID=SESS_abcdefghijklmnop"))
        assertTrue(header.contains("Authorization=eyJ"))
        assertTrue(header.contains("FROM_CODE=FROMCODE123"))
        assertTrue(header.contains("v=5.5"))
    }

    @Test
    fun `session log representation masks secrets`() {
        val masked = sampleSession().maskedForLog()
        assertFalse("不得出现完整 session", masked.contains("SESS_abcdefghijklmnop"))
        assertFalse("不得出现完整 jwt", masked.contains("eyJhbGciOiJIUzI1NiJ9.payload.sig"))
        assertTrue(masked.contains("***"))
    }

    // ---------- from_code：独立持久化 ----------
    //
    // from_code 不是 Cookie 字段，而是 traceint 入口 URL 的 query 参数。
    // 早上 7:50 的提醒由 WorkManager 里的 SeatReminderWorker 发，
    // 那个进程里没有 ViewModel，只能从存储读——所以必须单独落盘。

    @Test
    fun `from code round trips on its own`() {
        val (store, _) = newStore()
        assertEquals("未存过时必须是空串，不能是 null 让调用方去判空", "", store.fromCode())

        store.saveFromCode("FROMCODE123")

        assertEquals("FROMCODE123", store.fromCode())
    }

    @Test
    fun `from code survives without any seat session`() {
        // 用户可能先拿到 from_code 但还没填 Cookie。
        // 这时 from_code 仍要存住，等填完 Cookie 就能直接用。
        val (store, _) = newStore()
        store.saveFromCode("ONLY_FROM_CODE")

        assertFalse("没有 Cookie 不算已配置选座", store.hasSeatSession())
        assertEquals("ONLY_FROM_CODE", store.fromCode())
    }

    @Test
    fun `blank from code is ignored rather than overwriting`() {
        val (store, _) = newStore()
        store.saveFromCode("KEEP_ME")

        // 解析失败时调用方会传空串进来，不能把已有的值擦掉
        store.saveFromCode("")
        assertEquals("KEEP_ME", store.fromCode())

        store.saveFromCode("   ")
        assertEquals("KEEP_ME", store.fromCode())
    }

    @Test
    fun `clearAll wipes from code too`() {
        // 「身份迁移 / 一键清除」必须清干净，否则换手机后残留的 from_code
        // 会被拼进深链，指向别人的入口。
        val (store, _) = newStore()
        store.saveFromCode("SHOULD_BE_GONE")
        store.clearAll()

        assertEquals("", store.fromCode())
    }
}
