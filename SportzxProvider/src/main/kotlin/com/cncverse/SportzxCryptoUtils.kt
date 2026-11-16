package com.cncverse

import android.util.Base64
import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object SportzxCryptoUtils {
    // Keys are injected at build time from this module's build.gradle.kts.
    private val APP_PASSWORD = BuildConfig.SPORTZX_APP_PASSWORD

    private val CHARSET = BuildConfig.SPORTZX_CHARSET.toByteArray(Charsets.UTF_8)

    private val AES_KEY: ByteArray by lazy { generateAesKeyIv(APP_PASSWORD).first }
    private val AES_IV: ByteArray by lazy { generateAesKeyIv(APP_PASSWORD).second }

    private val MAGIC = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
    private val PRK = decodeHex(BuildConfig.SPORTZX_PRK)
    private val CTX_HASH = decodeHex(BuildConfig.SPORTZX_CTX_HASH)

    private const val V3_VERSION_BYTE: Byte = 3
    private val V3_SXR_LABEL = "SXR3/kdf".toByteArray(Charsets.UTF_8)
    private val V3_PEPPER = decodeHex(BuildConfig.SPORTZX_V3_PEPPER)
    private val SXR_CONSTS = intArrayOf(0x53585233, 0x9E3779B9.toInt(), 0x7D3CA820, 0xC2B2AE3D.toInt())

    private const val V2_VERSION_BYTE: Byte = 2
    private val V2_PRK_LABEL = "sportzx/v2/prk".toByteArray(Charsets.UTF_8)
    private val V2_MAC_LABEL = "mac".toByteArray(Charsets.UTF_8)
    private val V2_ENC_LABEL = "enc".toByteArray(Charsets.UTF_8)

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
        return decryptV3(trimmed)
            ?: decryptV2(trimmed)
            ?: decryptPrimary(trimmed)
            ?: decryptFallback(trimmed)
            ?: decryptFallback2(trimmed)
    }

    private fun decryptV3(b64Data: String): String? {
        return try {
            val raw = urlSafeB64Decode(b64Data)
            require(raw.size >= 45) { "v3 blob too short (${raw.size})" }
            require(raw[0] == V3_VERSION_BYTE) { "not a v3 blob (version=0x${"%02x".format(raw[0])})" }
            val iv = raw.copyOfRange(1, 17)
            val counterWords = IntArray(3) { ld32le(raw, 17 + it * 4) }
            val ct = raw.copyOfRange(29, raw.size - 16)
            val tag = raw.copyOfRange(raw.size - 16, raw.size)

            val (streamState, macKey) = sxrDerive(CTX_HASH, iv)
            val sponge = SxrSponge()
            sponge.absorb(macKey, 32)
            sponge.absorb(raw, 29, 0)
            sponge.absorb(ct, ct.size, 0)
            sponge.absorb(macKey, 32)
            val computedTag = ByteArray(32)
            sponge.final(computedTag)
            var diff = 0
            for (i in 0 until 16) diff = diff or (computedTag[i].toInt() xor tag[i].toInt())
            require(diff == 0) { "HMAC tag mismatch — wrong cert_hash or tampered payload" }

            val pt = ByteArray(ct.size)
            val block = ByteArray(64)
            var offset = 0
            var blockCounter = 0
            while (offset < ct.size) {
                sxrBlock(streamState, blockCounter, counterWords, block)
                val chunk = minOf(64, ct.size - offset)
                for (i in 0 until chunk) {
                    pt[offset + i] = (ct[offset + i].toInt() xor block[i].toInt()).toByte()
                }
                offset += chunk
                blockCounter++
            }
            String(pt, Charsets.UTF_8)
        } catch (e: Exception) {
            println("SportzxCrypto: v3 decryption failed — ${e.message}")
            null
        }
    }

    private fun sxrQr(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = (s[d] xor s[a]).rotateLeft(13)
        s[c] += s[d]; s[b] = (s[b] xor s[c]).rotateLeft(17)
        s[a] += s[b]; s[d] = (s[d] xor s[a]).rotateLeft(9)
        s[c] += s[d]; s[b] = (s[b] xor s[c]).rotateLeft(25)
    }

    private fun sxrPermute(state: IntArray) {
        repeat(10) {
            sxrQr(state, 0, 4, 8, 12)
            sxrQr(state, 1, 5, 9, 13)
            sxrQr(state, 2, 6, 10, 14)
            sxrQr(state, 3, 7, 11, 15)
            sxrQr(state, 0, 5, 10, 15)
            sxrQr(state, 1, 6, 11, 12)
            sxrQr(state, 2, 7, 8, 13)
            sxrQr(state, 3, 4, 9, 14)
        }
    }

    private fun ld32le(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 255) or
            ((buf[off + 1].toInt() and 255) shl 8) or
            ((buf[off + 2].toInt() and 255) shl 16) or
            ((buf[off + 3].toInt() and 255) shl 24)

    private fun st32le(buf: ByteArray, off: Int, value: Int) {
        buf[off] = (value and 255).toByte()
        buf[off + 1] = ((value ushr 8) and 255).toByte()
        buf[off + 2] = ((value ushr 16) and 255).toByte()
        buf[off + 3] = ((value ushr 24) and 255).toByte()
    }

    private class SxrSponge {
        val state = IntArray(16) { (it + 1) * -0x61c88647 }
        val buf = ByteArray(32)
        var pos = 0

        fun absorb(data: ByteArray, length: Int = data.size, offset: Int = 0) {
            for (i in 0 until length) {
                buf[pos++] = data[offset + i]
                if (pos == 32) {
                    for (j in 0 until 8) state[j] = state[j] xor ld32le(buf, j * 4)
                    sxrPermute(state)
                    pos = 0
                }
            }
        }

        fun final(out: ByteArray, outOffset: Int = 0) {
            buf[pos++] = 0x80.toByte()
            while (pos < 32) buf[pos++] = 0
            for (j in 0 until 8) state[j] = state[j] xor ld32le(buf, j * 4)
            sxrPermute(state)
            for (j in 0 until 8) st32le(out, outOffset + j * 4, state[j])
        }
    }

    private fun sxrPrf(data: ByteArray, length: Int): ByteArray {
        val sponge = SxrSponge()
        sponge.absorb(data, length)
        val out = ByteArray(32)
        sponge.final(out)
        return out
    }

    private fun sxrDerive(certHash: ByteArray, iv: ByteArray): Pair<IntArray, ByteArray> {
        val buf = ByteArray(88)
        System.arraycopy(V3_SXR_LABEL, 0, buf, 0, 8)
        System.arraycopy(certHash, 0, buf, 8, 32)
        System.arraycopy(V3_PEPPER, 0, buf, 40, 32)
        System.arraycopy(iv, 0, buf, 72, 16)
        val seed = sxrPrf(buf, 88)
        val encKey = sxrPrf(seed.copyOf(33).also { it[32] = 1 }, 33)
        val macKey = sxrPrf(seed.copyOf(33).also { it[32] = 2 }, 33)
        val streamState = IntArray(8) { ld32le(encKey, it * 4) }
        return Pair(streamState, macKey)
    }

    private fun sxrBlock(streamState: IntArray, blockCounter: Int, counterWords: IntArray, out: ByteArray) {
        val state = IntArray(16)
        System.arraycopy(SXR_CONSTS, 0, state, 0, 4)
        System.arraycopy(streamState, 0, state, 4, 8)
        state[12] = blockCounter
        System.arraycopy(counterWords, 0, state, 13, 3)
        val working = state.clone()
        sxrPermute(working)
        for (i in 0 until 16) working[i] += state[i]
        for (i in 0 until 16) st32le(out, i * 4, working[i])
    }

    private fun decryptV2(b64Data: String): String? {
        return try {
            val raw = urlSafeB64Decode(b64Data)
            require(raw.size >= 49) { "v2 blob too short (${raw.size})" }
            require(raw[0] == V2_VERSION_BYTE) { "not a v2 blob (version=0x${"%02x".format(raw[0])})" }
            val iv = raw.copyOfRange(1, 17)
            val tag = raw.copyOfRange(raw.size - 32, raw.size)
            val ct = raw.copyOfRange(17, raw.size - 32)
            require(ct.size % 16 == 0) { "ct length ${ct.size} not 16-aligned" }

            val prk = hmacSha256(V2_PRK_LABEL, CTX_HASH)
            val macKey = hmacSha256(prk, V2_MAC_LABEL)
            val encKey = hmacSha256(prk, V2_ENC_LABEL)
            val expectedTag = hmacSha256(macKey, raw.copyOfRange(0, raw.size - 32))
            require(expectedTag.contentEquals(tag)) { "HMAC tag mismatch — wrong cert_hash or tampered payload" }

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(encKey, "AES"), IvParameterSpec(iv))
            val aesOut = cipher.doFinal(ct)
            val plain = ByteArray(aesOut.size)
            for (i in aesOut.indices) {
                val b = aesOut[i].toInt() and 255
                plain[i] = (rotr3(b) xor (CTX_HASH[i % CTX_HASH.size].toInt() and 255)).toByte()
            }
            String(plain, Charsets.UTF_8)
        } catch (e: Exception) {
            println("SportzxCrypto: v2 decryption failed — ${e.message}")
            null
        }
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun urlSafeB64Decode(s: String): ByteArray {
        var std = s.replace('-', '+').replace('_', '/')
        while (std.length % 4 != 0) std += "="
        return Base64.decode(std, 0)
    }

    private fun decryptPrimary(b64Data: String): String? {
        return try {
            val blob = urlSafeB64Decode(b64Data)
            if (blob.size < 5 || blob[0] != MAGIC[0] || blob[1] != MAGIC[1] || blob[2] != MAGIC[2] || blob[3] != MAGIC[3]) {
                throw Exception("Bad magic")
            }
            val n = blob[4].toInt() and 255
            val total = blob.size
            if (total < n + 5) throw Exception("Blob too short")
            val payload = if (n > 0) blob.copyOfRange(5, total - n) else blob.copyOfRange(5, total)
            val tail = if (n > 0) blob.copyOfRange(total - n, total) else ByteArray(0)
            if (payload.isEmpty()) return ""
            if (payload.size % 16 != 0) throw Exception("Payload length not AES-block-aligned")

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
            println("SportzxCrypto: Primary decryption failed — ${e.message}")
            null
        }
    }

    private fun decryptFallback(b64Data: String): String? {
        return try {
            val data = urlSafeB64Decode(b64Data)
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
            println("SportzxCrypto: Fallback decryption failed — ${e.message}")
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
            println("SportzxCrypto: Fallback2 decryption failed — ${e.message}")
            null
        }
    }
}
