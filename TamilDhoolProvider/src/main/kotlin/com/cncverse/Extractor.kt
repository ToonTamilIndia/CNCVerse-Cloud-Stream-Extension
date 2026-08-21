package com.cncverse

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI


open class Dailymotion : ExtractorApi() {
    override val mainUrl = "https://www.dailymotion.com"
    override val name = "Dailymotion"
    override val requiresReferer = false
    private val baseUrl = "https://www.dailymotion.com"

    @Suppress("RegExpSimplifiable")
    private val videoIdRegex = "^[kx][a-zA-Z0-9]+$".toRegex()

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = getEmbedUrl(url) ?: return
        val id = getVideoId(embedUrl) ?: return
        val metaDataUrl = "$baseUrl/player/metadata/video/$id"
        val response = app.get(metaDataUrl, referer = embedUrl).text
        val qualityUrlRegex = Regex(""""url"\s*:\s*"([^"]+)"""")
        val subtitlesRegex = Regex(""""subtitles"\s*:\s*\{[^}]*"data"\s*:\s*(\[[^]]*])""")

        val urls = qualityUrlRegex.findAll(response)
            .map { it.groupValues[1] }
            .toList().filter { it.contains(".m3u8") }

        urls.forEach { videoUrl ->
            getStream(videoUrl, this.name, callback)
        }

        val subtitlesMatches = subtitlesRegex.findAll(response).map { it.groupValues[1] }.toList()
        subtitlesMatches.forEach { subtitleJson ->
            val subRegex = Regex("""\{\s*"label"\s*:\s*"([^"]+)",\s*"urls"\s*:\s*\["([^"]+)"""")
            subRegex.findAll(subtitleJson).forEach { match ->
                val label = match.groupValues[1]
                val subUrl = match.groupValues[2]
                subtitleCallback(SubtitleFile(label, subUrl))
            }
        }
    }

    private fun getEmbedUrl(url: String): String? {
        if (url.contains("/embed/") || url.contains("/video/")) return url
        if (url.contains("geo.dailymotion.com")) {
            val videoId = url.substringAfter("video=")
            return "$baseUrl/embed/video/$videoId"
        }
        return null
    }


    private fun getVideoId(url: String): String? {
        val path = URI(url).path
        val id = path.substringAfter("/video/")
        return if (id.matches(videoIdRegex)) id else null
    }

    private suspend fun getStream(
        streamLink: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ) {
        return generateM3u8(name, streamLink, "").forEach(callback)
    }
}

class TeamsToday : ExtractorApi() {
    override val mainUrl = "https://teamstoday.com"
    override val name = "TeamsToday"
    override val requiresReferer = false
    private val tamildhoolReferer = "https://www.tamildhool.tech/"

    private val metaRefreshRegex = Regex("url=([^\"'\\s>]+)", RegexOption.IGNORE_CASE)
    private val jwFileRegex = Regex("""file\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val iframeSrcRegex = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val rawM3u8Regex = Regex("""https?:\\?/\\?/[^\s"'<>\\]+\.m3u8""", RegexOption.IGNORE_CASE)

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val entryReferer = if (referer.isNullOrBlank()) tamildhoolReferer else referer

        val teamstodayHtml = app.get(url, referer = entryReferer).text
        val redirectTarget = metaRefreshRegex.find(teamstodayHtml)
            ?.groupValues?.get(1)?.trim()?.let { unescapeUrl(it) }
            ?.takeIf { it.startsWith("http") } ?: return

        val destinationHtml = app.get(redirectTarget, referer = url).text
        val directStream = findStreamUrl(destinationHtml)
        if (directStream != null) {
            newExtractorLink(name, name, directStream, ExtractorLinkType.M3U8) {
                this.referer = redirectTarget
            }?.let(callback)
            return
        }

        val iframeSrc = iframeSrcRegex.find(destinationHtml)
            ?.groupValues?.get(1)?.trim()?.let { unescapeUrl(it) }
            ?.takeIf { it.startsWith("http") } ?: return

        val iframeHtml = app.get(iframeSrc, referer = redirectTarget).text
        val iframeStream = findStreamUrl(iframeHtml)
        if (iframeStream != null) {
            newExtractorLink(name, name, iframeStream, ExtractorLinkType.M3U8) {
                this.referer = iframeSrc
            }?.let(callback)
            return
        }

        loadExtractor(iframeSrc, redirectTarget, subtitleCallback, callback)
    }

    private fun findStreamUrl(html: String): String? {
        val jwFile = jwFileRegex.find(html)?.groupValues?.get(1)?.let { unescapeUrl(it) }
            ?.takeIf { it.startsWith("http") && it.contains(".m3u8") }
        if (jwFile != null) return jwFile

        val raw = rawM3u8Regex.find(html)?.value ?: return null
        return unescapeUrl(raw)
    }

    private fun unescapeUrl(raw: String): String {
        return raw.trim().replace("\\/", "/").replace("\\u002F", "/")
    }
}