package com.cncverse

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object PlayFyCryptoUtils {

    private val AES_KEY = "hg47Dd84jfK83ncG"
    private val AES_IV = "47hgD84sjG83nFkH"
    private const val SHUFFLED_ALPHABET = "fFgGjJkKaApPbBmMoOzZeEnNcCdDrRqQtTvVuUxXhHiIwWyYlLsS"
    private const val STANDARD_ALPHABET = "aAbBcCdDeEfFgGhHiIjJkKlLmMnNoOpPqQrRsStTuUvVwWxXyYzZ"

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
}
