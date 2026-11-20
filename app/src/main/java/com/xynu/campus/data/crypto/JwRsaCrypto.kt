package com.xynu.campus.data.crypto

import android.util.Base64
import java.math.BigInteger
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher

/**
 * 正方教务 RSA 登录密码加密。
 * 与前端 JS 算法一致：Base64(modulus) + Base64(exponent) 构造公钥，
 * 使用 RSA/ECB/PKCS1Padding 加密，并做标准 Base64 编码。
 */
object JwRsaCrypto {

    private fun b64ToBytes(b64: String): ByteArray = Base64.decode(b64, Base64.DEFAULT)

    /** 用教务下发的公钥加密密码，返回 Base64 密文 */
    fun encryptPassword(modulusB64: String, exponentB64: String, password: String): String {
        val modulusBytes = b64ToBytes(modulusB64)
        val exponentBytes = b64ToBytes(exponentB64)
        // 去除可能的引导 0 字节（getPositive 需要转成无符号 BigInteger）
        val modulus = BigInteger(1, modulusBytes)
        val exponent = BigInteger(1, exponentBytes)

        val keyFactory = KeyFactory.getInstance("RSA")
        val pubKey = keyFactory.generatePublic(RSAPublicKeySpec(modulus, exponent)) as RSAPublicKey

        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, pubKey)
        val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }
}