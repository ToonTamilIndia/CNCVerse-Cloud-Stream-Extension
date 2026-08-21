package com.cncverse


import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.runBlocking
import org.jsoup.nodes.Element
import okhttp3.FormBody
import org.jsoup.Jsoup
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout


class TamilDhoolProvider : MainAPI() { // all providers must be an instance of MainAPI
    companion object {
        var context: android.content.Context? = null
    }
    
    private val cfInterceptor = CloudflareKiller()
    override var mainUrl = "https://www.tamildhool.tech"
    override var name = "TamilDhool"
    override val hasMainPage = true
    override var lang = "ta"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "zee-tamil" to "Zee Tamil TV",
        "sun-tv" to "Sun TV",
        "vijay-tv" to "Vijay TV",
        "kalaignar-tv" to "Kalaignar TV",
        "news-gossips" to "News Gossips TV",
    )

    data class TamilDhoolLinks(
        @JsonProperty("sourceName") val sourceName: String,
        @JsonProperty("sourceLink") val sourceLink: String
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        
        val query = request.data.format(page)
        val document = app.post(
            "$mainUrl/$query/",
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"),
            referer = "$mainUrl/",
            interceptor = cfInterceptor
        ).document
        val home = document.select("article.regular-post").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(arrayListOf(HomePageList(request.name, home, isHorizontalImages = true)), hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("section.entry-body > h3 > a")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("section.entry-body > h3 > a")?.attr("href").toString())
        val posterUrl = this.selectFirst("div.post-thumb > a > picture > img")?.attr("src")?: fixUrlNull(this.selectFirst("div.post-thumb > a > img")?.attr("src"))
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
            this.posterHeaders = mapOf("referer" to "$mainUrl/")
            this.quality = SearchQuality.HD
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        
        val encodedQuery = query.replace(" ", "+").lowercase()
        val document = app.get("$mainUrl/?s=$encodedQuery", referer = "$mainUrl/", interceptor = cfInterceptor).document
        return document.select("article.regular-post").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        
        val doc = app.get(url, interceptor = cfInterceptor).document
        val title = doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?: return null
        val posterRegex = Regex("(https?:\\/\\/(www\\.)?[-a-zA-Z0-9@:%._\\+~#=]{1,256}\\.[a-zA-Z0-9()]{1,6}\\b([-a-zA-Z0-9()@:%_\\+.~#?&\\/\\/=]*jpg))")
        val posterRaw = doc.selectFirst("div.entry-cover")?.attr("style").toString()
        val poster = posterRegex.find(posterRaw)?.value?.trim()

        val cardLinks = doc.select("figure.td-featured-thumb").mapNotNull { fig ->
            val anchor = fig.selectFirst("a[href]")
            val rawHref = anchor?.attr("href")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val label = fig.selectFirst("div.td-source-label")?.text()?.trim() ?: ""
            TamilDhoolLinks(
                classifySourceName(rawHref, label),
                normaliseHref(rawHref, label)
            )
        }

        val legacyLinks = if (cardLinks.isEmpty()) {
            doc.select("div.entry-content link[rel=prefetch][href], div.entry-content a[href], div.entry-content iframe[src]").mapNotNull {
                val rawUrl = it.attr("href").ifBlank { it.attr("src") }.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (rawUrl.startsWith(mainUrl) && !rawUrl.contains("?video=")) return@mapNotNull null
                TamilDhoolLinks(
                    classifySourceName(rawUrl, ""),
                    normaliseHref(rawUrl, "")
                )
            }
        } else emptyList()

        val link = (cardLinks + legacyLinks).distinctBy { it.sourceLink }

        val episodes = listOf(
            newEpisode(data = link.toJson()){
                name = title
                season = 1
                episode = 1
                this.posterUrl = poster
            }
        )

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster?.trim()
            this.posterHeaders = mapOf("referer" to "$mainUrl/")
        }
    }

    private fun normaliseHref(href: String, label: String): String {
        if (href.contains("?video=", true)) {
            val id = href.substringAfter("?video=").substringBefore("&")
            if (label.contains("Dailymotion", true)) {
                return "https://www.dailymotion.com/embed/video/$id"
            }
            if (label.contains("Youtube", true)) {
                return "https://www.youtube.com/watch?v=$id"
            }
        }
        if (href.startsWith("https://dai.ly/")) {
            return "https://www.dailymotion.com/embed/video/" + href.removePrefix("https://dai.ly/")
        }
        return href
    }

    private fun classifySourceName(href: String, label: String): String {
        if (!label.isBlank()) return label
        return when {
            href.contains("thirai", true) -> "ThiraiOne"
            href.contains("dailymotion", true) || href.contains("dai.ly", true) -> "Dailymotion"
            href.contains("youtube", true) || href.contains("youtu.be", true) -> "Youtube"
            href.contains("?video=", true) -> "TeamsToday"
            else -> "Unknown"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val link = parseJson<ArrayList<TamilDhoolLinks>>(data)

        val totalCounts = link.groupingBy { it.sourceName }.eachCount()
        val currentCounts = mutableMapOf<String, Int>()

        safeApiCall {
            link.forEach { src ->
                val count = currentCounts[src.sourceName] ?: 0
                val total = totalCounts[src.sourceName] ?: 0
                currentCounts[src.sourceName] = count + 1
                val displayName = if (total > 1) "${src.sourceName} ${count + 1}" else src.sourceName

                when (src.sourceName) {
                    "ThiraiOne" -> {
                        callback.invoke(
                            newExtractorLink(
                                displayName,
                                displayName,
                                src.sourceLink.replace("/p/", "/v/") + ".m3u8",
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = "$mainUrl/"
                                this.headers = mapOf("Referer" to "$mainUrl/")
                            }
                        )
                    }
                    else -> {
                        loadExtractor(src.sourceLink, "$mainUrl/", subtitleCallback) { extractedLink ->
                            runBlocking {
                                callback.invoke(
                                    newExtractorLink(
                                        extractedLink.source,
                                        displayName,
                                        extractedLink.url,
                                        if (extractedLink.isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = extractedLink.referer
                                        this.quality = extractedLink.quality
                                        this.headers = extractedLink.headers
                                        this.extractorData = extractedLink.extractorData
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
        return true
    }



}