package com.cncverse

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec


object SKLiveCryptoUtils {
    private val V23_KEY = "ST00ZGt3UGlPdVJP".toByteArray(Charsets.UTF_8)
    private val V23_IV  = "d2WT1lR4ckEvUsdk".toByteArray(Charsets.UTF_8)

    private val LEGACY_AES_KEY = hexStringToByteArray("6c326c356b4237784335715031724b31")
    private val LEGACY_AES_IV  = hexStringToByteArray("70314b356e50377542386848316c3139")

    private val LOOKUP_TABLE_D = (
        "\u0000\u0001\u0002\u0003\u0004\u0005\u0006\u0007\b\t\n\u000B\u000C\r\u000E\u000F" +
        "\u0010\u0011\u0012\u0013\u0014\u0015\u0016\u0017\u0018\u0019\u001A\u001B\u001C\u001D\u001E\u001F" +
        " !\"#\$%&'()*+,-./" +
        "0123456789:;<=>?" +
        "@EGMNKABUVCDYHLI" +
        "FPOZQSRWTXJ[\\]^_" +
        "`egmnkabuvcdyhli" +
        "fpozqsrwtxj{|}~\u007F"
    )

    private val V25_KEY1 = "V9LQR42pNKc7smaX"
    private val V25_KEY2 = "d2WT1lR4ckEvUsdk"
    private val V25_IV = "I=4dkwPiOuROD+pD"
    private val FALLBACK_AES_KEY = "l2l5kB7xC5qP1rK1"
    private val FALLBACK_AES_IV = "p1K5nP7uB8hH1l19"

    private fun hexStringToByteArray(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }

    private fun decryptV23(encryptedData: String): String? {
        return try {
            val padded = if (encryptedData.length % 4 != 0) {
                encryptedData + "=".repeat(4 - encryptedData.length % 4)
            } else {
                encryptedData
            }
            val inner = byteArrayOf(*Base64.decode(padded, Base64.DEFAULT))
                .toMutableList()

            for (i in 0 until inner.size - 1 step 2) {
                val tmp = inner[i]
                inner[i] = inner[i + 1]
                inner[i + 1] = tmp
            }

            inner.reverse()

            val ciphertext = Base64.decode(inner.toByteArray(), Base64.DEFAULT)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(V23_KEY, "AES"),
                IvParameterSpec(V23_IV)
            )
            val plaintext = cipher.doFinal(ciphertext)
            String(plaintext, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
    private fun decryptLegacy(encryptedData: String): String? {
        return try {
            val standardB64 = customToStandardBase64(encryptedData)
            val ciphertext = Base64.decode(standardB64, Base64.DEFAULT)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val secretKeySpec = SecretKeySpec(LEGACY_AES_KEY, "AES")
            val ivParameterSpec = IvParameterSpec(LEGACY_AES_IV)
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec)
            val decrypted = cipher.doFinal(ciphertext)
            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            println("[ERROR] Legacy decryption failed: ${e.message}")
            null
        }
    }

    private fun padBase64(s: String): String =
        if (s.length % 4 != 0) s + "=".repeat(4 - s.length % 4) else s

    private fun prepareCiphertext(encryptedData: String): ByteArray? {
        val src = if (encryptedData.startsWith("==")) encryptedData.reversed() else encryptedData
        return try {
            val decoded = Base64.decode(padBase64(src), Base64.DEFAULT)
            when {
                decoded.size > 12 && decoded.size % 16 == 12 -> decoded.copyOfRange(12, decoded.size)
                decoded.size % 16 == 0 -> decoded
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun aesDecryptAndTransform(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? {
        return try {
            if (ciphertext.size % 16 != 0) return null
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val plain = cipher.doFinal(ciphertext).toMutableList()
            for (i in 0 until plain.size - 1 step 2) {
                val tmp = plain[i]
                plain[i] = plain[i + 1]
                plain[i + 1] = tmp
            }
            plain.reverse()
            plain.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    private fun decryptV25Pass1(encryptedData: String): String? {
        val ciphertext = prepareCiphertext(encryptedData) ?: return null
        val plain = aesDecryptAndTransform(ciphertext, V23_KEY, V25_KEY1.toByteArray(Charsets.UTF_8)) ?: return null
        return try {
            val s = String(Base64.decode(plain, Base64.DEFAULT), Charsets.UTF_8).trimStart()
            if (s.startsWith("[") || s.startsWith("{")) s else null
        } catch (_: Exception) { null }
    }

    private fun decryptV25Pass2(encryptedData: String): String? {
        val ciphertext = prepareCiphertext(encryptedData) ?: return null
        val plain = aesDecryptAndTransform(ciphertext, V23_KEY, V25_KEY2.toByteArray(Charsets.UTF_8)) ?: return null
        return try {
            val s = String(Base64.decode(plain, Base64.DEFAULT), Charsets.UTF_8).trimStart()
            if (s.startsWith("[") || s.startsWith("{")) s else null
        } catch (_: Exception) { null }
    }

    private fun preprocessResponse(rawResponse: String): String? {
        return try {
            val chars = rawResponse.toCharArray()
            for (i in 0 until chars.size - 1 step 2) {
                val tmp = chars[i]
                chars[i] = chars[i + 1]
                chars[i + 1] = tmp
            }
            val reversed = String(chars).reversed()
            val decoded = Base64.decode(reversed, Base64.DEFAULT)
            val str = String(decoded, Charsets.UTF_8)
            if (!str.endsWith("BA@GBA@GBA@GBA@G")) return null
            str.substring(0, str.length - "BA@GBA@GBA@GBA@G".length)
        } catch (_: Exception) { null }
    }

    private fun decryptFallbackB(encryptedData: String): String? {
        return try {
            val standardB64 = customToStandardBase64(encryptedData)
            val decodedBytes = Base64.decode(padBase64(standardB64), Base64.DEFAULT)
            val step1 = String(decodedBytes, Charsets.UTF_8)
            val step2 = step1.reversed()
            val step3Raw = Base64.decode(padBase64(step2), Base64.DEFAULT)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(FALLBACK_AES_KEY.toByteArray(Charsets.UTF_8), "AES"), IvParameterSpec(FALLBACK_AES_IV.toByteArray(Charsets.UTF_8)))
            val result = String(cipher.doFinal(step3Raw), Charsets.UTF_8).trimStart()
            if (result.startsWith("[") || result.startsWith("{")) result else null
        } catch (_: Exception) { null }
    }

    private fun decryptFallbackC(encryptedData: String): String? {
        return try {
            val raw = Base64.decode(padBase64(encryptedData), Base64.DEFAULT)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(FALLBACK_AES_KEY.toByteArray(Charsets.UTF_8), "AES"), IvParameterSpec(FALLBACK_AES_IV.toByteArray(Charsets.UTF_8)))
            val result = String(cipher.doFinal(raw), Charsets.UTF_8).trimStart()
            if (result.startsWith("[") || result.startsWith("{")) result else null
        } catch (_: Exception) { null }
    }

    private fun customToStandardBase64(customB64: String): String {
        val result = StringBuilder()
        for (char in customB64) {
            val asciiVal = char.code
            if (asciiVal < LOOKUP_TABLE_D.length) {
                result.append(LOOKUP_TABLE_D[asciiVal])
            } else {
                result.append(char)
            }
        }
        return result.toString()
    }

    fun decryptSKLive(encryptedData: String): String? {
        val preprocessed = preprocessResponse(encryptedData)
        val v25Input = preprocessed ?: encryptedData
        decryptV25Pass1(v25Input)?.let { return it }
        decryptV25Pass2(v25Input)?.let { return it }
        decryptV23(encryptedData)?.let { return it }
        decryptLegacy(v25Input)?.let { return it }
        val legacyInput = try { String(Base64.decode(encryptedData, Base64.DEFAULT), Charsets.UTF_8) } catch (_: Exception) { encryptedData }
        decryptLegacy(legacyInput)?.let { return it }
        if (legacyInput != encryptedData) decryptLegacy(encryptedData)?.let { return it }
        decryptFallbackB(encryptedData)?.let { return it }
        decryptFallbackC(encryptedData)?.let { return it }
        return null
    }
}
