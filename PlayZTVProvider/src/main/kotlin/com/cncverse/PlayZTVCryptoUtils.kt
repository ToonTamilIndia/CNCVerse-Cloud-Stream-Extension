package com.cncverse

import android.util.Base64
import android.util.Log
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object PlayZTVCryptoUtils {

    private const val TAG = "PlayZTVCrypto"

    private val NATIVE_KEY: ByteArray = BuildConfig.PLAYZ_NATIVE_KEY.toByteArray(Charsets.US_ASCII)
    private val NATIVE_IV: ByteArray = BuildConfig.PLAYZ_NATIVE_IV.toByteArray(Charsets.US_ASCII)

    private val PRIMARY_KEY: ByteArray = decodeKey(BuildConfig.PLAYZ_PRIMARY_AES_KEY)
    private val PRIMARY_IV: ByteArray = decodeKey(BuildConfig.PLAYZ_PRIMARY_AES_IV)

    private val FALLBACK_KEY: ByteArray = decodeKey(BuildConfig.PLAYZ_AES_KEY)
    private val FALLBACK_IV: ByteArray = decodeKey(BuildConfig.PLAYZ_AES_IV)

    private val XOR_KEY: ByteArray = BuildConfig.PLAYZ_XOR_KEY.toByteArray(Charsets.US_ASCII)
    private val CATEGORY_XOR_SUFFIX = BuildConfig.PLAYZ_CATEGORY_XOR_SUFFIX

    private val SUB_FROM = BuildConfig.PLAYZ_SUB_FROM
    private val SUB_TO = BuildConfig.PLAYZ_SUB_TO

    private val SUB_REVERSE = HashMap<Char, Char>()

    init {
        for (i in SUB_TO.indices) {
            SUB_REVERSE[SUB_TO[i]] = SUB_FROM[i]
        }
    }

    private fun decodeKey(base64: String): ByteArray = Base64.decode(base64, Base64.DEFAULT)

    /**
     * XOR with a caller-supplied key; output must look like M3U or JSON.
     */
    private fun decryptXor(raw: String, key: ByteArray): String? {
        return try {
            val stripped = raw.replace("\\s".toRegex(), "")
            val decoded = Base64.decode(normalizeBase64(stripped), Base64.DEFAULT)
            val result = ByteArray(decoded.size)
            for (i in decoded.indices) {
                result[i] = (decoded[i].toInt() xor key[i % key.size].toInt()).toByte()
            }
            val text = String(result, Charsets.UTF_8).trim()
            if (text.startsWith("#EXTM3U") ||
                text.startsWith("#EXTINF") ||
                text.startsWith("{") ||
                text.startsWith("[")
            ) {
                Log.d(TAG, "XOR decrypt success (${result.size} bytes)")
                text
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "XOR decrypt failed: ${e.message}")
            null
        }
    }

    fun decryptWithCategoryKey(body: String, categoryKey: String): String? {
        if (categoryKey.isBlank()) return null
        return decryptXor(body.trim(), (categoryKey + CATEGORY_XOR_SUFFIX).toByteArray(Charsets.UTF_8))
    }

    /**
     * NATIVE FORMAT
     *
     * base64 -> reverse bytes -> swap adjacent pairs -> base64 -> AES
     */
    private fun decryptNativeLib(raw: String): String? {
        return try {
            val stripped = raw.replace("\\s".toRegex(), "")
            val b1 = Base64.decode(stripped, Base64.DEFAULT)
            val b1Reversed = b1.reversedArray()
            val b2 = swapAdjacentPairs(b1Reversed)
            val b2Str = String(b2, Charsets.US_ASCII).replace("\\s".toRegex(), "")
            val b3 = Base64.decode(b2Str, Base64.DEFAULT)
            Log.d(TAG, "Native: B1=${b1.size}B rev=${b1Reversed.size}B B2=${b2.size}B B3=${b3.size}B")
            val pt = aesCbcDecrypt(b3, NATIVE_KEY, NATIVE_IV) ?: return null
            val text = String(pt, Charsets.UTF_8).trim()
            if (text.startsWith("{") || text.startsWith("[")) {
                Log.d(TAG, "Native decrypt success (${pt.size} bytes)")
                text
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Native decrypt failed: ${e.message}")
            null
        }
    }

    private fun swapAdjacentPairs(b: ByteArray): ByteArray {
        val out = b.copyOf()
        var i = 0
        while (i + 1 < out.size) {
            val tmp = out[i]
            out[i] = out[i + 1]
            out[i + 1] = tmp
            i += 2
        }
        return out
    }

    /**
     * PRIMARY FORMAT
     *
     * substitution -> base64 -> base64 -> AES
     */
    private fun decryptPrimary(raw: String): String? {
        return try {
            val stripped = raw.replace("\\s".toRegex(), "")
            val restored = buildString {
                for (c in stripped) {
                    append(SUB_REVERSE[c] ?: c)
                }
            }
            val b1 = Base64.decode(normalizeBase64(restored), Base64.DEFAULT)
            val b1Str = String(b1, Charsets.US_ASCII)
            val ct = Base64.decode(normalizeBase64(b1Str), Base64.DEFAULT)
            val pt = aesCbcDecrypt(ct, PRIMARY_KEY, PRIMARY_IV) ?: return null
            val text = String(pt, Charsets.UTF_8).trim()
            if (text.startsWith("{") || text.startsWith("[")) text else null
        } catch (e: Exception) {
            Log.e(TAG, "Primary decrypt failed: ${e.message}")
            null
        }
    }

    /**
     * FALLBACK FORMAT
     */
    private fun decryptFallback(raw: String): String? {
        return try {
            val stripped = raw.replace("\\s".toRegex(), "")
            val ct = Base64.decode(normalizeBase64(stripped), Base64.DEFAULT)
            val pt = aesCbcDecrypt(ct, FALLBACK_KEY, FALLBACK_IV) ?: return null
            val text = String(pt, Charsets.UTF_8).trim()
            if (text.startsWith("{") || text.startsWith("[")) text else null
        } catch (e: Exception) {
            Log.e(TAG, "Fallback decrypt failed: ${e.message}")
            null
        }
    }

    /**
     * MAIN DECRYPT
     */
    fun decryptPlayZTV(body: String?): String? {
        val raw = body?.trim() ?: ""
        if (raw.isEmpty()) {
            Log.w(TAG, "Empty body")
            return null
        }
        Log.d(TAG, "Raw payload: len=${raw.length}")
        if (raw.startsWith("{") || raw.startsWith("[") || raw.startsWith("<")) {
            return raw
        }
        decryptNativeLib(raw)?.let { return it }
        decryptPrimary(raw)?.let { return it }
        decryptFallback(raw)?.let { return it }
        decryptXor(raw, XOR_KEY)?.let { return it }
        Log.e(TAG, "All decryption strategies failed")
        return null
    }

    /**
     * Normalize malformed base64
     */
    private fun normalizeBase64(value: String): String {
        var normalized = value
            .replace("-", "+")
            .replace("_", "/")
            .replace("\n", "")
            .replace("\r", "")
            .replace(" ", "")
            .replace("\t", "")
        while (normalized.length % 4 != 0) {
            normalized += "="
        }
        return normalized
    }

    /**
     * AES CBC PKCS5 decrypt
     */
    private fun aesCbcDecrypt(cipherBytes: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? {
        if (key.size != 16 || iv.size != 16 || cipherBytes.size % 16 != 0) return null
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(iv)
            )
            cipher.doFinal(cipherBytes)
        } catch (e: Exception) {
            Log.e(TAG, "AES failed: ${e.message}")
            null
        }
    }
}
