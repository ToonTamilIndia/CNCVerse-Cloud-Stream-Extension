package com.anikoto

import com.fasterxml.jackson.annotation.JsonProperty
import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val MP_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

class Vidwish : MegaPlay() {
    override val name = "Vidwish"
    override val mainUrl = "https://vidwish.live"
}

class Vidtube : MegaPlay() {
    override val name = "Vidtube"
    override val mainUrl = "https://vidtube.site"
}

open class MegaPlay : ExtractorApi() {
    override val name = "MegaPlay"
    override val mainUrl = "https://megaplay.buzz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extractMegaPlayUrl(url, referer, mainUrl, name, subtitleCallback, callback)
    }

    companion object {
        suspend fun extractMegaPlayUrl(
            url: String,
            referer: String?,
            host: String,
            serverName: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ) {
            val playbackHeaders = mapOf(
                "User-Agent" to MP_UA,
                "Accept" to "*/*",
                "Origin" to host,
                "Referer" to "$host/",
            )

            val pageHeaders = mapOf(
                "User-Agent" to MP_UA,
                "Referer" to (referer ?: host)
            )

            val doc = app.get(url, headers = pageHeaders).document
            val playerEl = doc.selectFirst("#megaplay-player")
            val streamId = playerEl?.attr("data-realid")?.takeIf { it.isNotBlank() }
                ?: playerEl?.attr("data-id")
                ?: Regex("""/stream/s-\d+/(\d+)/""").find(url)?.groupValues?.get(1)
                ?: Regex("""/stream/([A-Za-z0-9_-]+)/""").find(url)?.groupValues?.get(1)
                ?: return

            val type = if (url.contains("/dub", ignoreCase = true)) "dub" else "sub"

            val ajaxHeaders = mapOf(
                "User-Agent" to MP_UA,
                "Accept" to "*/*",
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to host,
                "Referer" to url,
            )

            val jsonText = try {
                app.get(
                    "$host/stream/getSources?id=$streamId&type=$type",
                    headers = ajaxHeaders,
                    referer = url
                ).text
            } catch (_: Exception) {
                null
            } ?: try {
                app.get(
                    "$host/stream/getSourcesNew?id=$streamId&id=$streamId&type=$type&type=$type",
                    headers = ajaxHeaders,
                    referer = url
                ).text
            } catch (_: Exception) {
                null
            } ?: return

            val root = try {
                parseJson<MegaPlayResponse>(jsonText)
            } catch (_: Exception) {
                null
            }
            // MegaPlay now answers with an encrypted `enc` blob instead of a
            // plain `sources.file`. Without unwrapping it there is no stream
            // URL and the provider reports "no links found". Older mirrors may
            // still return `sources` as an array, so fall back to a text scan.
            val m3u8 = root?.sources?.file?.takeIf { it.isNotBlank() }
                ?: root?.enc?.let { MegaPlayCipher.resolveEncStreamUrl(it, host) }
                ?: Regex("\"enc\"\\s*:\\s*\"([^\"]+)\"").find(jsonText)
                    ?.groupValues?.get(1)
                    ?.let { MegaPlayCipher.resolveEncStreamUrl(it, host) }
                ?: Regex("\"file\"\\s*:\\s*\"([^\"]+)\"").find(jsonText)?.groupValues?.get(1)
            if (m3u8.isNullOrBlank()) return

            val generated = M3u8Helper.generateM3u8(serverName, m3u8, host, headers = playbackHeaders)
            if (generated.isNotEmpty()) {
                generated.forEach(callback)
            } else {
                callback(
                    newExtractorLink(serverName, serverName, m3u8, ExtractorLinkType.M3U8) {
                        this.referer = "$host/"
                        this.headers = playbackHeaders
                    }
                )
            }

            try {
                root?.tracks?.forEach { track ->
                    val kind = track.kind ?: return@forEach
                    if (kind != "captions" && kind != "subtitles") return@forEach
                    val file = track.file ?: return@forEach
                    val label = track.label ?: "Unknown"
                    subtitleCallback(
                        newSubtitleFile(label, file) {
                            this.headers = playbackHeaders
                        }
                    )
                }
            } catch (_: Exception) {
            }
        }
    }

    data class MegaPlayResponse(
        @JsonProperty("sources") val sources: Sources? = null,
        @JsonProperty("tracks") val tracks: List<Track> = emptyList(),
        @JsonProperty("enc") val enc: String? = null
    )

    data class Sources(
        @JsonProperty("file") val file: String? = null
    )

    data class Track(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null
    )
}

/**
 * Unwraps the encrypted `enc` payload returned by MegaPlay's `getSources`
 * endpoint. The key/IV seeds are published in the player's
 * `/lib/newclient.min.js`; if that fetch fails (or the pair is missing) the
 * static fallback seeds below still decrypt current responses.
 */
internal object MegaPlayCipher {
    private const val FALLBACK_KEY_SEED = "i?LMTAx0Q6,:}50U"
    private const val FALLBACK_IV_SEED = "W0;27ToaUpl_P%'c"
    private val keyPairRegex = Regex("[A-Za-z]\\w*=\"([^\"]{16})\",[A-Za-z]\\w*=\"([^\"]{16})\"")
    private val fileRegex = Regex("\"file\"\\s*:\\s*\"([^\"]+)\"")

    @Volatile
    private var cachedSeeds: Pair<String, String>? = null

    suspend fun resolveEncStreamUrl(enc: String, baseUrl: String): String? {
        for ((keySeed, ivSeed) in keySeedCandidates(baseUrl)) {
            val plain = decryptToken(enc, keySeed, ivSeed) ?: continue
            fileRegex.find(plain)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private suspend fun keySeedCandidates(baseUrl: String): List<Pair<String, String>> {
        cachedSeeds?.let { return listOf(it, fallback()) }
        val fetched = try {
            val js = app.get("$baseUrl/lib/newclient.min.js", timeout = 10L).text
            keyPairRegex.find(js)?.let { m ->
                Pair(m.groupValues[1], m.groupValues[2]).also { cachedSeeds = it }
            }
        } catch (_: Exception) {
            null
        }
        return listOfNotNull(fetched, fallback())
    }

    private fun fallback(): Pair<String, String> = FALLBACK_KEY_SEED to FALLBACK_IV_SEED

    private fun decryptToken(enc: String, keySeed: String, ivSeed: String): String? = try {
        var b64 = enc.replace('-', '+').replace('_', '/')
        while (b64.length % 4 != 0) b64 += "="
        val cipherBytes = Base64.decode(b64, Base64.DEFAULT)

        val keyBytes = ByteArray(32)
        val seedBytes = keySeed.toByteArray(Charsets.UTF_8)
        System.arraycopy(seedBytes, 0, keyBytes, 0, minOf(32, seedBytes.size))

        val ivBytes = ByteArray(16)
        val ivSeedBytes = ivSeed.toByteArray(Charsets.UTF_8)
        System.arraycopy(ivSeedBytes, 0, ivBytes, 0, minOf(16, ivSeedBytes.size))

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
        String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }
}
