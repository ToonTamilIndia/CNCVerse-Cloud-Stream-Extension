package com.animesuge.provider

import android.util.Base64
import com.cncverse.BuildConfig
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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
        val stream = MegaPlayResolver.resolveStream(url, referer) ?: return
        val audioLabel = MegaPlayResolver.audioTypeFromUrl(url)?.uppercase() ?: "SUB"
        val streamHost = stream.embedBase.removePrefix("https://").removePrefix("http://").substringBefore(".")
        val streamSource = when (streamHost.lowercase()) {
            "megaplay" -> "MegaPlay"
            "vidwish" -> "Vidwish"
            "vidtube" -> "Vidtube"
            else -> streamHost.replaceFirstChar { it.uppercase() }
        }
        MegaPlayResolver.emitLinks(
            source = streamSource,
            label = "$streamSource $audioLabel",
            m3u8 = stream.m3u8,
            referer = "${stream.embedBase}/",
            subtitles = stream.subtitles,
            subtitleCallback = subtitleCallback,
            callback = callback
        )
    }
}

/**
 * Unwraps the encrypted `enc` payload returned by MegaPlay's `getSources`
 * endpoint. The key/IV seeds are published in the player's
 * `/lib/newclient.min.js`; if that fetch fails (or the pair is missing) the
 * fallback seeds from BuildConfig still decrypt current responses.
 */
internal object MegaPlayCipher {
    private const val TAG = "MegaPlay"
    private val FALLBACK_KEY_SEED = BuildConfig.MEGAPLAY_FALLBACK_KEY_SEED
    private val FALLBACK_IV_SEED = BuildConfig.MEGAPLAY_FALLBACK_IV_SEED
    private val keyPairRegex = Regex("[A-Za-z]\\w*=\"([^\"]{16})\",[A-Za-z]\\w*=\"([^\"]{16})\"")
    private val fileRegex = Regex("\"file\"\\s*:\\s*\"([^\"]+)\"")

    @Volatile
    private var cachedSeeds: Pair<String, String>? = null

    suspend fun resolveEncStreamUrl(enc: String, baseUrl: String): String? {
        for ((keySeed, ivSeed) in keySeedCandidates(baseUrl)) {
            val plain = decryptToken(enc, keySeed, ivSeed) ?: continue
            fileRegex.find(plain)?.groupValues?.get(1)?.let { return it }
        }
        return null
    }

    private suspend fun keySeedCandidates(baseUrl: String): List<Pair<String, String>> {
        cachedSeeds?.let { return listOf(it, fallback()) }
        val fetched = try {
            val js = app.get("$baseUrl/lib/newclient.min.js", timeout = 10000L).text
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
    } catch (e: Exception) {
        Log.d(TAG, "token decrypt failed: ${e.message}")
        null
    }
}

object MegaPlayResolver {
    private const val TAG = "MegaPlay"
    private val mapper = ObjectMapper()
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    class MegaPlayStream(
        val m3u8: String,
        val subtitles: List<Pair<String, String>>,
        val embedBase: String
    )

    fun audioTypeFromUrl(url: String): String? =
        Regex("/(dub|sub)(?:[/?#]|$)").find(url)?.groupValues?.get(1)

    suspend fun resolveStream(embedUrl: String, referer: String?): MegaPlayStream? {
        val host = Regex("https?://([^/]+)").find(embedUrl)?.groupValues?.get(1) ?: return null
        val pageHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to (referer ?: "https://$host/")
        )
        val pageHtml = try {
            app.get(embedUrl, headers = pageHeaders).text
        } catch (e: Exception) {
            Log.d(TAG, "embed page failed for $host: ${e.message}")
            return null
        }

        val streamId = Regex("data-id=[\"'](\\d+)").find(pageHtml)?.groupValues?.get(1)
            ?: Regex("data-realid=[\"'](\\d+)").find(pageHtml)?.groupValues?.get(1)
            ?: Regex("/stream/s-\\d+/(\\d+)/").find(embedUrl)?.groupValues?.get(1)
            ?: return null
        val audioType = audioTypeFromUrl(embedUrl)
            ?: Regex("type\\s*:\\s*['\"](dub|sub)['\"]").find(pageHtml)?.groupValues?.get(1)
            ?: "sub"
        val altHost = Regex("data-domain=[\"']([^\"']+)[\"']").find(pageHtml)?.groupValues?.get(1)
        val hosts = listOfNotNull(host, altHost?.takeIf { it != host }).distinct()

        for (apiHost in hosts) {
            val base = "https://$apiHost"
            val ajaxHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "*/*",
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to base,
                "Referer" to embedUrl
            )
            for (endpoint in listOf("getSourcesNew", "getSources")) {
                val url = "$base/stream/$endpoint?id=$streamId&type=$audioType"
                val root = fetchJson(url, ajaxHeaders) ?: continue
                val m3u8 = extractStream(root, base) ?: continue
                return MegaPlayStream(m3u8, parseSubtitleTracks(root), base)
            }
        }
        return null
    }

    private suspend fun extractStream(root: JsonNode, base: String): String? {
        val sources = root.get("sources")
        val plain = when {
            sources == null || sources.isNull -> null
            sources.isObject -> sources.get("file")?.asText()
            sources.isArray && sources.size() > 0 -> sources.get(0)?.get("file")?.asText()
            else -> null
        }
        if (!plain.isNullOrBlank()) return plain
        val enc = root.get("enc")?.takeIf { !it.isNull }?.asText() ?: return null
        return MegaPlayCipher.resolveEncStreamUrl(enc, base)
    }

    private fun parseSubtitleTracks(root: JsonNode): List<Pair<String, String>> {
        val subs = mutableListOf<Pair<String, String>>()
        val tracks = root.get("tracks")
        if (tracks == null || !tracks.isArray) return subs
        for (element in tracks) {
            val kind = element.get("kind")?.asText() ?: continue
            if (kind != "captions" && kind != "subtitles") continue
            val file = element.get("file")?.asText()?.takeIf { it.isNotBlank() } ?: continue
            val label = element.get("label")?.asText() ?: "English"
            subs.add(label to file)
        }
        return subs
    }

    private suspend fun fetchJson(url: String, headers: Map<String, String>): JsonNode? = try {
        mapper.readTree(app.get(url, headers = headers, timeout = 15000L).text)
    } catch (e: Exception) {
        Log.d(TAG, "sources request failed: ${e.message}")
        null
    }

    private val TOKEN_KEY = BuildConfig.MEGAPLAY_TOKEN_KEY
    private const val TOKEN_LIFETIME_SECONDS = 604800L
    private val hexIdsRegex = Regex("/([a-f0-9]{32})/([a-f0-9]{32})/", RegexOption.IGNORE_CASE)

    private fun b64url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    fun signUrl(url: String): String {
        val m = hexIdsRegex.find(url) ?: return url
        val expires = System.currentTimeMillis() / 1000 + TOKEN_LIFETIME_SECONDS
        val payload = "$expires|${m.groupValues[1].lowercase()}/${m.groupValues[2].lowercase()}"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(TOKEN_KEY.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signature = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        val token = b64url(payload.toByteArray(Charsets.UTF_8)) + "." + b64url(signature)
        val sep = if (url.contains('?')) "&" else "?"
        return "$url${sep}token=$token"
    }

    private data class VariantEntry(val url: String, val quality: Int?)

    private fun parseVariants(masterUrl: String, masterText: String): List<VariantEntry> {
        val base = masterUrl.substringBefore('?').substringBeforeLast('/') + "/"
        val out = mutableListOf<VariantEntry>()
        val lines = masterText.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val quality = Regex("RESOLUTION=(\\d+)x(\\d+)").find(line)?.groupValues?.get(2)?.toIntOrNull()
                var j = i + 1
                while (j < lines.size && (lines[j].isBlank() || lines[j].startsWith("#"))) j++
                if (j < lines.size) {
                    val uri = lines[j].trim()
                    if (uri.isNotEmpty()) {
                        out.add(VariantEntry(if (uri.startsWith("http")) uri else base + uri, quality))
                    }
                    i = j
                }
            }
            i++
        }
        return out
    }

    suspend fun emitLinks(
        source: String,
        label: String,
        m3u8: String,
        referer: String,
        subtitles: List<Pair<String, String>>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer
        )
        val signedMaster = signUrl(m3u8)
        val masterText = try {
            app.get(signedMaster, headers = playHeaders, timeout = 15000L).text
        } catch (e: Exception) {
            Log.d(TAG, "master playlist fetch failed: ${e.message}")
            null
        }
        val variants = masterText?.let { parseVariants(m3u8, it) } ?: emptyList()

        var found = false
        if (variants.isNotEmpty()) {
            for (v in variants) {
                val suffix = v.quality?.let { "${it}p" } ?: ""
                callback(
                    newExtractorLink(
                        source,
                        if (suffix.isEmpty()) label else "$label $suffix",
                        signUrl(v.url),
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = referer
                        v.quality?.let { this.quality = it }
                        this.headers = playHeaders
                    }
                )
                found = true
            }
        } else {
            callback(
                newExtractorLink(source, label, signedMaster, ExtractorLinkType.M3U8) {
                    this.referer = referer
                    this.headers = playHeaders
                }
            )
            found = true
        }

        for ((subLabel, subUrl) in subtitles) {
            subtitleCallback(
                newSubtitleFile(subLabel, subUrl) {
                    this.headers = playHeaders
                }
            )
        }
        return found
    }
}
