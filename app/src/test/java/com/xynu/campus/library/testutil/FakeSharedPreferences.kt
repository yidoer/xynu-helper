package com.xynu.campus.library.testutil

import android.content.SharedPreferences

/**
 * 纯 JVM 的 SharedPreferences 内存实现。
 *
 * 为什么需要它：`IdentityStore` 的逻辑（分区、覆盖、清理、快照恢复）必须在纯 JVM
 * 单测里可验证，而把真正的 `EncryptedSharedPreferences` 塞进单测需要 Robolectric。
 * 这里用内存实现验证**逻辑**，加密本身由 androidx.security 保证。
 *
 * ## 加密落盘由真机测试覆盖（此前不是，现已补上）
 *
 * 本文件早前的注释写着「另有 Robolectric 用例验证真实加密落盘不出现明文」。
 * 那句话曾经是**假的** —— 全仓搜索 `EncryptedSharedPreferences` / `MasterKey`，
 * 在 `app/src/test` 下找不到任何使用它们做断言的测试。
 *
 * 之后核实：**真机测试其实早就存在**，是
 * `app/src/androidTest/.../EncryptedIdentityStoreTest.kt`，只是从未被执行过
 * （一直以「需要真机」为由挂着）。现在已经在 API 35 模拟器上实际跑通：
 *
 * ```
 * Starting 10 tests on fanzha_phone(AVD) - 15
 * Finished 14 tests …
 * BUILD SUCCESSFUL
 * ```
 *
 * 其中关键的 `openidAndCookieNeverAppearInPlaintextOnDisk` 直接读加密文件字节，
 * 断言不含明文 openid / wechatSESS_ID / JWT / JWT 片段 —— **通过**。
 * 所以"加密真的生效"这件事现在**有**自动化覆盖了。
 *
 * 但请注意分工，别把两者混为一谈：
 * - **本文件**（`FakeSharedPreferences`）+ JVM 单测负责 `IdentityStore` 的**逻辑**；
 * - **`EncryptedIdentityStoreTest`**（androidTest，需设备）负责**真实加密落盘**；
 * - 在纯 JVM 单测里你仍然测不到加密，那个 Fake 也不会假装测到了。
 *
 * 静态层面另有保障：`allowBackup="false"`、`data_extraction_rules.xml` 排除了
 * `library_identity_enc.xml`、`SecurityParams` 参数取值正确（security-audit 逐条核过）。
 */
class FakeSharedPreferences : SharedPreferences {

    private val map = LinkedHashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(map)

    override fun getString(key: String, defValue: String?): String? =
        map[key] as? String ?: defValue

    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST")
        (map[key] as? MutableSet<String>) ?: defValues

    override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue

    override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        map[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = map.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    /** 直接暴露给测试断言"磁盘上到底写了什么"。 */
    fun rawKeys(): Set<String> = map.keys.toSet()

    fun rawValue(key: String): Any? = map[key]

    private inner class Editor : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private val removals = HashSet<String>()
        private var clearRequested = false

        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) =
            apply { pending[key] = values }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { removals.add(key) }
        override fun clear() = apply { clearRequested = true }

        override fun commit(): Boolean {
            applyChanges()
            return true
        }

        override fun apply() = applyChanges()

        private fun applyChanges() {
            if (clearRequested) map.clear()
            removals.forEach { map.remove(it) }
            pending.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
        }
    }
}
