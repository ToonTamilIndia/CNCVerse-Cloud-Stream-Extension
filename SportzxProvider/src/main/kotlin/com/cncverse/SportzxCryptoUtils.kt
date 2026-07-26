package com.cncverse

import android.util.Base64
import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object SportzxCryptoUtils {
    private const val APP_PASSWORD = "oAR80SGuX3EEjUGFRwLFKBTiris="

    private val CHARSET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+!@#$%&=".toByteArray(Charsets.UTF_8)

    private val AES_KEY: ByteArray by lazy { generateAesKeyIv(APP_PASSWORD).first }
    private val AES_IV: ByteArray by lazy { generateAesKeyIv(APP_PASSWORD).second }

    private val MAGIC = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
    private val PRK = decodeHex("d1a7ebbaed93b2f68ac092ade2b0075d917634447a3519b41e817ad631020053")
    private val CTX_HASH = decodeHex("1676ec7db4771b0d826d70369b579684b182d2c0133be041bdd55f5d6d79a98b")

    private fun u32(x: Long): Long = x and 4294967295L

    private fun generateAesKeyIv(s: String): Pair<ByteArray, ByteArray> {
        val data = s.toByteArray(Charsets.UTF_8)
        val n = data.size
        var u = u32(2166136261L)
        for (b in data) {
            u = u32((((b.toLong() and 255L) xor u) * 16777619L))
        }
        val key = ByteArray(16)
        for (i in 0 until 16) {
            val b2 = data[i % n].toLong() and 255L
            u = u32((31L * u) + (i.toLong() xor b2))
            key[i] = CHARSET[(u % CHARSET.size.toLong()).toInt()]
        }

        var u2 = u32(2166129450L)
        for (b in data) {
            u2 = u32((((b.toLong() and 255L) xor u2) * 16777619L))
        }
        val iv = ByteArray(16)
        var idx = 0
        var acc = 0L
        val dataForLoop = data
        while (idx != 48) {
            val b4 = dataForLoop[idx % n].toLong() and 255L
            u2 = u32((29L * u2) + (acc xor b4))
            iv[idx / 3] = CHARSET[(u2 % CHARSET.size.toLong()).toInt()]
            idx += 3
            acc = u32(7L + acc)
        }
        return Pair(key, iv)
    }

    private fun decodeHex(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun rotr3(b: Int): Int = ((b ushr 3) or (b shl 5)) and 255

    fun decrypt(b64Data: String): String? {
        val trimmed = b64Data.trim()
        if (trimmed.isEmpty()) return null
        return decryptPrimary(trimmed) ?: decryptFallback(trimmed) ?: decryptFallback2(trimmed)
    }

    private fun decryptPrimary(b64Data: String): String? {
        return try {
            val std = b64Data.replace('-', '+').replace('_', '/')
                .let { if (it.length % 4 != 0) it.padEnd(it.length + (4 - it.length % 4), '=') else it }
            val blob = Base64.decode(std, 0)
            if (blob.size < 5 || blob[0] != MAGIC[0] || blob[1] != MAGIC[1] || blob[2] != MAGIC[2] || blob[3] != MAGIC[3]) {
                return null
            }
            val n = blob[4].toInt() and 255
            val total = blob.size
            if (total < n + 5) return null
            val payload = if (n > 0) blob.copyOfRange(5, total - n) else blob.copyOfRange(5, total)
            val tail = if (n > 0) blob.copyOfRange(total - n, total) else ByteArray(0)
            if (payload.isEmpty()) return ""
            if (payload.size % 16 != 0) return null

            val hmacKey = tail + CTX_HASH
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(hmacKey, "HmacSHA256"))

            val output = ByteArrayOutputStream()
            var prev = ByteArray(0)
            var counter = 1
            while (output.size() < 48) {
                val input = prev + hmacKey + byteArrayOf(counter.toByte())
                val h = mac.doFinal(input)
                output.write(h)
                prev = h
                counter++
            }
            val kmat = output.toByteArray()
            val aesKey = kmat.copyOfRange(0, 32)
            val aesIv = kmat.copyOfRange(32, 48)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(aesIv))
            val raw = cipher.doFinal(payload)

            val result = ByteArray(raw.size)
            for (i in raw.indices) {
                val b = raw[i].toInt() and 255
                val r3 = rotr3(b)
                result[i] = (r3 xor (CTX_HASH[i % CTX_HASH.size].toInt() and 255)).toByte()
            }
            String(result, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun decryptFallback(b64Data: String): String? {
        return try {
            val std = b64Data.replace('-', '+').replace('_', '/')
                .let { if (it.length % 4 != 0) it.padEnd(it.length + (4 - it.length % 4), '=') else it }
            val data = Base64.decode(std, 0)
            val saltLen = data[4].toInt() and 255
            val salt = data.copyOfRange(data.size - saltLen, data.size)
            val ciphertext = data.copyOfRange(5, data.size - saltLen)

            val info = salt + CTX_HASH
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(PRK, "HmacSHA256"))

            val info1 = info + byteArrayOf(1)
            val t1 = mac.doFinal(info1)

            val info2 = t1 + info + byteArrayOf(2)
            val t2 = mac.doFinal(info2)

            val iv = t2.copyOfRange(0, 16)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(t1, "AES"), IvParameterSpec(iv))
            val aesOut = cipher.doFinal(ciphertext)

            val plaintext = ByteArray(aesOut.size)
            for (i in aesOut.indices) {
                val b = aesOut[i].toInt() and 255
                val rol5 = ((b shl 5) and 255) or (b ushr 3)
                val rol6 = CTX_HASH[i % 32].toInt() and 255
                plaintext[i] = (rol5 xor rol6).toByte()
            }
            String(plaintext, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun decryptFallback2(b64Data: String): String? {
        return try {
            val ct = Base64.decode(b64Data, 0)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(AES_KEY, "AES"), IvParameterSpec(AES_IV))
            val pt = cipher.doFinal(ct)
            String(pt, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
