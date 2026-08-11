package com.cncverse

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLDecoder

class RtallyProvider : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }

    override var mainUrl = "https://www.rtally.shop"
    override var name = "Rtally"
    override var lang = "ta"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.AnimeMovie,
        TvType.Anime
    )
    override val mainPage = mainPageOf(
        "/categories/trending" to "Trending",
        "/categories/featured" to "Featured",
        "/categories/hollywood" to "Hollywood",
        "/categories/bengali" to "Bangla",
        "/categories/bollywood" to "Bollywood",
        "/categories/tv-shows" to "Tv Shows",
        "/categories/korean" to "Korean",
        "/categories/anime" to "Anime"
    )
    private val headers =
        mapOf("user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36")

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val doc = app.get(
            "$mainUrl${request.data}?page=$page",
            cacheTime = 60,
            headers = headers
        ).document
        val home = doc.select("section.md\\:col-span-3 div.grid a[href]").mapNotNull { toResult(it) }
        return newHomePageResponse(request.name, home, true)
    }

    private fun toResult(post: Element): SearchResponse {
        val title = post.select("h4").text()
        val url = mainUrl + post.attr("href")
        var posterUrl = extractImageUrl(post.select("img").attr("src"))
        if (posterUrl.isNullOrEmpty()) {
            val styleAttr = post.select("div[style*=background-image]").attr("style")
            posterUrl = styleAttr.substringAfter("url(").substringBefore(")").substringBefore("?")
        }
        val language = post.select("div.absolute.bottom-2.left-2").text()

        return newAnimeSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = posterUrl
            addDubStatus(
                dubExist = when {
                    "Dual" in language -> true
                    "Hindi" in language -> true
                    "Tamil" in language -> true
                    "Telugu" in language -> true
                    "Bangla" in language -> true
                    else -> false
                },
                subExist = "Eng-Sub" in language
            )
        }
    }

    private fun extractImageUrl(url: String?): String? {
        if (url.isNullOrEmpty()) return url
        if (!url.contains("url=")) return url
        return try {
            URLDecoder.decode(url.substringAfter("url=").substringBefore("&"), "UTF-8")
        } catch (e: Exception) {
            url
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/search/$query",
            cacheTime = 60,
            headers = headers
        ).document
        return doc.select("div.grid:nth-child(1) > a[href]:not([target])").mapNotNull { toResult(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(
            url,
            cacheTime = 60,
            headers = headers
        ).document
        val title = doc.selectFirst("h1.font-josefn, h1[class*='font-josefn']")?.text()?.trim()
            ?: doc.title().substringBefore("|").trim()
        var image = extractImageUrl(doc.selectFirst(".w-\\[200px\\] img, article img")?.attr("src"))
        if (image.isNullOrEmpty()) {
            val styleAttr = doc.select("div[style*=background-image]").first()?.attr("style")
            image = styleAttr?.substringAfter("url(")?.substringBefore(")")?.substringBefore("?")
        }
        val plot = doc.selectFirst("p.mt-2, article p[class*='text-sm']")?.text()
        val year = doc.select("div.infoDiv span:last-child, .infoDiv span")
            .mapNotNull { it.text().trim().toIntOrNull() }
            .firstOrNull()
        val recommendations = doc.select("div.grid a[href*='/post/']").mapNotNull { a ->
            val recTitle = a.selectFirst("h4")?.text()?.trim() ?: return@mapNotNull null
            val recUrl = if (a.attr("href").startsWith("http")) a.attr("href") else mainUrl + a.attr("href")
            newMovieSearchResponse(recTitle, recUrl, TvType.Movie) {
                this.posterUrl = extractImageUrl(a.selectFirst("img")?.attr("src"))
            }
        }
        val episodeDivs = doc.select("div[id^='episode-']")
        if (episodeDivs.isNotEmpty()) {
            val episodesData = mutableListOf<Episode>()
            episodeDivs.forEach { epDiv ->
                val epNum = Regex("\\d+").find(epDiv.attr("id"))?.value?.toIntOrNull()
                if (epNum != null) {
                    val qualityLinks = mutableListOf<String>()
                    epDiv.select("a[href*='/redirect?']").forEach { a ->
                        val qualityLabel = a.text().trim().uppercase()
                        val actualUrl = extractRedirectLink(a.attr("href"))
                        if (actualUrl != null) {
                            qualityLinks.add("$qualityLabel|${embedify(actualUrl)}")
                        }
                    }
                    if (qualityLinks.isNotEmpty()) {
                        val epData = qualityLinks.joinToString(" ; ")
                        episodesData.add(newEpisode(epData) {
                            this.name = "Episode $epNum"
                            this.season = 1
                            this.episode = epNum
                        })
                    }
                }
            }
            val streamLinks = mutableListOf<String>()
            doc.select("a[target='_blank'][href]").forEach { a ->
                val href = a.attr("href")
                if (href.startsWith("http") && !href.contains("rtally") && !href.contains("facebook.com") &&
                    !href.contains("t.me") && !href.contains("telegram") && !href.contains("google.com")
                ) {
                    val lbl = a.selectFirst("span[class*='font-bold'], span")?.text()?.trim()
                        ?.takeIf { it.isNotEmpty() } ?: "Stream"
                    streamLinks.add("$lbl|${embedify(href)}")
                }
            }
            if (streamLinks.isNotEmpty()) {
                val streamData = streamLinks.joinToString(" ; ")
                val ep1 = episodesData.firstOrNull { it.episode == 1 && it.season == 1 }
                if (ep1 != null) {
                    if (ep1.data.isNullOrEmpty()) {
                        ep1.data = streamData
                    } else {
                        ep1.data += " ; $streamData"
                    }
                } else {
                    episodesData.add(newEpisode(streamData) {
                        this.name = "Episode 1"
                        this.season = 1
                        this.episode = 1
                    })
                }
            }
            if (episodesData.size > 1) {
                episodesData.sortWith(compareBy { it.episode })
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesData) {
                this.posterUrl = image
                this.plot = plot
                this.year = year
                this.recommendations = recommendations
            }
        } else {
            val links = mutableListOf<String>()
            doc.select("section a[href*='/download/']").forEach { a ->
                val href = a.attr("href")
                if (!href.contains("/redirect?")) {
                    val quality = href.substringAfterLast("/").uppercase()
                    val absHref = if (href.startsWith("http")) href else mainUrl + href
                    links.add("$quality|dlpage:$absHref")
                }
            }
            doc.select("a[target='_blank'][href]").forEach { a ->
                val href = a.attr("href")
                if (href.startsWith("http") && href.contains("rtally") && !href.contains("facebook.com") &&
                    !href.contains("t.me") && !href.contains("telegram") && !href.contains("google.com")
                ) {
                    val lbl = a.selectFirst("span[class*='font-bold'], span")?.text()?.trim()
                        ?.takeIf { it.isNotEmpty() } ?: "Stream"
                    links.add("$lbl|${embedify(href)}")
                }
            }
            return newMovieLoadResponse(title, url, TvType.Movie, links.joinToString(" ; ")) {
                this.posterUrl = image
                this.plot = plot
                this.year = year
                this.recommendations = recommendations
            }
        }
    }

    private fun extractRedirectLink(href: String): String? {
        if (!href.contains("/redirect?")) return null
        return try {
            URLDecoder.decode(href.substringAfter("link=").substringBefore("&"), "UTF-8")
        } catch (e: Exception) {
            null
        }
    }

    private fun embedify(url: String): String {
        return when {
            url.contains("vidhideplus.com/d/") -> url.replace("/d/", "/v/")
            url.contains("vidhidepre.com/d/") -> url.replace("/d/", "/v/")
            url.contains("playerwish.com/d/") || url.contains("filemoon.sx/d/") -> url.replace("/d/", "/e/")
            url.contains("filemoon.sx/download/") -> url.replace("/download/", "/e/")
            else -> url
        }
    }

    private suspend fun resolveDownloadPage(
        dlPageUrl: String,
        qualityLabel: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = try {
            app.get(dlPageUrl, headers = headers).document
        } catch (e: Exception) {
            return
        }
        doc.select("a[target='_blank'][href]").forEach { a ->
            val href = a.attr("href")
            try {
                if (href.startsWith("http") && !href.contains("rtally") && !href.contains("facebook.com") &&
                    !href.contains("t.me") && !href.contains("telegram") && !href.contains("google.com")
                ) {
                    loadExtractor(embedify(href), subtitleCallback, callback)
                }
            } catch (e: Exception) {
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        data.split(" ; ").forEach { item ->
            val parts = item.split("|")
            val linkUrl = if (parts.size >= 2) parts[1].trim() else item.trim()
            if (linkUrl.startsWith("dlpage:")) {
                val dlPage = linkUrl.removePrefix("dlpage:")
                val qualityLabel = parts.getOrNull(0) ?: ""
                resolveDownloadPage(dlPage, qualityLabel, subtitleCallback, callback)
            } else if (linkUrl.isNotEmpty()) {
                try {
                    loadExtractor(linkUrl, subtitleCallback, callback)
                } catch (e: Exception) {
                }
            }
        }
        return true
    }
}
