package com.anikoto

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

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
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5"
    )

    private fun ajaxHeaders(referer: String) = mapOf(
        "User-Agent" to USER_AGENT,
        "X-Requested-With" to "XMLHttpRequest",
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Referer" to referer
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}?page=$page"
        val doc = app.get(url, headers = browserHeaders).document
        val items = doc.select("div.item, div.flw-item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/filter?keyword=$encoded", headers = browserHeaders).document
        return doc.select("div.item, div.flw-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val animeUrl = url.replace(Regex("/ep-\\d+$"), "")
        val doc = app.get(animeUrl, headers = browserHeaders).document

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

        // Anime ID from watch-main or data-id attribute
        val animeId = doc.selectFirst("#watch-main")?.attr("data-id")
            ?: doc.selectFirst("[data-id]")?.attr("data-id")
            ?: Regex("""data-id=["'](\d+)["']""").find(doc.html())?.groupValues?.get(1)

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        if (animeId != null) {
            try {
                val jsonText = app.get(
                    "$mainUrl/ajax/episode/list/$animeId",
                    headers = ajaxHeaders(animeUrl),
                    referer = animeUrl
                ).text
                val html = jsonResultString(jsonText)
                val epSoup = Jsoup.parse(html)

                epSoup.select("a[data-ids]").forEach { el ->
                    val dataIds = el.attr("data-ids").takeIf { it.isNotBlank() } ?: return@forEach
                    val epNum = el.attr("data-num").toIntOrNull()
                    val hasSub = el.attr("data-sub") == "1"
                    val hasDub = el.attr("data-dub") == "1"

                    val epName = el.selectFirst(".d-title")?.text()?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?: el.attr("data-jp").takeIf { it.isNotBlank() }
                        ?: "Episode ${epNum ?: ""}"

                    if (hasSub || !hasDub) {
                        subEpisodes.add(
                            newEpisode("anikoto|$animeUrl|$dataIds|sub") {
                                episode = epNum
                                name = epName
                            }
                        )
                    }
                    if (hasDub) {
                        dubEpisodes.add(
                            newEpisode("anikoto|$animeUrl|$dataIds|dub") {
                                episode = epNum
                                name = epName
                            }
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }

        if (subEpisodes.isEmpty() && dubEpisodes.isEmpty()) {
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

        return newAnimeLoadResponse(title, animeUrl, if (isMovie) TvType.AnimeMovie else TvType.Anime) {
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
        val parts = data.split("|")
        if (parts.size < 4) return false

        val animeUrl = parts[1]
        val serverIds = parts[2]
        val audioType = parts[3]
        val isSub = audioType == "sub"
        val preferredTypes = if (isSub) {
            listOf("sub", "hsub", "h-sub", "raw")
        } else {
            listOf("dub", "adub", "a-dub")
        }

        // Get server list
        val serverListText = app.get(
            "$mainUrl/ajax/server/list?servers=$serverIds",
            headers = ajaxHeaders("$animeUrl/")
        ).text
        val serverListHtml = jsonResultString(serverListText)
        val serverListSoup = Jsoup.parse(serverListHtml)

        val linkIds = mutableListOf<Pair<String, String>>()
        serverListSoup.select(".server-type").forEach { st ->
            val typeAttr = st.attr("data-type")
            if (typeAttr in preferredTypes) {
                st.select(".server").forEach { s ->
                    val linkId = s.attr("data-link-id").takeIf { it.isNotBlank() } ?: return@forEach
                    val serverName = s.selectFirst("span")?.text()?.trim() ?: "Server"
                    linkIds.add(serverName to linkId)
                }
            }
        }

        if (linkIds.isEmpty()) return false

        var found = false
        for ((serverName, linkId) in linkIds) {
            try {
                val serverText = app.get(
                    "$mainUrl/ajax/server?get=$linkId",
                    headers = ajaxHeaders("$animeUrl/")
                ).text
                val serverJson = parseJson<ServerInfoResponse>(serverText)
                val embedUrl = serverJson.result?.url?.takeIf { it.isNotBlank() } ?: continue

                val loaded = resolveEmbedInline(embedUrl, "$animeUrl/", audioType, subtitleCallback, callback)
                if (loaded) found = true
            } catch (_: Exception) {
            }
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
        val domain = Regex("""https?://([^/]+)""").find(normalizedUrl)?.groupValues?.get(1) ?: ""
        val isMegaPlayDomain = domain.contains("megaplay", ignoreCase = true) ||
            domain.contains("vidwish", ignoreCase = true) ||
            domain.contains("vidtube", ignoreCase = true)

        if (isMegaPlayDomain) {
            return resolveMegaPlayInline(normalizedUrl, referer, domain, audioType, subtitleCallback, callback)
        }

        return try {
            loadExtractor(normalizedUrl, referer, subtitleCallback, callback)
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
            "User-Agent" to USER_AGENT,
            "Referer" to referer
        )

        val playbackHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Origin" to host,
            "Referer" to "$host/"
        )

        val doc = app.get(url, headers = pageHeaders).document
        val playerEl = doc.selectFirst("#megaplay-player")
        val streamId = playerEl?.attr("data-id")
            ?: playerEl?.attr("data-realid")
            ?: Regex("""/stream/s-\d+/(\d+)/""").find(url)?.groupValues?.get(1)
            ?: return false

        val jsonText = try {
            app.get(
                "$host/stream/getSources?id=$streamId&type=$type",
                headers = mapOf("Referer" to url),
                referer = url
            ).text
        } catch (_: Exception) {
            return false
        }

        val root = try {
            parseJson<MegaPlaySourceResponse>(jsonText)
        } catch (_: Exception) {
            null
        } ?: return false

        val m3u8 = root.sources?.file
        if (m3u8.isNullOrBlank()) return false

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

        if (href.isBlank()) return null
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

    data class MegaPlaySourceResponse(
        @JsonProperty("sources") val sources: MegaPlaySources? = null,
        @JsonProperty("tracks") val tracks: List<MegaPlayTrack>? = null
    )

    data class MegaPlaySources(
        @JsonProperty("file") val file: String? = null
    )

    data class MegaPlayTrack(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null
    )
}
