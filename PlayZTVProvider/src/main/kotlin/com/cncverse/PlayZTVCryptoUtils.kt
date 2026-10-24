package com.cncverse

import android.util.Base64
import android.util.Log
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object PlayZTVCryptoUtils {

    private const val TAG = "PlayZTVCrypto"

    /**
     * OLD FORMAT
     */
    private val PLAYZ_AES_KEY = BuildConfig.PLAYZ_AES_KEY

    private val PLAYZ_AES_IV = BuildConfig.PLAYZ_AES_IV

    /**
     * PRIMARY FORMAT
     */
    private val PLAYZ_PRIMARY_AES_KEY = BuildConfig.PLAYZ_PRIMARY_AES_KEY

    private val PLAYZ_PRIMARY_AES_IV = BuildConfig.PLAYZ_PRIMARY_AES_IV

    /**
     * Substitution maps from plugin.js
     */
    private val SUB_FROM = BuildConfig.PLAYZ_SUB_FROM

    private val SUB_TO = BuildConfig.PLAYZ_SUB_TO

    private val SUB_REVERSE = HashMap<Char, Char>()

    init {
        for (i in SUB_TO.indices) {
            SUB_REVERSE[SUB_TO[i]] = SUB_FROM[i]
        }
    }

    private data class KeyInfo(
        val key: ByteArray,
        val iv: ByteArray
    )

    private fun decodeKey(base64: String): ByteArray {
        return Base64.decode(
            base64,
            Base64.DEFAULT
        )
    }

    private val PRIMARY_KEY by lazy {
        KeyInfo(
            decodeKey(PLAYZ_PRIMARY_AES_KEY),
            decodeKey(PLAYZ_PRIMARY_AES_IV)
        )
    }

    private val FALLBACK_KEY by lazy {
        KeyInfo(
            decodeKey(PLAYZ_AES_KEY),
            decodeKey(PLAYZ_AES_IV)
        )
    }

    /**
     * Reverse substitution cipher
     */
    private fun decodeSubstitutionPayload(
        value: String
    ): String {

        val restored = buildString {

            for (char in value) {
                append(
                    SUB_REVERSE[char] ?: char
                )
            }
        }

        return String(
            Base64.decode(
                normalizeBase64(restored),
                Base64.DEFAULT
            ),
            Charsets.UTF_8
        )
    }

    /**
     * Normalize malformed base64
     */
    private fun normalizeBase64(
        value: String
    ): String {

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
    private fun decryptAes(
        dataB64: String,
        keyInfo: KeyInfo
    ): String? {

        return try {

            val cipherBytes = Base64.decode(
                normalizeBase64(dataB64),
                Base64.DEFAULT
            )

            val cipher = Cipher.getInstance(
                "AES/CBC/PKCS5Padding"
            )

            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(
                    keyInfo.key,
                    "AES"
                ),
                IvParameterSpec(
                    keyInfo.iv
                )
            )

            val decrypted =
                cipher.doFinal(cipherBytes)

            String(
                decrypted,
                Charsets.UTF_8
            ).trim()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "AES failed: ${e.message}"
            )

            null
        }
    }

    /**
     * MAIN DECRYPT
     */
    fun decryptPlayZTV(
        body: String?
    ): String? {

        return try {

            val raw = body?.trim().orEmpty()

            if (raw.isEmpty()) {
                return null
            }

            Log.d(TAG, "Raw Payload: $raw")

            /**
             * Already plain JSON/HTML
             */
            if (
                raw.startsWith("{") ||
                raw.startsWith("[") ||
                raw.startsWith("<")
            ) {
                return raw
            }

            /**
             * PRIMARY FORMAT
             *
             * substitution ->
             * base64 text ->
             * AES decrypt
             */
            try {

                val primaryPayload =
                    decodeSubstitutionPayload(
                        raw.replace("\\s".toRegex(), "")
                    )

                Log.d(
                    TAG,
                    "Primary payload decoded"
                )

                val primary =
                    decryptAes(
                        primaryPayload,
                        PRIMARY_KEY
                    )

                if (!primary.isNullOrBlank()) {

                    Log.d(
                        TAG,
                        "Primary decrypt success"
                    )

                    return primary
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Primary decrypt failed: ${e.message}"
                )
            }

            /**
             * FALLBACK FORMAT
             */
            try {

                val fallback =
                    decryptAes(
                        raw.replace("\\s".toRegex(), ""),
                        FALLBACK_KEY
                    )

                if (!fallback.isNullOrBlank()) {

                    Log.d(
                        TAG,
                        "Fallback decrypt success"
                    )

                    return fallback
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Fallback decrypt failed: ${e.message}"
                )
            }

            Log.e(
                TAG,
                "All decryption strategies failed"
            )

            null

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Decrypt error: ${e.message}",
                e
            )

            null
        }
    }
}