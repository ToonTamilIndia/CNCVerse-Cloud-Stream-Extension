package com.anikoto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLDecoder
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
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/filter?keyword=$encodedQuery", headers = browserHeaders).document
        return doc.select("div.item, div.flw-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val response = app.get(url, headers = browserHeaders)
            val doc = response.document

            val title = doc.selectFirst("#w-info h1.title, h1[itemprop=name], .title[itemprop=name]")
                ?.text()?.trim()
                ?: doc.selectFirst("h1.title")?.text()?.trim()
                ?: return null

            val posterEl = doc.selectFirst("#w-info .poster img, img[itemprop=image], .poster img")
            val poster = posterEl?.let {
                it.attr("data-src").takeIf { s -> s.isNotBlank() } ?: it.attr("src")
            }

            val description = doc.selectFirst("#w-info .synopsis .content, #w-info .synopsis, .synopsis .content")
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
                    val json = app.get(
                        "$mainUrl/ajax/episode/list/$animeId",
                        headers = ajaxHeaders(url),
                        referer = url
                    ).text
                    val html = jsonResultString(json)

                    Jsoup.parse(html).select("a[data-ids]").forEach { el ->
                        val serverIds = el.attr("data-ids")
                        val episodeNumber = el.attr("data-num").toIntOrNull()
                        val hasSub = el.attr("data-sub") == "1"
                        val hasDub = el.attr("data-dub") == "1"
                        if (serverIds.isBlank()) return@forEach

                        val episodeName = el.selectFirst(".d-title")?.text()?.takeIf { it.isNotBlank() }
                            ?: el.attr("data-jp").takeIf { it.isNotBlank() }
                            ?: "Episode ${episodeNumber ?: ""}"

                        if (hasSub || !hasDub) {
                            subEpisodes.add(
                                newEpisode("anikoto|$url|$serverIds|sub") {
                                    episode = episodeNumber
                                    name = episodeName
                                }
                            )
                        }
                        if (hasDub) {
                            dubEpisodes.add(
                                newEpisode("anikoto|$url|$serverIds|dub") {
                                    episode = episodeNumber
                                    name = episodeName
                                }
                            )
                        }
                    }
                } catch (_: Exception) {
                }
            }

            if (subEpisodes.isNotEmpty() && dubEpisodes.isEmpty()) {
                doc.select("a[href*='/ep-']").forEachIndexed { i, el ->
                    subEpisodes.add(
                        newEpisode(fixUrl(el.attr("href"))) {
                            episode = i + 1
                            name = el.text().ifBlank { "Episode ${i + 1}" }
                        }
                    )
                }
            }

            newAnimeLoadResponse(title, url, if (isMovie) TvType.AnimeMovie else TvType.Anime) {
                this.posterUrl = poster?.let { fixUrl(it) }
                this.plot = description
                this.tags = genres
                if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
                if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
            }
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val decoded = try {
            URLDecoder.decode(data, "UTF-8")
        } catch (_: Exception) {
            data
        }
        val ep = if (decoded.startsWith("$mainUrl/")) decoded.removePrefix("$mainUrl/") else decoded

        if (ep.startsWith("anikoto|")) {
            val parts = ep.split("|", limit = 4)
            if (parts.size < 4) return false
            val referer = parts[1]
            val serverIds = parts[2]
            val audioType = parts[3].ifBlank { "sub" }
            if (serverIds.isBlank()) return false

            val linkIds = try {
                val serverListJson = app.get(
                    "$mainUrl/ajax/server/list?servers=$serverIds",
                    headers = ajaxHeaders(referer)
                ).text
                val serverListHtml = jsonResultString(serverListJson)
                if (serverListHtml.isBlank()) return false
                val serverDoc = Jsoup.parse(serverListHtml)

                val typeSelectors = if (audioType == "dub") {
                    listOf("div.type[data-type=dub]")
                } else {
                    listOf("div.type[data-type=sub]", "div.type[data-type=hsub]")
                }
                val preferredServers = typeSelectors
                    .flatMap { serverDoc.select("$it li[data-link-id]") }
                    .ifEmpty { serverDoc.select("li[data-link-id]") }
                preferredServers.map { it.attr("data-link-id") }.filter { it.isNotBlank() }.distinct()
            } catch (_: Exception) {
                return false
            }
            if (linkIds.isEmpty()) return false

            var found = false
            for (linkId in linkIds) {
                try {
                    val serverJson = app.get(
                        "$mainUrl/ajax/server?get=$linkId",
                        headers = ajaxHeaders(referer)
                    ).text
                    val embedUrl = jsonResultUrl(serverJson)
                    if (embedUrl.isNullOrBlank()) continue
                    if (resolveEmbedInline(embedUrl, referer, subtitleCallback, callback)) found = true
                } catch (_: Exception) {
                }
            }
            return found
        }

        if (ep.startsWith("anikoto-direct|")) {
            return resolveFromWatchPage(ep.removePrefix("anikoto-direct|"), subtitleCallback, callback)
        }
        return resolveFromWatchPage(ep, subtitleCallback, callback)
    }

    private suspend fun resolveFromWatchPage(
        episodeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val doc = app.get(episodeUrl, headers = browserHeaders).document
            val watchMain = doc.selectFirst("#watch-main") ?: return false
            val animeId = watchMain.attr("data-id").takeIf { it.isNotBlank() } ?: return false
            val epName = watchMain.attr("data-ep-name").takeIf { it.isNotBlank() }

            val epListJson = app.get(
                "$mainUrl/ajax/episode/list/$animeId?vrf=",
                headers = ajaxHeaders(episodeUrl)
            ).text
            val epListHtml = jsonResultString(epListJson)
            if (epListHtml.isBlank()) return false
            val epDoc = Jsoup.parse(epListHtml)

            val targetEp = epName?.let { epDoc.selectFirst("a[data-num=$it]") }
                ?: epDoc.select("a[data-ids]").firstOrNull()
                ?: return false
            val serverIds = targetEp.attr("data-ids").takeIf { it.isNotBlank() } ?: return false

            val serverListJson = app.get(
                "$mainUrl/ajax/server/list?servers=$serverIds",
                headers = ajaxHeaders(episodeUrl)
            ).text
            val serverListHtml = jsonResultString(serverListJson)
            if (serverListHtml.isBlank()) return false
            val linkIds = Jsoup.parse(serverListHtml).select("li[data-link-id]")
                .map { it.attr("data-link-id") }
                .filter { it.isNotBlank() }
                .distinct()
            if (linkIds.isEmpty()) return false

            var found = false
            for (linkId in linkIds) {
                try {
                    val serverJson = app.get(
                        "$mainUrl/ajax/server?get=$linkId",
                        headers = ajaxHeaders(episodeUrl)
                    ).text
                    val embedUrl = jsonResultUrl(serverJson)
                    if (embedUrl.isNullOrBlank()) continue
                    if (resolveEmbedInline(embedUrl, episodeUrl, subtitleCallback, callback)) found = true
                } catch (_: Exception) {
                }
            }
            found
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun resolveEmbedInline(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val normalizedUrl = when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> url
        }
        return try {
            loadExtractor(normalizedUrl, referer, subtitleCallback, callback)
        } catch (_: Exception) {
            false
        }
    }

    private fun jsonResultString(json: String): String {
        return try {
            val response = parseJson<AjaxResponse>(json)
            if (response.status == 200) {
                response.result as? String ?: ""
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
        val titleEl = selectFirst("a.name.d-title")
            ?: selectFirst("a[title]")
            ?: selectFirst("a[href*='/watch/']")
            ?: return null

        val href = titleEl.attr("href").ifBlank {
            selectFirst("div.poster a, a")?.attr("href") ?: ""
        }
        val title = titleEl.text().trim().ifBlank { titleEl.attr("title").trim() }
        if (href.isBlank() || title.isBlank()) return null

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

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AjaxResponse(
        @JsonProperty("status") val status: Int? = null,
        @JsonProperty("result") val result: Any? = null
    )
}
