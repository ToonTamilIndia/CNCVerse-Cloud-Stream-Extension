package com.cncverse

import android.util.Base64
import com.fasterxml.jackson.core.type.TypeReference
import com.lagradost.cloudstream3.utils.AppUtils
import kotlinx.serialization.SerializationException
import kotlinx.serialization.modules.SerializersModule
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object PlayFyCryptoUtils {

    const val DEFAULT_LORA = "zH7hY9@lO=8uXk#f%mI/VvJd2G10Z5eU+L6Pi&aEbwA4scBCStQ3KyWqRjDgnoMRP"
    const val DEFAULT_SIG = "Mc1pOdG+rjLRsO8tlGCxcBFqGIU"

    private val AES_KEY = "hg47Dd84jfK83ncG"
    private val AES_IV = "47hgD84sjG83nFkH"
    private const val SHUFFLED_ALPHABET = "fFgGjJkKaApPbBmMoOzZeEnNcCdDrRqQtTvVuUxXhHiIwWyYlLsS"
    private const val STANDARD_ALPHABET = "aAbBcCdDeEfFgGhHiIjJkKlLmMnNoOpPqQrRsStTuUvVwWxXyYzZ"

    private val FALLBACK_KEY = "ouAzyvGQqd5yAi5G".toByteArray(Charsets.ISO_8859_1)
    private val FALLBACK_IV = "QC4a1NX)XXEHq1bf".toByteArray(Charsets.ISO_8859_1)
    private const val FNV32_PRIME = 16777619L
    private const val MASK32 = 4294967295L

    private val decodeTable: CharArray by lazy {
        val table = CharArray(128) { it.toChar() }
        for (i in SHUFFLED_ALPHABET.indices) {
            table[SHUFFLED_ALPHABET[i].code] = STANDARD_ALPHABET[i]
        }
        table
    }

    private fun decryptSubstitution(str: String): String {
        val chars = CharArray(str.length) { i ->
            val c = str[i].code
            if (c in 0..127) decodeTable[c] else str[i]
        }
        return String(chars)
    }

    fun decryptHttpResponse(str: String): String {
        if (str.startsWith("{") || str.startsWith("[")) return str
        return try {
            val substituted = decryptSubstitution(str)
            val padded = if (substituted.length % 4 != 0) {
                substituted + "=".repeat(4 - (substituted.length % 4))
            } else {
                substituted
            }
            val decoded = Base64.decode(padded, Base64.DEFAULT)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(AES_KEY.toByteArray(Charsets.UTF_8), "AES"),
                IvParameterSpec(AES_IV.toByteArray(Charsets.UTF_8))
            )
            String(cipher.doFinal(decoded), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    fun decrypt(str: String): String? = decryptHttpResponse(str).ifBlank { null }

    fun hexToBase64Unpadded(hex: String): String {
        val clean = hex.replace("-", "").replace(" ", "")
        val bytes = ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun fnv1a32(data: ByteArray, seed: Long): Long {
        var h = seed and MASK32
        for (b in data) {
            h = (((b.toLong() and 255) xor h) * FNV32_PRIME) and MASK32
        }
        return h
    }

    private fun s32(v: Long): Long {
        val u = MASK32 and v
        return if (u >= 2147483648L) u - 4294967296L else u
    }

    private fun u32(v: Long): Long = MASK32 and v

    private fun deriveKey(lora: String, sig: String): ByteArray {
        val sigBytes = sig.toByteArray(Charsets.ISO_8859_1)
        val sigLen = sig.length
        val loraLen = lora.length
        var h = fnv1a32(sigBytes, 2166136261L)
        val key = ByteArray(16)
        var offset = 0L
        for (i in 0 until 16) {
            val idx = i % sigLen
            val charVal = s32(sig[idx].code.toLong())
            val product = s32(h) * 31
            h = u32(s32(charVal + offset) xor s32(product))
            key[i] = lora[(h % loraLen).toInt()].code.toByte()
            offset = u32(13L + offset)
        }
        return key
    }

    private fun deriveIv(lora: String, sig: String): ByteArray {
        val sigBytes = sig.toByteArray(Charsets.ISO_8859_1)
        val sigLen = sig.length
        val loraLen = lora.length
        var mixed = fnv1a32(sigBytes, 2166129450L)
        val iv = ByteArray(16)
        var ivIdx = 0
        var offset = 0L
        var loop = 0
        while (loop != 48) {
            val idx = loop % sigLen
            val charVal = s32(sig[idx].code.toLong())
            val product = s32(mixed) * 29
            mixed = u32(s32(charVal + offset) xor s32(product))
            iv[ivIdx] = lora[(mixed % loraLen).toInt()].code.toByte()
            loop += 3
            offset = u32(7L + offset)
            ivIdx++
        }
        return iv
    }

    private fun decodeBase64(encoded: String): ByteArray {
        var s = encoded.trim()
        val mod = s.length % 4
        if (mod != 0) s += "=".repeat(4 - mod)
        return Base64.decode(s, 0)
    }

    private fun pkcs7Unpad(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val pad = data.last().toInt() and 255
        if (pad in 1..16 && data.takeLast(pad).all { it.toInt() and 255 == pad }) {
            return data.copyOf(data.size - pad)
        }
        return data
    }

    private fun aesCbcDecrypt(ct: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            pkcs7Unpad(cipher.doFinal(ct))
        } catch (e: Exception) {
            null
        }
    }

    private fun isValidUtf8(data: ByteArray): Boolean {
        return try {
            String(data, Charsets.UTF_8)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun decryptPlayFy(encoded: String, lora: String = DEFAULT_LORA, sig: String = DEFAULT_SIG): String? {
        return try {
            val ctBytes = decodeBase64(encoded)
            val key1 = deriveKey(lora, sig)
            val iv1 = deriveIv(lora, sig)
            val pt1 = aesCbcDecrypt(ctBytes, key1, iv1)
            if (pt1 != null && isValidUtf8(pt1)) {
                return String(pt1, Charsets.UTF_8)
            }
            val pt2 = aesCbcDecrypt(ctBytes, FALLBACK_KEY, FALLBACK_IV)
            if (pt2 != null && isValidUtf8(pt2)) {
                return String(pt2, Charsets.UTF_8)
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    fun extractDataField(responseText: String): String {
        return try {
            val data = parseJsonToMap(responseText)["data"]
            val str = data as? String
            str ?: responseText.trim()
        } catch (e: Exception) {
            responseText.trim()
        }
    }

    private fun parseJsonToMap(json: String): Map<String, Any> {
        return try {
            AppUtils.parseJson<Map<String, Any>>(json)
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
