package com.xynu.campus.data.crypto

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 图书馆 OPAC 登录密码 AES 加密。
 * 与前端脚本一致：CryptoJS.AES.encrypt(content, key, {mode:ECB, padding:Pkcs7}).toString()
 * 密钥源取自 OPAC 登录页脚本；若密钥长度非法（非 16/24/32 字节），
 * 自动用 SHA-256 派生为 32 字节，保证任何情况下都不会抛异常导致闪退。
 */
object AesEcbCrypto {

    // 密钥源：OPAC 登录页硬编码值（运行期应为 16 字节；非法长度时自动规范化）
    const val LIB_KEY = "goldlib@2306!@#$"

    private val VALID_KEY_SIZES = intArrayOf(16, 24, 32)

    /** 规范化密钥：合法则原样，非法则 SHA-256 派生 32 字节 */
    private fun normalizeKeyBytes(key: String): ByteArray {
        val raw = key.toByteArray(Charsets.UTF_8)
        if (raw.size in VALID_KEY_SIZES) return raw
        return MessageDigest.getInstance("SHA-256").digest(raw)
    }

    fun encrypt(content: String, key: String): String {
        val keyBytes = normalizeKeyBytes(key)
        val spec = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, spec)
        val encrypted = cipher.doFinal(content.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }
}