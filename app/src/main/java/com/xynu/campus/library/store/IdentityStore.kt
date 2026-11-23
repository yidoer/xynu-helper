package com.xynu.campus.library.store

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.xynu.campus.library.crypto.OpenIdExtractor
import com.xynu.campus.library.util.LibraryLog
import com.xynu.campus.library.model.SeatSession

/**
 * 两套身份的**单一入口**（任务书 F2）。
 *
 * 分区：
 * - 借阅区：openid、绑定时间
 * - 选座区：wechatSESS_ID / Authorization / FROM_CODE / v、验活时间
 *
 * 存储：`EncryptedSharedPreferences`（Keystore-backed，AES256-GCM 值加密 +
 * AES256-SIV key 加密）。**绝不写明文到磁盘**。
 *
 * 红线：这些字段等同账号本体，永不上传、永不进日志。
 */
class IdentityStore private constructor(
    private val prefs: SharedPreferences,
) {

    // ---------- 借阅区 ----------

    data class BorrowIdentity(val openid: String, val boundAt: Long)

    fun borrowIdentity(): BorrowIdentity? {
        val openid = prefs.getString(K_OPENID, null) ?: return null
        if (openid.isBlank()) return null
        return BorrowIdentity(openid, prefs.getLong(K_OPENID_BOUND_AT, 0L))
    }

    fun hasBorrowIdentity(): Boolean = borrowIdentity() != null

    fun saveBorrowIdentity(openid: String, boundAt: Long = System.currentTimeMillis()) {
        require(openid.isNotBlank()) { "openid 不能为空" }
        // 凭据写入用 commit() 而不是 apply()。
        //
        // apply() 只同步写内存，磁盘落盘是异步的：用户粘完 Cookie/链接立刻切后台
        // 或进程被系统回收，就存在一个"界面说保存成功、重启后不见了"的窗口，
        // 而且极难复现与归因。凭据是低频写入（一次配置用很久），
        // commit() 的那点同步开销完全可以接受。
        val ok = prefs.edit()
            .putString(K_OPENID, openid)
            .putLong(K_OPENID_BOUND_AT, boundAt)
            .commit()
        if (!ok) LibraryLog.w(TAG, "saveBorrowIdentity commit failed")
        LibraryLog.i(TAG, "saveBorrowIdentity ${OpenIdExtractor.mask(openid)}")
    }

    fun clearBorrowIdentity() {
        prefs.edit().remove(K_OPENID).remove(K_OPENID_BOUND_AT).commit()
    }

    // ---------- 选座区 ----------

    fun seatSession(): SeatSession? {
        val sess = prefs.getString(K_SEAT_SESS, null) ?: return null
        val auth = prefs.getString(K_SEAT_AUTH, null) ?: return null
        // from_code 是可选的（入口 URL 参数，不是 Cookie 字段），
        // 只要求会话与 JWT 两项，否则旧数据/无 from_code 的正常会话会被判无效。
        if (sess.isBlank() || auth.isBlank()) return null
        return SeatSession(
            wechatSessId = sess,
            authorization = auth,
            fromCode = prefs.getString(K_SEAT_FROM, null).orEmpty(),
            version = prefs.getString(K_SEAT_VER, null) ?: SeatSession.DEFAULT_VERSION,
            verifiedAt = prefs.getLong(K_SEAT_VERIFIED_AT, 0L),
        )
    }

    fun hasSeatSession(): Boolean = seatSession() != null

    fun saveSeatSession(session: SeatSession, verifiedAt: Long = System.currentTimeMillis()) {
        // 同 saveBorrowIdentity：凭据落盘必须 commit()。
        val ok = prefs.edit()
            .putString(K_SEAT_SESS, session.wechatSessId)
            .putString(K_SEAT_AUTH, session.authorization)
            .putString(K_SEAT_FROM, session.fromCode)
            .putString(K_SEAT_VER, session.version)
            .putLong(K_SEAT_VERIFIED_AT, verifiedAt)
            .commit()
        if (!ok) LibraryLog.w(TAG, "saveSeatSession commit failed")
        // 红线：cookie 只打 ***，不打任何字段内容（from_code 也属凭据，同样不能打）
        LibraryLog.i(TAG, "saveSeatSession cookie=*** v=${session.version}")
    }

    fun clearSeatSession() {
        prefs.edit()
            .remove(K_SEAT_SESS).remove(K_SEAT_AUTH).remove(K_SEAT_FROM)
            .remove(K_SEAT_VER).remove(K_SEAT_VERIFIED_AT)
            .commit()
    }

    /** 标记一次验活成功（40001 时由上层清空）。 */
    fun markSeatVerified(verifiedAt: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(K_SEAT_VERIFIED_AT, verifiedAt).commit()
    }

    /**
     * 单独读/写 from_code。
     *
     * 为什么要拆出来：from_code 只在**基础档**（还没配 Cookie）就已经能取到，
     * 而它还必须活到「明天早上 7:50 的通知」那一刻——定时任务跑在
     * BroadcastReceiver 里，拿不到 ViewModel 的状态，只能从落盘处读回来。
     * 复用同一个键，所以配上 Cookie 后两者自然一致；反过来单独写这个键
     * 不会让 [seatSession] 变有效（那还需要 session + authorization 两项）。
     */
    fun fromCode(): String = prefs.getString(K_SEAT_FROM, null).orEmpty()

    fun saveFromCode(code: String) {
        if (code.isBlank()) return
        prefs.edit().putString(K_SEAT_FROM, code).commit()
        // 同样属凭据性参数，只打长度
        LibraryLog.i(TAG, "saveFromCode len=${code.length}")
    }

    // ---------- 迁移（F5 用）----------

    /** 导出全部身份的明文结构，仅交给加密导出层使用。 */
    data class Snapshot(
        val openid: String?,
        val openidBoundAt: Long,
        val seat: SeatSession?,
    ) {
        val isEmpty: Boolean get() = openid == null && seat == null
    }

    fun snapshot(): Snapshot = Snapshot(
        openid = borrowIdentity()?.openid,
        openidBoundAt = borrowIdentity()?.boundAt ?: 0L,
        seat = seatSession(),
    )

    /**
     * 用快照覆盖全部身份。
     *
     * 必须在**单个** `edit()` 事务里完成 clear + write。
     * 原来的写法是 `clearAll()` 然后逐个 `saveXxx()`，两步之间进程死亡
     * 就会留下"凭据全空"的中间态 —— 而 SharedPreferences 的
     * `Editor.clear()` 在同一个事务里是一次原子提交，没有这个窗口。
     *
     * 目前只有测试在用（生产走迁移导入），但一旦将来用于"导入失败回滚"，
     * 非原子就会造成不可逆的空状态。
     */
    fun restore(snapshot: Snapshot) {
        val e = prefs.edit().clear()
        snapshot.openid?.let {
            e.putString(K_OPENID, it)
            e.putLong(K_OPENID_BOUND_AT, snapshot.openidBoundAt)
        }
        snapshot.seat?.let { seat ->
            e.putString(K_SEAT_SESS, seat.wechatSessId)
            e.putString(K_SEAT_AUTH, seat.authorization)
            e.putString(K_SEAT_FROM, seat.fromCode)
            e.putString(K_SEAT_VER, seat.version)
            e.putLong(K_SEAT_VERIFIED_AT, seat.verifiedAt)
        }
        if (!e.commit()) LibraryLog.w(TAG, "restore commit failed")
    }

    fun clearAll() {
        // 保留非身份键（若有）只删身份键，避免误删配置
        prefs.edit()
            .remove(K_OPENID).remove(K_OPENID_BOUND_AT)
            .remove(K_SEAT_SESS).remove(K_SEAT_AUTH).remove(K_SEAT_FROM)
            .remove(K_SEAT_VER).remove(K_SEAT_VERIFIED_AT)
            .apply()
    }

    companion object {
        private const val TAG = "IdentityStore"

        /** 借阅区键 */
        private const val K_OPENID = "borrow_openid"
        private const val K_OPENID_BOUND_AT = "borrow_bound_at"

        /** 选座区键 */
        private const val K_SEAT_SESS = "seat_wechat_sess_id"
        private const val K_SEAT_AUTH = "seat_authorization"
        private const val K_SEAT_FROM = "seat_from_code"
        private const val K_SEAT_VER = "seat_version"
        private const val K_SEAT_VERIFIED_AT = "seat_verified_at"

        private const val FILE_NAME = "library_identity_enc"

        @Volatile
        private var instance: IdentityStore? = null

        fun get(ctx: Context): IdentityStore = instance ?: synchronized(this) {
            instance ?: IdentityStore(createPrefs(ctx.applicationContext)).also { instance = it }
        }

        private fun createPrefs(ctx: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(ctx, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                ctx,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

        /** 测试用：注入普通 SharedPreferences 走同一套逻辑。 */
        internal fun forTest(prefs: SharedPreferences): IdentityStore = IdentityStore(prefs)
    }
}
