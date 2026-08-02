package com.anikoto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

private const val ANIKOTO_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

class AnikotoProvider : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }

    override var mainUrl = "https://anikototv.to"
    override var name = "AniKoto"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "$mainUrl/latest-updated" to "Latest Updated",
        "$mainUrl/most-viewed" to "Most Popular",
        "$mainUrl/status/currently-airing" to "Ongoing",
        "$mainUrl/type/movie" to "Movies"
    )

    private val browserHeaders = mapOf(
        "User-Agent" to ANIKOTO_UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5"
    )

    private fun ajaxHeaders(referer: String) = mapOf(
        "User-Agent" to ANIKOTO_UA,
        "X-Requested-With" to "XMLHttpRequest",
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Referer" to referer
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("${request.data}?page=$page", headers = browserHeaders).document
        val items = doc.select("div.item, div.flw-item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/filter?keyword=$encoded", headers = browserHeaders).document
        return doc.select("div.item, div.flw-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = browserHeaders).document

        val title = doc.selectFirst("#w-info h1.title, h1[itemprop=name], .title[itemprop=name]")
            ?.text()?.trim()
            ?: doc.selectFirst("h1.title")?.text()?.trim()
            ?: return null

        val posterEl = doc.selectFirst("#w-info .poster img, img[itemprop=image], .poster img")
        val poster = posterEl?.let {
            it.attr("data-src").takeIf { s -> s.isNotBlank() } ?: it.attr("src")
        }

        val plot = doc.selectFirst("#w-info .synopsis .content, #w-info .synopsis, .synopsis .content")
            ?.text()

        val genres = doc.select("#w-info a[href*='/genre/'], .meta a[href*='/genre/']")
            .map { it.text().trim() }

        val isMovie = doc.selectFirst("#w-info a[href*='/type/movie']") != null
            || doc.selectFirst(".bmeta")?.text()?.contains("Movie", ignoreCase = true) == true

        val animeId = doc.selectFirst("#watch-main")?.attr("data-id")
            ?: doc.selectFirst("[data-id]")?.attr("data-id")
            ?: Regex("""data-id=["'](\d+)["']""").find(doc.html())?.groupValues?.get(1)

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        if (animeId != null) {
            try {
                val jsonText = app.get(
                    "$mainUrl/ajax/episode/list/$animeId",
                    headers = ajaxHeaders(url),
                    referer = url
                ).text
                val html = jsonResultString(jsonText)
                val epSoup = Jsoup.parse(html)

                epSoup.select("a[data-ids]").forEach { el ->
                    val dataIds = el.attr("data-ids").takeIf { it.isNotBlank() } ?: return@forEach
                    val epNum = el.attr("data-num").toIntOrNull()
                    val hasSub = el.attr("data-sub") == "1"
                    val hasDub = el.attr("data-dub") == "1"

                    val malId = el.attr("data-mal")
                    val slug = el.attr("data-slug")
                    val timestamp = el.attr("data-timestamp")

                    val epName = el.selectFirst(".d-title")?.text()?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?: el.attr("data-jp").takeIf { it.isNotBlank() }
                        ?: "Episode ${epNum ?: ""}"

                    if (hasSub || !hasDub) {
                        subEpisodes.add(
                            newEpisode("anikoto|$url|$dataIds|sub|$malId|$slug|$timestamp") {
                                episode = epNum
                                name = epName
                            }
                        )
                    }
                    if (hasDub) {
                        dubEpisodes.add(
                            newEpisode("anikoto|$url|$dataIds|dub|$malId|$slug|$timestamp") {
                                episode = epNum
                                name = epName
                            }
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }

        if (subEpisodes.isNotEmpty() && dubEpisodes.isEmpty()) {
            doc.select("a[href*='/ep-']").forEachIndexed { i, el ->
                val epName = el.text().trim().takeIf { it.isNotBlank() }
                    ?: "Episode ${i + 1}"
                subEpisodes.add(
                    newEpisode(fixUrl(el.attr("href"))) {
                        episode = i + 1
                        name = epName
                    }
                )
            }
        }

        return newAnimeLoadResponse(title, url, if (isMovie) TvType.AnimeMovie else TvType.Anime) {
            this.posterUrl = poster?.let { fixUrl(it) }
            this.plot = plot
            this.tags = genres
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!data.startsWith("anikoto|")) {
            return resolveFromWatchPage(data, subtitleCallback, callback)
        }

        val parts = data.split("|")
        if (parts.size < 4) {
            return resolveFromWatchPage(data, subtitleCallback, callback)
        }

        val animeUrl = parts[1]
        val serverIds = parts[2]
        val audioType = parts[3]
        val isSub = audioType == "sub"
        val malId = parts.getOrNull(4)
        val slug = parts.getOrNull(5)
        val timestamp = parts.getOrNull(6)
        val referer = "$animeUrl/"

        var found = false

        if (!malId.isNullOrBlank() && !slug.isNullOrBlank() && !timestamp.isNullOrBlank()) {
            try {
                val mapperJson = app.get(
                    "https://mapper.nekostream.site/api/mal/$malId/$slug/$timestamp",
                    headers = ajaxHeaders(referer),
                    referer = referer
                ).text
                val mapperRoot = parseJson<Map<String, Any>>(mapperJson)
                mapperRoot.forEach { (serverName, value) ->
                    if (serverName == "status") return@forEach
                    val serverMap = value as? Map<*, *> ?: return@forEach
                    val typeEntry = serverMap[audioType] as? Map<*, *> ?: return@forEach

                    val linkId = typeEntry["url"] as? String
                    if (!linkId.isNullOrBlank()) {
                        val loaded = resolveServerLink(
                            linkId,
                            referer,
                            audioType,
                            subtitleCallback,
                            callback
                        )
                        if (loaded) found = true
                        return@forEach
                    }

                    (typeEntry["download"] as? Map<*, *>)?.forEach { (_, dl) ->
                        val dlUrl = dl as? String ?: return@forEach
                        if (dlUrl.contains(".m3u8", ignoreCase = true)) {
                            val loaded = resolveM3u8Direct(
                                serverName,
                                dlUrl,
                                referer,
                                subtitleCallback,
                                callback
                            )
                            if (loaded) found = true
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        if (serverIds.isNotBlank()) {
            try {
                val serverListText = app.get(
                    "$mainUrl/ajax/server/list?servers=$serverIds",
                    headers = ajaxHeaders(referer),
                    referer = referer
                ).text
                val serverListHtml = jsonResultString(serverListText)
                val serverListSoup = Jsoup.parse(serverListHtml)

                serverListSoup.select("div.type[data-type]").forEach { typeEl ->
                    val typeAttr = typeEl.attr("data-type")
                    if (typeAttr !in preferredTypes(isSub)) return@forEach
                    typeEl.select("li[data-link-id]").forEach { serverEl ->
                        val linkId = serverEl.attr("data-link-id").takeIf { it.isNotBlank() }
                            ?: return@forEach
                        val loaded = resolveServerLink(
                            linkId,
                            referer,
                            audioType,
                            subtitleCallback,
                            callback
                        )
                        if (loaded) found = true
                    }
                }
            } catch (_: Exception) {
            }
        }

        if (found) return true
        return resolveFromWatchPage(animeUrl, subtitleCallback, callback)
    }

    private fun preferredTypes(isSub: Boolean): List<String> {
        return if (isSub) {
            listOf("sub", "hsub", "h-sub", "raw")
        } else {
            listOf("dub", "adub", "a-dub")
        }
    }

    private suspend fun resolveServerLink(
        linkId: String,
        referer: String,
        audioType: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            if (linkId.startsWith("http")) {
                return resolveEmbedInline(linkId, referer, audioType, subtitleCallback, callback)
            }
            val serverText = app.get(
                "$mainUrl/ajax/server?get=$linkId",
                headers = ajaxHeaders(referer),
                referer = referer
            ).text
            val serverJson = parseJson<ServerInfoResponse>(serverText)
            val embedUrl = serverJson.result?.url?.takeIf { it.isNotBlank() } ?: return false
            resolveEmbedInline(embedUrl, referer, audioType, subtitleCallback, callback)
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun resolveFromWatchPage(
        episodeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = try {
            app.get(episodeUrl, headers = browserHeaders).document
        } catch (_: Exception) {
            return false
        }

        val watchMain = doc.selectFirst("#watch-main")
        val animeId = watchMain?.attr("data-id")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("[data-id]")?.attr("data-id")
            ?: Regex("""data-id=["'](\d+)["']""").find(doc.html())?.groupValues?.get(1)
            ?: return false

        val audioType = if (episodeUrl.contains("/dub/", ignoreCase = true)) "dub" else "sub"

        val epListText = try {
            app.get(
                "$mainUrl/ajax/episode/list/$animeId",
                headers = ajaxHeaders(episodeUrl),
                referer = episodeUrl
            ).text
        } catch (_: Exception) {
            return false
        }
        val epListHtml = jsonResultString(epListText)
        val epDoc = Jsoup.parse(epListHtml)

        val urlSuffix = episodeUrl.substringAfterLast("/")
        val targetEp = epDoc.select("a[href*='$urlSuffix']").first()
            ?: epDoc.select("a[href*='/ep-']").first()
            ?: return false

        val serverIds = targetEp.attr("data-ids").takeIf { it.isNotBlank() } ?: return false

        val serverListText = try {
            app.get(
                "$mainUrl/ajax/server/list?servers=$serverIds",
                headers = ajaxHeaders("$episodeUrl/")
            ).text
        } catch (_: Exception) {
            return false
        }
        val serverListHtml = jsonResultString(serverListText)
        val serverDoc = Jsoup.parse(serverListHtml)

        val preferredTypes = if (audioType == "sub") {
            listOf("sub", "hsub", "h-sub", "raw")
        } else {
            listOf("dub", "adub", "a-dub")
        }

        val linkIds = mutableListOf<Pair<String, String>>()
        serverDoc.select("div.type[data-type]").forEach { typeEl ->
            val typeAttr = typeEl.attr("data-type")
            if (typeAttr in preferredTypes) {
                typeEl.select("li[data-link-id]").forEach { serverEl ->
                    val linkId = serverEl.attr("data-link-id").takeIf { it.isNotBlank() }
                        ?: return@forEach
                    val serverName = serverEl.text()?.trim()?.ifBlank { "Server" } ?: "Server"
                    linkIds.add(serverName to linkId)
                }
            }
        }

        if (linkIds.isEmpty()) return false

        var found = false
        for ((_, linkId) in linkIds) {
            val loaded = resolveServerLink(
                linkId,
                "$episodeUrl/",
                audioType,
                subtitleCallback,
                callback
            )
            if (loaded) found = true
        }

        return found
    }

    private suspend fun resolveEmbedInline(
        url: String,
        referer: String,
        audioType: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val normalizedUrl = when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> url
        }
        if (normalizedUrl.contains(".m3u8", ignoreCase = true)) {
            return resolveM3u8Direct(
                "Stream",
                normalizedUrl,
                referer,
                subtitleCallback,
                callback
            )
        }
        val domain = Regex("""https?://([^/]+)""").find(normalizedUrl)?.groupValues?.get(1) ?: ""
        val isMegaPlayDomain = domain.contains("megaplay", ignoreCase = true) ||
            domain.contains("vidwish", ignoreCase = true) ||
            domain.contains("vidtube", ignoreCase = true)

        if (isMegaPlayDomain) {
            return resolveMegaPlayInline(
                normalizedUrl,
                referer,
                domain,
                audioType,
                subtitleCallback,
                callback
            )
        }

        if (domain.contains("mewcdn", ignoreCase = true)) {
            return resolveMewcdnInline(
                normalizedUrl,
                referer,
                domain,
                subtitleCallback,
                callback
            )
        }

        return try {
            loadExtractor(normalizedUrl, referer, subtitleCallback, callback)
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun resolveM3u8Direct(
        serverName: String,
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val host = Regex("""https?://([^/]+)""").find(url)?.groupValues?.get(1) ?: return false
        val headers = mapOf(
            "User-Agent" to ANIKOTO_UA,
            "Accept" to "*/*",
            "Referer" to referer
        )
        val generated = M3u8Helper.generateM3u8(serverName, url, host, headers = headers)
        if (generated.isNotEmpty()) {
            generated.forEach(callback)
        } else {
            callback(
                newExtractorLink(serverName, serverName, url, ExtractorLinkType.M3U8) {
                    this.referer = referer
                    this.headers = headers
                }
            )
        }
        return true
    }

    private suspend fun fetchSources(
        host: String,
        streamId: String,
        type: String,
        ref: String
    ): String? {
        try {
            val jsonText = app.get(
                "$host/stream/getSources?id=$streamId&type=$type",
                headers = mapOf("Referer" to ref),
                referer = ref
            ).text
            if (jsonText.isNotBlank()) return jsonText
        } catch (_: Exception) {
        }
        return try {
            app.get(
                "$host/stream/getSourcesNew?id=$streamId&id=$streamId&type=$type&type=$type",
                headers = mapOf("Referer" to ref),
                referer = ref
            ).text
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun resolveMewcdnInline(
        url: String,
        referer: String,
        domain: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val host = if (domain.startsWith("http")) domain else "https://$domain"
        val headers = mapOf(
            "User-Agent" to ANIKOTO_UA,
            "Referer" to referer
        )
        return try {
            val fragment = url.substringAfter("#", "").ifBlank { return false }
            val decoded = java.util.Base64.getUrlDecoder()
                .decode(fragment.toByteArray())
                .toString(Charsets.UTF_8)
            val source = Regex("""["']file["']\s*:\s*["']([^"']+)["']""")
                .find(decoded)?.groupValues?.get(1)
                ?: decoded.takeIf { it.startsWith("http") }
                ?: return false

            val generated = M3u8Helper.generateM3u8(
                "Mewcdn",
                source,
                host,
                headers = headers
            )
            if (generated.isNotEmpty()) {
                generated.forEach(callback)
            } else {
                callback(
                    newExtractorLink("Mewcdn", "Mewcdn", source, ExtractorLinkType.M3U8) {
                        this.referer = "$host/"
                        this.headers = headers
                    }
                )
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun resolveMegaPlayInline(
        url: String,
        referer: String,
        domain: String,
        audioType: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val host = if (domain.startsWith("http")) domain else "https://$domain"
        val serverName = when {
            domain.contains("vidwish", ignoreCase = true) -> "Vidwish"
            domain.contains("vidtube", ignoreCase = true) -> "Vidtube"
            else -> "MegaPlay"
        }
        val type = if (url.contains("/dub", ignoreCase = true)) "dub" else "sub"

        val pageHeaders = mapOf(
            "User-Agent" to ANIKOTO_UA,
            "Referer" to referer
        )

        val playbackHeaders = mapOf(
            "User-Agent" to ANIKOTO_UA,
            "Accept" to "*/*",
            "Origin" to host,
            "Referer" to "$host/"
        )

        val doc = app.get(url, headers = pageHeaders).document
        val playerEl = doc.selectFirst("#megaplay-player")
        val streamId = playerEl?.attr("data-id")
            ?: playerEl?.attr("data-realid")
            ?: Regex("""/stream/s-\d+/(\d+)/""").find(url)?.groupValues?.get(1)
            ?: Regex("""/stream/([A-Za-z0-9_-]+)/""").find(url)?.groupValues?.get(1)
            ?: return false

        val jsonText = fetchSources(host, streamId, type, url) ?: return false

        val root = try {
            parseJson<SourcesResponse>(jsonText)
        } catch (_: Exception) {
            null
        } ?: return false

        val m3u8 = when (val s = root.sources) {
            is Map<*, *> -> (s["file"] as? String)
            else -> null
        }
        if (m3u8.isNullOrBlank()) return false

        val generated = M3u8Helper.generateM3u8(
            serverName,
            m3u8,
            host,
            headers = playbackHeaders
        )
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
            root.tracks?.forEach { track ->
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

        return true
    }

    private fun jsonResultString(json: String): String {
        return try {
            val response = parseJson<AjaxResponse>(json)
            if (response.status == 200) {
                response.result?.toString() ?: ""
            } else ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun jsonResultUrl(json: String): String? {
        return try {
            val response = parseJson<AjaxResponse>(json)
            if (response.status == 200) {
                (response.result as? Map<*, *>)?.get("url") as? String
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        var titleEl = selectFirst("a.name.d-title")
            ?: selectFirst("a[title]")
            ?: selectFirst("a[href*='/watch/']")
            ?: return null

        var href = titleEl.attr("href")
        if (href.isBlank()) {
            href = selectFirst("div.poster a, a")?.attr("href") ?: ""
        }
        if (href.isBlank()) return null

        val title = titleEl.text().trim().takeIf { it.isNotBlank() }
            ?: titleEl.attr("title").trim().takeIf { it.isNotBlank() }
            ?: return null

        val cleanHref = fixUrl(Regex("/ep-\\d+$").replace(href, ""))

        val posterEl = selectFirst("div.poster img, img")
        val poster = posterEl?.let {
            it.attr("data-src").takeIf { s -> s.isNotBlank() } ?: it.attr("src")
        }

        val typeText = selectFirst(".fd-infor .tick-item.tick-type, .item-type, .tick-type")
            ?.text()
            ?: selectFirst(".type")?.ownText()?.trim()

        val type = if (typeText?.contains("Movie", ignoreCase = true) == true) {
            TvType.AnimeMovie
        } else {
            TvType.Anime
        }

        val metaText = select(".meta, .info, .type, .right").text()
        val hasDub = selectFirst(".dub, i.dub, .fa-microphone") != null ||
            metaText.contains("Dub", ignoreCase = true)
        val hasSub = selectFirst(".sub, i.sub, .fa-closed-captioning") != null ||
            metaText.contains("Sub", ignoreCase = true) ||
            !hasDub

        return newAnimeSearchResponse(title, cleanHref, type) {
            this.posterUrl = poster?.let { this@AnikotoProvider.fixUrl(it) }
            addDubStatus(hasDub, hasSub)
        }
    }

    data class AjaxResponse(
        @JsonProperty("status") val status: Int? = null,
        @JsonProperty("result") val result: Any? = null
    )

    data class ServerInfoResponse(
        @JsonProperty("status") val status: Int? = null,
        @JsonProperty("result") val result: ServerResult? = null
    )

    data class ServerResult(
        @JsonProperty("url") val url: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SourcesResponse(
        @JsonProperty("sources") val sources: Any? = null,
        @JsonProperty("tracks") val tracks: List<Track>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Track(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null
    )
}
