package com.cncverse

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.json.JSONTokener
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

        // Upstream skips `player.*` frames unless they are embeds, then takes the first one.
        val iframeSrc = iframeSrcRegex.findAll(destinationHtml)
            .map { unescapeUrl(it.groupValues[1].trim()) }
            .filter { it.startsWith("http") }
            .filter { !it.contains("player.", ignoreCase = true) || it.contains("embed", ignoreCase = true) }
            .firstOrNull() ?: return

        if (iframeSrc.contains("vimeo.com", ignoreCase = true)) {
            extractVimeo(iframeSrc, redirectTarget, callback)
            return
        }

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

    /**
     * Ported from upstream TeamsToday: resolves Vimeo player pages by reading the
     * inline `window.playerConfig` JSON and picking the first usable CDN URL.
     */
    private suspend fun extractVimeo(
        embedUrl: String,
        pageReferer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(embedUrl, referer = pageReferer).text
        val configIndex = html.indexOf("window.playerConfig")
        if (configIndex < 0) return
        val braceIndex = html.indexOf('{', configIndex)
        if (braceIndex < 0) return

        val config = runCatching {
            JSONTokener(html.substring(braceIndex)).nextValue() as? JSONObject
        }.getOrNull() ?: return

        val hls = config.optJSONObject("request")
            ?.optJSONObject("files")
            ?.optJSONObject("hls") ?: return
        val cdns = hls.optJSONObject("cdns") ?: return
        val defaultCdn = hls.optString("default_cdn")

        val cdnNames = buildList {
            add(defaultCdn)
            cdns.keys().asSequence()
                .filter { it != defaultCdn }
                .forEach { add(it) }
        }

        for (cdn in cdnNames) {
            val c = cdns.optJSONObject(cdn) ?: continue
            val rawUrl = c.optString("avc_url").ifBlank { c.optString("url") }
            if (!rawUrl.startsWith("http")) continue
            newExtractorLink(name, name, rawUrl, ExtractorLinkType.M3U8) {
                this.referer = "https://player.vimeo.com/"
            }?.let(callback)
            return
        }
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