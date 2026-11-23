package com.xynu.campus.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xynu.campus.library.model.SeatSession
import com.xynu.campus.library.store.IdentityStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * M1 验收（真实加密，仪器测试）：EncryptedSharedPreferences 落盘不含明文。
 *
 * 为什么必须放在 androidTest：
 * `EncryptedSharedPreferences` 依赖 **AndroidKeyStore**（由 TEE/StrongBox 支撑）。
 * Robolectric 没有 Keystore provider 实现，`MasterKey` 创建时会抛
 * `KeyStoreException: ... NoSuchAlgorithmException`，因此纯 JVM 侧
 * 无法证明"落盘加密"，只能退化为「读写一致」的逻辑测试。
 *
 * 本测试直接读 `shared_prefs/library_identity_enc.xml` 的**原始字节**，
 * 断言 openid / wechatSESS_ID / JWT 三个凭据的明文都不出现——
 * 这是任务书红线 2 与验收标准 10 的直接证据。
 *
 * 运行：`.\gradlew.bat :app:connectedDebugAndroidTest`
 */
@RunWith(AndroidJUnit4::class)
class EncryptedIdentityStoreTest {

    private val fullOpenId = "oABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
    private val fullSess = "SESS_SECRET_NEEDLE_1234567890"
    private val fullAuth = "eyJhbGciOiJIUzI1NiJ9.NEEDLE_PAYLOAD.SIGNATURE"

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun store(): IdentityStore = IdentityStore.get(ctx)

    @Before
    fun cleanUp() {
        store().clearAll()
    }

    @Test
    fun encryptedStoreRoundTripsIdentities() {
        val s = store()
        s.saveBorrowIdentity(fullOpenId, boundAt = 111L)
        s.saveSeatSession(
            SeatSession(wechatSessId = fullSess, authorization = fullAuth, fromCode = "FROMCODE"),
            verifiedAt = 222L,
        )

        // 新建实例重新读取：证明数据真的落盘了，不是内存态
        val s2 = store()
        assertEquals(fullOpenId, s2.borrowIdentity()!!.openid)
        assertEquals(111L, s2.borrowIdentity()!!.boundAt)
        assertEquals(fullSess, s2.seatSession()!!.wechatSessId)
        assertEquals(fullAuth, s2.seatSession()!!.authorization)
        assertEquals(222L, s2.seatSession()!!.verifiedAt)
    }

    @Test
    fun openidAndCookieNeverAppearInPlaintextOnDisk() {
        val s = store()
        s.saveBorrowIdentity(fullOpenId)
        s.saveSeatSession(
            SeatSession(wechatSessId = fullSess, authorization = fullAuth, fromCode = "FROMCODE"),
        )

        // 触发一次同步落盘（apply 是异步的）
        s.markSeatVerified()

        val prefsDir = File(ctx.applicationInfo.dataDir, "shared_prefs")
        val target = File(prefsDir, "library_identity_enc.xml")
        assertTrue("加密存储文件必须存在: ${target.absolutePath}", target.exists())

        val raw = target.readText()

        // 红线：三个凭据的明文都不能出现在文件里
        assertFalse("落盘不得含明文 openid", raw.contains(fullOpenId))
        assertFalse("落盘不得含明文 wechatSESS_ID", raw.contains(fullSess))
        assertFalse("落盘不得含明文 JWT", raw.contains(fullAuth))
        assertFalse("落盘不得含 JWT 片段", raw.contains("NEEDLE_PAYLOAD"))

        // 但文件必须有实际内容（否则是"没写"而非"加密"）
        assertTrue("文件不应为空，实际 ${raw.length} 字节", raw.length > 50)
    }

    @Test
    fun twoStoreInstancesShareSameEncryptedFile() {
        store().saveBorrowIdentity(fullOpenId)
        val b = store()
        assertNotNull(b.borrowIdentity())
        assertEquals(fullOpenId, b.borrowIdentity()!!.openid)
    }

    @Test
    fun clearAllWipesBothPartitions() {
        val s = store()
        s.saveBorrowIdentity(fullOpenId)
        s.saveSeatSession(SeatSession(fullSess, fullAuth, "F"))
        s.clearAll()

        val fresh = store()
        assertFalse(fresh.hasBorrowIdentity())
        assertFalse(fresh.hasSeatSession())
    }

    @Test
    fun snapshotRestoreSurvivesClearAll() {
        // F5 迁移的加密侧前提：快照是内存对象，clearAll 后能用它恢复
        val s = store()
        s.saveBorrowIdentity(fullOpenId, boundAt = 5L)
        s.saveSeatSession(SeatSession(fullSess, fullAuth, "FROMCODE"), verifiedAt = 9L)

        val snap = s.snapshot()
        s.clearAll()
        assertFalse(s.hasBorrowIdentity())

        s.restore(snap)
        assertEquals(fullOpenId, store().borrowIdentity()!!.openid)
        assertEquals(5L, store().borrowIdentity()!!.boundAt)
        assertEquals(fullSess, store().seatSession()!!.wechatSessId)
        assertEquals(9L, store().seatSession()!!.verifiedAt)
    }
}
