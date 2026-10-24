package com.cncverse.radioindia

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.nio.charset.StandardCharsets

class RadioIndiaProvider : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }

    override var mainUrl = "https://www.radioindia.in"
    override var name = "Radio India"
    override val hasMainPage = true
    override var lang = "ta"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Live)

    override val mainPage = mainPageOf(
        "$mainUrl" to "Top Stations",
        "$mainUrl/radio/discover-180" to "Recommended Stations",
        "$mainUrl/genre/local" to "Local",
        "$mainUrl/genre/oldies" to "Oldies",
        "$mainUrl/genre/religious-spirituality" to "Religious & Spirituality",
        "$mainUrl/genre/talk" to "Talk",
        "$mainUrl/genre/christian" to "Christian",
        "$mainUrl/genre/folk" to "Folk",
        "$mainUrl/genre/indian-music" to "Indian Music",
        "$mainUrl/genre/bollywood" to "Bollywood",
        "$mainUrl/genre/90s" to "90s",
        "$mainUrl/genre/80s" to "80s",
        "$mainUrl/radio/tamil-nadu" to "Tamil Nadu",
        "$mainUrl/radio/kerala" to "Kerala"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data).document
        val home = document.select("a.radio-card__link, a.radio-list-row").mapNotNull {
            it.toSearchResponse()
        }
        return newHomePageResponse(
            HomePageList(request.name, home, isHorizontalImages = true)
        )
    }

    private fun Element.toSearchResponse(): LiveSearchResponse? {
        val title = this.selectFirst(".radio-card__name, .radio-list-row__name")?.text()
            ?: this.attr("title").takeIf { it.isNotBlank() }
            ?: return null

        val href = fixUrlNull(this.attr("href")) ?: return null

        val imgEl = this.selectFirst("img.radio-card__logo, img.radio-list-row__logo")
        val posterUrl = imgEl?.attr("data-src")?.takeIf { it.isNotBlank() } ?: imgEl?.attr("src")

        return newLiveSearchResponse(
            name = title,
            url = href,
            type = TvType.Live
        ) {
            this.posterUrl = fixUrlNull(posterUrl)
            this.lang = "hi"
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search?q=$query").document
        return document.select("a.radio-card__link").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.radio-info__name")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?.split(":")?.firstOrNull()?.trim()
            ?: "Unknown Radio"

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")

        val description = document.selectFirst(".radio-info__description")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        return newLiveStreamLoadResponse(
            name = title,
            url = url,
            dataUrl = url
        ) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = description
        }
    }

    // the genk function translated to Kotlin
    private fun genk(str: String): String {
        var hex = ""
        var j = 0
        for (i in 0 until 32) {
            val charCode = str[j].code
            hex += charCode.toString(16)
            j++
            if (j >= str.length) j = 0
        }
        return hex
    }

    private fun parseHex(hexStr: String): ByteArray {
        val len = hexStr.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hexStr[i], 16) shl 4)
                    + Character.digit(hexStr[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun decryptMytuner(cipherTextBase64: String, ivHex: String, timestampData: String): String? {
        try {
            val cipherData = Base64.decode(
                cipherTextBase64.replace('-', '+').replace('_', '/'),
                Base64.DEFAULT
            )

            val keyHex = genk(timestampData)
            val keyBytes = parseHex(keyHex)
            val ivBytes = parseHex(ivHex)

            val secretKey = SecretKeySpec(keyBytes, "AES")
            val ivSpec = IvParameterSpec(ivBytes)

            val cipher = Cipher.getInstance("AES/CFB/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

            val decryptedBytes = cipher.doFinal(cipherData)
            return String(decryptedBytes, StandardCharsets.UTF_8).trimEnd('\u0000').trim()
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        val timestampData = document.selectFirst("#last-update")?.attr("data-timestamp")
            ?: throw Exception("Timestamp not found")

        val streamsJson = document.selectFirst("#radio-streams-json")?.data()
            ?: throw Exception("Playlists not found")

        val itemRegex = Regex("\"cipher\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"iv\"\\s*:\\s*\"([^\"]+)\"")
        var found = false
        itemRegex.findAll(streamsJson).forEach { m ->
            val cipher = m.groupValues[1]
            val iv = m.groupValues[2]

            if (!cipher.isBlank() && !iv.isBlank()) {
                val decryptedUrl = decryptMytuner(cipher, iv, timestampData)
                if (decryptedUrl != null) {
                    found = true
                    callback(
                        newExtractorLink(
                            name = this.name,
                            source = this.name,
                            url = decryptedUrl,
                            type = if (decryptedUrl.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }
        }
        return found
    }
}
