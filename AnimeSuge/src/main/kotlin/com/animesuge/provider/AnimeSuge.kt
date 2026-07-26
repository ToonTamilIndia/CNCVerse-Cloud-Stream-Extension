package com.animesuge.provider

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import java.net.URLEncoder

class AnimeSuge : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }

    override var mainUrl = "https://animesuge.cz"
    override var name = "AnimeSuge"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/latest-updated" to "Recently Updated",
        "$mainUrl/new-release" to "New Releases",
        "$mainUrl/most-viewed" to "Popular Anime",
        "$mainUrl/status/finished-airing" to "Completed",
        "$mainUrl/status/currently-airing" to "Ongoing",
    )

    private val ajaxHeaders = mapOf(
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to "$mainUrl/",
    )

    private fun rc4(key: ByteArray, input: ByteArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0..255) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val tmp = s[i]
            s[i] = s[j]
            s[j] = tmp
        }
        var i = 0
        j = 0
        val out = ByteArray(input.size)
        for (x in input.indices) {
            i = (i + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            val tmp = s[i]
            s[i] = s[j]
            s[j] = tmp
            out[x] = ((input[x].toInt() and 0xFF) xor s[(s[i] + s[j]) and 0xFF]).toByte()
        }
        return out
    }

    private fun shiftCharcode(t: String): ByteArray {
        val result = ByteArray(t.length)
        for (r in t.indices) {
            var s = t[r].code
            when (r % 8) {
                0 -> s -= 3
                1 -> s += 3
                2 -> s -= 4
                3 -> s += 2
                4 -> s -= 2
                5 -> s += 5
                6 -> s += 4
                7 -> s += 5
            }
            result[r] = s.toByte()
        }
        return result
    }

    private fun rot13(s: String) = s.map { c ->
        when (c) {
            in 'a'..'z' -> ((c.code - 'a'.code + 13) % 26 + 'a'.code).toChar()
            in 'A'..'Z' -> ((c.code - 'A'.code + 13) % 26 + 'A'.code).toChar()
            else -> c
        }
    }.joinToString("")

    private fun generateVrf(input: String): String {
        val encoded = URLEncoder.encode(input, "UTF-8").replace("+", "%20")
        val key = "ysJhV6U27FVIjjuk".toByteArray(Charsets.UTF_8)
        val rc4Bytes = rc4(key, encoded.toByteArray(Charsets.UTF_8))
        val b64 = Base64.encodeToString(rc4Bytes, Base64.URL_SAFE or Base64.NO_WRAP)
        val shifted = shiftCharcode(b64)
        val b64Shifted = Base64.encodeToString(shifted, Base64.URL_SAFE or Base64.NO_WRAP)
        return rot13(b64Shifted)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + if (page > 1) "?page=$page" else ""
        val doc = app.get(url).document
        val items = doc.select("div.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items.distinctBy { it.url }, hasNext = true)
    }

    private fun org.jsoup.nodes.Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a.poster") ?: selectFirst("a") ?: return null
        val rawHref = anchor.attr("href").takeIf { it.isNotBlank() } ?: return null
        val href = fixUrl(rawHref.replace(Regex("/ep-\\d+$"), ""))
        val title = selectFirst(".name a, .name, img")?.let {
            if (it.tagName() == "img") it.attr("alt") else it.text()
        }?.trim() ?: return null
        val poster = selectFirst("img.lazyload, img")
            ?.let { it.attr("data-src").takeIf { s -> s.isNotBlank() } ?: it.attr("src") }
            ?.let { if (it.startsWith("http")) it else "$mainUrl/$it" }
        return newAnimeSearchResponse(title, href) { this.posterUrl = poster }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return app.get("$mainUrl/filter?keyword=$encoded").document
            .select("div.item").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val animeUrl = url.replace(Regex("/ep-\\d+$"), "")
        val doc = app.get(animeUrl).document

        val dataId = doc.selectFirst(".watch-wrap[data-id]")?.attr("data-id")
            ?: Regex("""mangaId\s*=\s*(\d+)""").find(doc.html())?.groupValues?.get(1)
            ?: return null

        val title = doc.selectFirst(".maindata h1.title, h1.title[itemprop=name], h1.title")
            ?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore(" Episode")?.trim()
            ?: "Unknown"

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")

        val plot = doc.selectFirst(".description .full.cts-block div, .description .full div")
            ?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val genres = doc.select(".meta a[href*='/genre/'], .data a[href*='/genre/']")
            .map { it.text().trim() }

        val vrf = generateVrf(dataId)
        val epsText = app.get(
            "$mainUrl/ajax/episode/list/$dataId?vrf=$vrf",
            headers = ajaxHeaders,
        ).text
        val epsJson = parseJson<AjaxResponse>(epsText)
        val epsHtml = epsJson.result ?: return null
        val epsSoup = Jsoup.parse(epsHtml)

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        epsSoup.select("a[data-ids]").forEach { epLink ->
            val epNum = epLink.text().toIntOrNull()
                ?: epLink.attr("data-slug").toIntOrNull()
                ?: 1
            val epTitle = epLink.attr("data-num").takeIf { it.isNotBlank() } ?: "Episode $epNum"
            val dataIds = epLink.attr("data-ids").takeIf { it.isNotBlank() } ?: return@forEach
            val hasSub = epLink.attr("data-sub") == "1"
            val hasDub = epLink.attr("data-dub") == "1"

            if (hasSub) {
                subEpisodes.add(newEpisode("$animeUrl|$dataId|$epNum|$dataIds|sub") {
                    episode = epNum
                    name = epTitle
                })
            }
            if (hasDub) {
                dubEpisodes.add(newEpisode("$animeUrl|$dataId|$epNum|$dataIds|dub") {
                    episode = epNum
                    name = epTitle
                })
            }
        }

        return newAnimeLoadResponse(title, animeUrl, TvType.Anime) {
            this.posterUrl = poster
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
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val parts = data.split("|")
        if (parts.size < 5) return false

        val animeUrl = parts[0]
        val dataIds = parts[3]
        val selectedType = parts[4]

        val serverListText = app.get(
            "$mainUrl/ajax/server/list?servers=$dataIds",
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to "$animeUrl/",
            ),
        ).text
        val serverListJson = parseJson<AjaxResponse>(serverListText)
        val serverListHtml = serverListJson.result ?: return false
        val serverListSoup = Jsoup.parse(serverListHtml)

        val serversToLoad = mutableListOf<Pair<String, String>>()

        serverListSoup.select(".server-type").forEach { st ->
            val typeAttr = st.attr("data-type")
            val isMatch = if (selectedType == "sub") {
                typeAttr in listOf("sub", "hsub", "h-sub", "raw")
            } else {
                typeAttr in listOf("dub", "adub", "a-dub")
            }
            if (!isMatch) return@forEach

            st.select(".server").forEach { s ->
                val linkId = s.attr("data-link-id").takeIf { it.isNotBlank() } ?: return@forEach
                val serverName = s.selectFirst("span")?.text()?.trim() ?: "Server"
                serversToLoad.add(serverName to linkId)
            }
        }

        if (serversToLoad.isEmpty()) return false

        var found = false

        for ((serverName, linkId) in serversToLoad) {
            try {
                val serverInfoText = app.get(
                    "$mainUrl/ajax/server?get=$linkId",
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to "$animeUrl/",
                    ),
                ).text
                val serverInfoJson = parseJson<ServerInfoResponse>(serverInfoText)
                val playerUrl = serverInfoJson.result?.url
                    ?.takeIf { it.isNotBlank() } ?: continue

                val loaded = loadExtractor(playerUrl, "$mainUrl/", subtitleCallback, callback)
                if (loaded) found = true
            } catch (_: Exception) {
            }
        }

        return found
    }

    data class AjaxResponse(
        @JsonProperty("status") val status: Int? = null,
        @JsonProperty("result") val result: String? = null,
    )

    data class ServerInfoResponse(
        @JsonProperty("status") val status: Int? = null,
        @JsonProperty("result") val result: ServerInfoResult? = null,
    )

    data class ServerInfoResult(
        @JsonProperty("url") val url: String? = null,
    )
}
