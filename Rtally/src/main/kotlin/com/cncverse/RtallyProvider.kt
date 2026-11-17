package com.cncverse

import com.cncverse.rtally.Filepress
import com.cncverse.rtally.GDFlix
import com.cncverse.rtally.GDxFiles
import com.cncverse.rtally.Gofile
import com.cncverse.rtally.HubCloud
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.extractors.ByseSX
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder

class RtallyProvider : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }

    override var mainUrl = "https://www.rtally.link"
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
        "/genres/animation" to "Animation"
    )
    private val headers =
        mapOf("user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36")

    private val junkHosts = listOf("rtally", "facebook.com", "t.me", "telegram", "google.com", "toonworld", "xmovies")

    // Dead `vidhide*` hosts redirect to the current mirror domain, which is remembered here
    @Volatile private var vidHideMirror = "https://callistanise.com"
    private val vidHideRegex = Regex("^https?://[^/]*vidhide[^/]*(/.*)$", RegexOption.IGNORE_CASE)

    private val fileMoonRegex = Regex("\"multiLinksDl\":\\s*\"([^\"]+)\"")
    private val streamwishMultiUrlRegex = Regex("\"streamwishMultiUrl\":\\s*\"([^\"]+)\"")
    private val vidhideplusRegex = Regex("\"multiLinksSl\":\\s*\"([^\"]+)\"")

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

    private fun toResult(post: Element): SearchResponse? {
        val href = post.attr("href")
        if (!href.startsWith("/post/")) return null
        val title = post.select("h4").text()
        val url = mainUrl + href
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

    private fun extractFileMoonUrls(text: String): String? {
        return fileMoonRegex.find(text)?.groupValues?.getOrNull(1)
    }

    private fun extractImageUrl(url: String?): String? {
        if (url.isNullOrEmpty()) return url
        if (!url.contains("url=")) return url
        val param = url.substringAfter("url=").substringBefore("&")
        val bytes = mutableListOf<Byte>()
        var i = 0
        while (i < param.length) {
            val c = param[i]
            if (c == '%' && i + 2 < param.length) {
                try {
                    bytes.add(Integer.parseInt(param.substring(i + 1, i + 3), 16).toByte())
                    i += 3
                    continue
                } catch (e: Exception) {
                }
            }
            bytes.add(if (c == '+') 32 else c.toByte())
            i++
        }
        return bytes.toByteArray().decodeToString()
    }

    private fun extractStreamwishUrls(text: String): String? {
        return streamwishMultiUrlRegex.find(text)?.groupValues?.getOrNull(1)
    }

    private fun extractVidhideplus(text: String): String? {
        return vidhideplusRegex.find(text)?.groupValues?.getOrNull(1)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/search/$query",
            cacheTime = 60,
            headers = headers
        ).document
        return doc.select("a[href^='/post/']:has(h4)").mapNotNull { toResult(it) }.distinctBy { it.url }
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
                if (isServerLink(a)) {
                    val lbl = a.selectFirst("span[class*='font-bold'], span")?.text()?.trim()
                        ?.takeIf { it.isNotEmpty() } ?: "Stream"
                    streamLinks.add("$lbl|${embedify(a.attr("href"))}")
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
                if (isServerLink(a)) {
                    val lbl = a.selectFirst("span[class*='font-bold'], span")?.text()?.trim()
                        ?.takeIf { it.isNotEmpty() } ?: "Stream"
                    links.add("$lbl|${embedify(a.attr("href"))}")
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

    private suspend fun resolveDownloadPage(
        dlPageUrl: String,
        qualityLabel: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(dlPageUrl, headers = headers, cacheTime = 60).document
            doc.select("a[target='_blank'][href]").filter { isServerLink(it) }.amap { a ->
                extract(embedify(a.attr("href")), subtitleCallback, callback)
            }
        } catch (e: Exception) {
        }
    }

    private fun isServerLink(a: Element): Boolean {
        val href = a.attr("href")
        if (!href.startsWith("http") || a.hasClass("no-ad")) return false
        return junkHosts.none { href.contains(it) }
    }

    private suspend fun extract(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val vidHidePath = vidHideRegex.find(url)?.groupValues?.get(1)
            if (vidHidePath != null) {
                var found = false
                val cb: (ExtractorLink) -> Unit = {
                    found = true
                    callback(it)
                }
                runCatching {
                    VidHidePro().getUrl("$vidHideMirror$vidHidePath", "$mainUrl/", subtitleCallback, cb)
                }
                if (!found) {
                    val location = app.get(url, allowRedirects = false, timeout = 15L).headers["Location"]
                    if (location != null) {
                        Regex("^https?://[^/]+").find(location)?.value?.let { vidHideMirror = it }
                    }
                    VidHidePro().getUrl("$vidHideMirror$vidHidePath", "$mainUrl/", subtitleCallback, callback)
                }
                return
            }

            when {
                Regex("^https?://byse[^/]*/").containsMatchIn(url) ->
                    ByseSX().getUrl(url, "$mainUrl/", subtitleCallback, callback)
                hostOf(url).let { it.contains("hubcloud") || it.contains("vcloud") } ->
                    HubCloud().getUrl(url, "$mainUrl/", subtitleCallback, callback)
                hostOf(url).contains("gdflix") ->
                    GDFlix().getUrl(url, "$mainUrl/", subtitleCallback, callback)
                hostOf(url).contains("gdxfiles") ->
                    GDxFiles().getUrl(url, "$mainUrl/", subtitleCallback, callback)
                hostOf(url).let { it.contains("filepress") || it.contains("filebee") } ->
                    Filepress().getUrl(url, "$mainUrl/", subtitleCallback, callback)
                hostOf(url).contains("gofile") ->
                    Gofile().getUrl(url, "$mainUrl/", subtitleCallback, callback)
                else -> loadExtractor(url, "$mainUrl/", subtitleCallback, callback)
            }
        } catch (e: Exception) {
        }
    }

    private fun hostOf(url: String): String {
        return runCatching { URI(url).host?.lowercase() }.getOrNull() ?: ""
    }

    private fun embedify(url: String): String {
        return when {
            url.contains("vidhideplus.com/d/") -> url.replace("/d/", "/v/")
            url.contains("vidhidepre.com/d/") -> url.replace("/d/", "/v/")
            url.contains("playerwish.com/d/") || url.contains("filemoon.sx/d/") -> url.replace("/d/", "/e/")
            url.contains("filemoon.sx/download/") || Regex("^https?://byse[^/]*/download/").containsMatchIn(url) ->
                url.replace("/download/", "/e/")
            else -> url
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        data.split(" ; ").amap { item ->
            val parts = item.split("|")
            val linkUrl = (if (parts.size >= 2) parts[1] else item).trim()
            if (linkUrl.startsWith("dlpage:")) {
                resolveDownloadPage(linkUrl.removePrefix("dlpage:"), parts.getOrNull(0) ?: "", subtitleCallback, callback)
            } else if (linkUrl.isNotEmpty() && junkHosts.none { linkUrl.contains(it) }) {
                extract(embedify(linkUrl), subtitleCallback, callback)
            }
        }
        return true
    }
}
