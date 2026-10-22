package com.netease.lite.api

import java.math.BigInteger
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 网易云 /weapi 请求体加密（`netease-lite\electron\api\crypto.js` 的 Kotlin 移植）。
 *
 * 只读接口用明文 GET 就够，但凡是要改服务端状态的接口（登录、收藏、歌单增删）
 * 都走 /weapi/，body 必须是 AES + RSA 包出来的：
 *
 *   params    = AES-128-CBC( AES-128-CBC(json, PRESET_KEY), secretKey )   -> base64
 *   encSecKey = RSA( secretKey )  —— 教科书式模幂，输入按站点约定**小端**
 *
 * 逐字节与 JS 版对齐：IV、预置密钥、模数、指数全部是常量，不需要联网取。
 */
object Weapi {

    /**
     * 注意这里是 **ASCII 的 16 个字符**，不是 8 个原始字节。
     *
     * JS 那边写的是 `Buffer.from('0102030405060708')` —— Node 的 Buffer.from
     * 不带编码参数时按 UTF-8 处理，所以拿到的是 `0x30 0x31 0x30 0x32 ...`。
     * 第一版按字面搬成 `byteArrayOf(1,2,...,8)`（8 字节），服务端直接回
     * `expected IV length of 16 but was 8`。两个 0x30 的差别，整个登录就废了。
     */
    private val IV = "0102030405060708".toByteArray(Charsets.UTF_8)
    private const val PRESET_KEY = "0CoJUm6Qyw8W8jud"
    private const val BASE62 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    private val BI_PUB = BigInteger("010001", 16)
    private val BI_MOD = BigInteger(
        "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629" +
            "ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d8" +
            "13cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7",
        16,
    )

    private val random = SecureRandom()

    fun aesEncrypt(text: String, key: ByteArray): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(IV))
        return android.util.Base64.encodeToString(
            cipher.doFinal(text.toByteArray(Charsets.UTF_8)),
            android.util.Base64.NO_WRAP,
        )
    }

    /**
     * 教科书 RSA 模幂。站点把小端字节序直接当大整数用，所以这里也要反一下 ——
     * 这一句是整段加密里唯一「不合常理但必须照抄」的地方。
     */
    fun rsaEncrypt(text: String): String {
        val reversed = text.toByteArray(Charsets.UTF_8).reversedArray()
        val hex = reversed.joinToString("") { "%02x".format(it) }
        val m = BigInteger(hex.ifEmpty { "0" }, 16)
        return m.modPow(BI_PUB, BI_MOD).toString(16).padStart(256, '0')
    }

    fun randomKey(len: Int = 16): String {
        val bytes = ByteArray(len)
        random.nextBytes(bytes)
        val sb = StringBuilder(len)
        for (i in 0 until len) sb.append(BASE62[(bytes[i].toInt() and 0xff) % BASE62.length])
        return sb.toString()
    }

    /** 构造 /weapi/ POST 用的表单字段。 */
    fun encode(data: String): Map<String, String> {
        val secretKey = randomKey(16)
        val params = aesEncrypt(aesEncrypt(data, PRESET_KEY.toByteArray(Charsets.UTF_8)), secretKey.toByteArray(Charsets.UTF_8))
        return mapOf("params" to params, "encSecKey" to rsaEncrypt(secretKey))
    }
}
