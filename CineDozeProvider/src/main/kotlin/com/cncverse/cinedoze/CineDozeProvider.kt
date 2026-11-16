package com.cncverse.cinedoze

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class CineDozeProvider : MainAPI() {
    override var mainUrl = "https://cinedoze.tv"
    override var name = "CineDoze"
    override var lang = "bn"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
    )

    override val mainPage = mainPageOf(
        "movies" to "Latest",
        "genre/bollywood-movies" to "Bollywood",
        "genre/hollywood-movies" to "Hollywood",
        "genre/hindi-dubbed" to "Hindi Dubbed",
        "genre/dual-multi-audio-movies" to "Dual / Multi Audio",
        "genre/bengali-movies" to "Bengali",
        "genre/bangla-dubbed" to "Bangla Dubbed",
        "genre/tamil-movies" to "Tamil",
        "genre/telugu-movies" to "Telugu",
        "genre/malayalam-movies" to "Malayalam",
        "genre/korean-movies-drama" to "Korean",
        "genre/web-series" to "Web Series",
        "genre/tv-series-shows" to "TV Shows",
        "genre/anime" to "Anime"
    )

    private val seriesTag = Regex("(?i)\\bS\\d{1,2}(E\\d{1,3})?\\b|\\bseason\\b")
    private val yearTag = Regex("\\((\\d{4})\\)")

    data class Row(
        @JsonProperty("quality") val quality: String,
        @JsonProperty("url") val url: String
    )

    private fun result(title: String, href: String, poster: String?): SearchResponse {
        return if (seriesTag.containsMatchIn(title)) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = yearTag.find(title)?.groupValues?.get(1)?.toIntOrNull()
            }
        }
    }

    private fun Element.card(): SearchResponse? {
        val a = selectFirst("a[href]") ?: return null
        val title = selectFirst("h3.title, .title a, .title")?.text()?.trim()?.ifEmpty { null }
            ?: selectFirst("img")?.attr("alt")
            ?: return null
        val poster = selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
            ?.replace(Regex("-\\d+x\\d+(?=\\.\\w+$)"), "")
        return result(title, a.attr("href"), poster)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/${request.data}/" else "$mainUrl/${request.data}/page/$page/"
        val doc = app.get(url, headers = headers).document
        val items = doc.select("article.item").mapNotNull { it.card() }
        return newHomePageResponse(HomePageList(request.name, items), items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/", params = mapOf("s" to query), headers = headers).document
            .select("div.result-item article")
            .mapNotNull { it.card() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val title = doc.selectFirst("div.data h1, h1")?.text()?.trim() ?: ""
        val poster = doc.selectFirst("div.poster img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("#info div[itemprop=description] p, #info .wp-content p")?.text()
        val year = yearTag.find(title)?.groupValues?.get(1)?.toIntOrNull()
        val tags = doc.select("div.sgeneros a").map { it.text() }
        val rating = doc.selectFirst("#repimdb strong")?.text()
        val actors = doc.select("#cast div.person[itemprop=actor]").mapNotNull { p ->
            val n = p.selectFirst("meta[itemprop=name]")?.attr("content") ?: return@mapNotNull null
            ActorData(
                Actor(n, p.selectFirst("img")?.attr("src")),
                roleString = p.selectFirst(".caracter")?.text()
            )
        }

        val epiTag = Regex("(?i)\\bEp(?:i|isode)?[-\\s.]*(\\d{1,3})\\b")
        val rows = doc.select("#download tbody tr").mapNotNull { tr ->
            val a = tr.selectFirst("a[href]") ?: return@mapNotNull null
            val tds = tr.select("td")
            val quality = tds.getOrNull(1)?.text()?.trim() ?: ""
            val extra = tds.drop(2).joinToString(" ") { it.text() }
            Triple(epiTag.find(extra)?.groupValues?.get(1)?.toIntOrNull(), Row(quality, a.attr("href")), extra)
        }

        val episodic = rows.any { it.first != null }
        if (!episodic) {
            return newMovieLoadResponse(title, url, TvType.Movie, rows.map { it.second }.toJson()) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.actors = actors
                rating?.toDoubleOrNull()?.let { this.score = Score.from10(it) }
            }
        }

        val season = Regex("(?i)\\bS(\\d{1,2})").find(title)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val episodes = rows.filter { it.first != null }
            .groupBy { it.first!! }
            .toSortedMap()
            .map { (ep, list) ->
                newEpisode(list.map { it.second }.toJson()) {
                    this.season = season
                    this.episode = ep
                    this.name = "Episode $ep"
                }
            }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            this.actors = actors
            rating?.toDoubleOrNull()?.let { this.score = Score.from10(it) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val rows = tryParseJson<List<Row>>(data) ?: return false
        rows.distinctBy { it.url }.amap { row ->
            val label = Regex("\\d{3,4}p|4k", RegexOption.IGNORE_CASE).find(row.quality)?.value ?: row.quality
            val page = runCatching {
                app.get(row.url, headers = headers + ("Referer" to "$mainUrl/"))
            }.getOrNull() ?: return@amap
            // savelinks.me pages list every mirror for one quality row
            val hosts = if (page.url.contains("savelinks")) {
                page.document.select("main ul li a[href^=http]").map { it.attr("href").trim() }.distinct()
            } else {
                listOf(page.url)
            }
            hosts.amap { resolve(it, label, subtitleCallback, callback) }
        }
        return true
    }

    private suspend fun resolve(
        url: String,
        quality: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            when {
                url.contains("filepress") || url.contains("filebee") ->
                    Filepress().getUrl(url, quality, subtitleCallback, callback)
                url.contains("multicloud") ->
                    MultiCloudLinks().getUrl(url, quality, subtitleCallback, callback)
                url.contains("gdxfiles") ->
                    GDxFiles().getUrl(url, quality, subtitleCallback, callback)
                url.contains("gdflix") || url.contains("gdlink") ->
                    GDFlix().getUrl(url, quality, subtitleCallback, callback)
                url.contains("hubcloud") || url.contains("shikshakdaak") ->
                    HubCloud().getUrl(url, quality, subtitleCallback, callback)
                url.contains("gofile") ->
                    Gofile().getUrl(url, quality, subtitleCallback, callback)
                url.contains("filesdl") ->
                    Filesdl().getUrl(url, quality, subtitleCallback, callback)
                url.contains("pixeldrain.com") -> {
                    val id = url.substringAfterLast("/").substringBefore("?")
                    callback(
                        newExtractorLink(
                            "Pixeldrain",
                            "Pixeldrain [$quality]",
                            "https://pixeldrain.com/api/file/$id?download",
                            INFER_TYPE
                        ) {
                            this.quality = getIndexQuality(quality)
                        }
                    )
                }
                url.contains("t.me") || url.contains("telegram") || url.contains("savelinks.me") -> Unit
                else -> loadExtractor(url, quality, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            Log.e("CineDoze", "Error resolving $url: ${e.message}")
        }
    }
}
