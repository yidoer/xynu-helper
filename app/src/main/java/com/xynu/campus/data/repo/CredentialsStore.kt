package com.xynu.campus.data.repo

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xynu.campus.data.crypto.KeystoreCrypto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "xynu_credentials")

/**
 * 用户凭据安全存储：DataStore 持久化 + Keystore 加密。
 * 每个系统的凭据独立保存，便于单独登录。
 */
object CredentialsStore {

    private val KEY_JW_USER = stringPreferencesKey("jw_username")
    private val KEY_JW_PASS = stringPreferencesKey("jw_password")
    private val KEY_LIB_USER = stringPreferencesKey("lib_username")
    private val KEY_LIB_PASS = stringPreferencesKey("lib_password")

    data class Credentials(val username: String, val password: String)

    suspend fun saveJw(ctx: Context, username: String, password: String) {
        ctx.dataStore.edit { prefs ->
            prefs[KEY_JW_USER] = KeystoreCrypto.encrypt(username)
            prefs[KEY_JW_PASS] = KeystoreCrypto.encrypt(password)
        }
    }

    suspend fun saveLib(ctx: Context, username: String, password: String) {
        ctx.dataStore.edit { prefs ->
            prefs[KEY_LIB_USER] = KeystoreCrypto.encrypt(username)
            prefs[KEY_LIB_PASS] = KeystoreCrypto.encrypt(password)
        }
    }

    suspend fun clearJw(ctx: Context) {
        ctx.dataStore.edit { prefs -> prefs.remove(KEY_JW_USER); prefs.remove(KEY_JW_PASS) }
    }

    suspend fun clearLib(ctx: Context) {
        ctx.dataStore.edit { prefs -> prefs.remove(KEY_LIB_USER); prefs.remove(KEY_LIB_PASS) }
    }

    fun jwCredentials(ctx: Context): Flow<Credentials?> = ctx.dataStore.data.map { prefs ->
        val u = prefs[KEY_JW_USER] ?: return@map null
        val p = prefs[KEY_JW_PASS] ?: return@map null
        Credentials(KeystoreCrypto.decrypt(u), KeystoreCrypto.decrypt(p))
    }

    fun libCredentials(ctx: Context): Flow<Credentials?> = ctx.dataStore.data.map { prefs ->
        val u = prefs[KEY_LIB_USER] ?: return@map null
        val p = prefs[KEY_LIB_PASS] ?: return@map null
        Credentials(KeystoreCrypto.decrypt(u), KeystoreCrypto.decrypt(p))
    }
}