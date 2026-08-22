package com.cncverse

import android.util.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.util.concurrent.TimeUnit

class MovieLinkBDProvider : MainAPI() {
    companion object {
        var appContext: Context? = null
        private const val FALLBACK_URL = "https://movielinkbd.one"
        private const val BROWSER_DEBOUNCE_MS = 10000L
        @Volatile private var lastBrowserOpenMs = 0L
    }

    override var mainUrl = FALLBACK_URL
    override var name = "MovieLinkBD"
    override var lang = "bn"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false

    override val mainPage = mainPageOf(
        "/" to "Recently Updated",
        "/type/movies" to "All Movies",
        "/type/series" to "All Web Series",
        "/language/hindi" to "Hindi Movies",
        "/language/bangla" to "Bangla Movies",
        "/language/bangla-dubbed" to "Bangla Dubbed",
        "/language/dual-audio" to "Dual Audio",
        "/language/english" to "English",
        "/southIndian" to "South Indian",
        "/language/korean" to "Korean",
        "/anime" to "Anime Zone",
        "/drama" to "K/J/C Drama",
        "/ongoing" to "Ongoing Series",
        "/genre/action" to "Action",
        "/genre/thriller" to "Thriller",
        "/genre/horror" to "Horror",
        "/genre/romance" to "Romance",
        "/category/wwe" to "WWE"
    )

    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.AsianDrama,
        TvType.AnimeMovie, TvType.Anime,
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    @Volatile private var resolvedBase: String? = null

    // ── Custom OkHttp clients with Cloudflare bypass ─────────────────────────
    private val cfClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(CloudflareKiller())
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val noRedirectClient by lazy {
        cfClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────
    private suspend fun httpGetText(url: String, headers: Map<String, String> = emptyMap()): String {
        return withContext(Dispatchers.IO) {
            val reqHeaders = Headers.Builder()
            for ((k, v) in headers) reqHeaders.add(k, v)
            val request = Request.Builder().url(url).headers(reqHeaders.build()).get().build()
            val response = cfClient.newCall(request).execute()
            response.body.string().also { response.close() }
        }
    }

    private suspend fun httpGetDoc(url: String, headers: Map<String, String> = emptyMap()): Document {
        val html = httpGetText(url, headers)
        return Jsoup.parse(html, url)
    }

    /** HEAD probe to detect direct media responses (extension-less CDN URLs). */
    private suspend fun probeContentType(url: String, refererUrl: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val probeHeaders = headers + mapOf("Referer" to refererUrl)
                val headRequest = Request.Builder().url(url).headers(buildHeaders(probeHeaders)).head().build()
                cfClient.newCall(headRequest).execute().use { response ->
                    if (response.isSuccessful) return@withContext(response.header("Content-Type"))
                }
                // Fallback: ranged GET (some servers reject HEAD)
                val getRequest = Request.Builder().url(url).headers(buildHeaders(probeHeaders))
                    .header("Range", "bytes=0-0").build()
                cfClient.newCall(getRequest).execute().use { response ->
                    if (response.isSuccessful) response.header("Content-Type") else null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun buildHeaders(headerMap: Map<String, String>): Headers {
        val builder = Headers.Builder()
        for ((k, v) in headerMap) builder.add(k, v)
        return builder.build()
    }

    // ── Fix URL domain for movielinkbd links ─────────────────────────────────
    private fun fixUrlDomain(url: String, base: String): String {
        if (url.isEmpty()) return url
        return try {
            val uri = URI(url)
            val host = uri.host
            if (host == null || !host.contains("movielinkbd") || host.contains("play")) return url
            val path = uri.rawPath ?: ""
            val query = uri.rawQuery?.let { "?$it" } ?: ""
            val fragment = uri.rawFragment?.let { "#$it" } ?: ""
            "${base.trimEnd('/')}/${path.trimStart('/')}$query$fragment"
        } catch (_: Exception) {
            url
        }
    }

    // ── Resolve the live mirror URL ─────────────────────────────────────────
    private val baseCandidates = listOf(
        FALLBACK_URL,
        "https://www.movielinkbd.one",
        "https://movielinkbd.li",
        "https://open.movielinkbd.li"
    )

    private fun isValidMlbdHome(html: String): Boolean {
        if (html.length < 500) return false
        val lower = html.lowercase()
        return lower.contains("movie-card") || lower.contains("mlbd-img") ||
            lower.contains("movielinkbd") && lower.contains("<!doctype")
    }

    /** Follows the redirect chain manually so the final (rotating) host is known. */
    private suspend fun followRedirects(url: String, maxHops: Int = 6): String? {
        return withContext(Dispatchers.IO) {
            var current = url
            var hops = 0
            while (hops < maxHops) {
                hops++
                try {
                    val request = Request.Builder().url(current)
                        .header("User-Agent", headers["User-Agent"] ?: "Mozilla/5.0")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .get().build()
                    noRedirectClient.newCall(request).execute().use { response ->
                        val location = response.header("Location")
                        if (response.isRedirect && !location.isNullOrEmpty()) {
                            current = URI(current).resolve(location).toString()
                            null
                        } else {
                            current
                        }
                    }?.let { return@withContext(it) }
                } catch (_: Exception) {
                    return@withContext(null)
                }
            }
            current
        }
    }

    private suspend fun getBase(): String {
        resolvedBase?.let { return it }
        for (candidate in baseCandidates) {
            val finalUrl = try {
                followRedirects(candidate)
            } catch (_: Exception) {
                null
            } ?: continue
            val uri = try {
                URI(finalUrl)
            } catch (_: Exception) {
                continue
            }
            val host = uri.host?.lowercase() ?: continue
            if (!host.contains("movielinkbd")) continue
            val base = "${uri.scheme}://$host"
            val html = try {
                httpGetText("$base/", headers)
            } catch (_: Exception) {
                ""
            }
            if (!isValidMlbdHome(html)) continue

            resolvedBase = base
            if (base != mainUrl) mainUrl = base
            return base
        }
        return FALLBACK_URL
    }

    // ── Homepage / category pages ───────────────────────────────────────────
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = getBase()
        val path = request.data
        val url = when {
            path == "/" && page == 1 -> "$base/"
            path == "/" -> "$base/page/$page"
            page == 1 -> "$base$path"
            else -> "$base$path/page/$page"
        }
        val doc = httpGetDoc(url, headers)
        val items = parseMovieCards(doc, base)
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = true),
            hasNext = items.isNotEmpty()
        )
    }

    // ── Search ──────────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val base = getBase()
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val doc = httpGetDoc("$base/search?q=$encoded", headers)
        return parseMovieCards(doc, base)
    }

    // ── Parse movie cards from listing pages ───────────────────────────────
    private fun parseMovieCards(doc: Document, base: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val cards = doc.select("div.movie-item, div.item-box, div.film-item, div.post-item, .movie-card")
        if (cards.isNotEmpty()) {
            cards.forEach { card ->
                val aTag = card.selectFirst("a[href*='/movie/'], a[href*='/series/'], a[href*='/anime/'], a[href*='/download18plus/']") ?: return@forEach
                val href = aTag.attr("abs:href").ifEmpty { base + aTag.attr("href") }
                val title = card.selectFirst(".title, .movie-title, h3, h2")?.text()?.trim()
                    ?: aTag.attr("title").trim().takeIf { it.isNotEmpty() }
                    ?: return@forEach
                val img = card.selectFirst("img")
                val poster = img?.attr("data-src")?.ifEmpty { img.attr("src") } ?: img?.attr("src")
                val type = if (href.contains("/series/") || href.contains("/anime/")) TvType.TvSeries else TvType.Movie
                results.add(newMovieSearchResponse(title, href, type) { this.posterUrl = poster })
            }
            return results
        }

        val movieLinkPattern = "a[href*='/movie/'], a[href*='/series/'], a[href*='/anime/'], a[href*='/download18plus/']"
        val seen = mutableSetOf<String>()
        doc.select(movieLinkPattern).forEach { a ->
            val href = a.attr("abs:href").ifEmpty { base + a.attr("href") }
            if (!seen.add(href)) return@forEach
            val img = a.selectFirst("img") ?: return@forEach
            val poster = img.attr("data-src").ifEmpty { img.attr("src") }
            val titleEl = a.parent()?.selectFirst(".title, .movie-title, h3, h2, [class*='name']")
            val title = titleEl?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: a.attr("title").trim().takeIf { it.isNotEmpty() }
                ?: a.text().trim().takeIf { it.isNotEmpty() }
                ?: return@forEach
            val type = if (href.contains("/series/") || href.contains("/anime/")) TvType.TvSeries else TvType.Movie
            results.add(newMovieSearchResponse(title, href, type) {
                this.posterUrl = poster.takeIf { it.isNotEmpty() }
            })
        }

        if (results.isEmpty()) {
            doc.select(movieLinkPattern).forEach { a ->
                val href = a.attr("abs:href").ifEmpty { base + a.attr("href") }
                if (!seen.add(href)) return@forEach
                val title = a.text().trim().takeIf { it.isNotEmpty() } ?: return@forEach
                if (title.length < 4 || title.all { it.isUpperCase() || it == ' ' }) return@forEach
                val type = if (href.contains("/series/") || href.contains("/anime/")) TvType.TvSeries else TvType.Movie
                results.add(newMovieSearchResponse(title, href, type))
            }
        }
        return results
    }

    // ── Detail page ─────────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        val base = getBase()
        val doc = httpGetDoc(url, headers)
        val rawTitle = doc.selectFirst(".movie-info-view h2, h1, .movie-title, .film-title, [class*='title']")?.text()?.trim()
            ?: doc.title().substringBefore("•").trim()
        val year = Regex("\\((\\d{4})\\)").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
        val posterElement = doc.selectFirst("img.poster, img[class*='poster'], .poster img, .thumb img, img[src*='poster'], img[src*='uploads']")
        val poster = posterElement?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }.takeIf { s -> s.isNotEmpty() }
        }

        fun metaVal(label: String): String? {
            return doc.select("li, p, span, div").firstOrNull { el ->
                el.text().contains(label, ignoreCase = true)
            }?.text()?.substringAfter(":")?.trim()
        }

        val plot = doc.selectFirst(".storyline p, .storyline, [class*='story'] p, [class*='plot']")?.text()?.trim()
            ?: metaVal("Storyline")
        val genre = metaVal("Genre")
        val cast = metaVal("Cast")
        val language = metaVal("Language")
        val rating = doc.selectFirst("[class*='imdb'], [class*='rating']")?.text()
            ?.let { Regex("[0-9.]+").find(it)?.value?.toFloatOrNull() }

        val fullPlot = buildString {
            language?.let { append("Language: $it\n") }
            genre?.let { append("Genre: $it\n") }
            cast?.let { append("Cast: $it\n") }
            plot?.let { append("\n$it") }
        }.trim()

        val isSeries = url.contains("/series/") || url.contains("/anime/")

        val jsonSources = mutableListOf<StreamSource>()
        try {
            val script = doc.selectFirst("script#mlbdInlinePlayerData")
            if (script != null && script.data().isNotEmpty()) {
                val jsonObj = JSONObject(script.data())
                val episodes = jsonObj.optJSONArray("episodes")
                if (episodes != null) {
                    for (i in 0 until episodes.length()) {
                        try {
                            val ep = episodes.getJSONObject(i)
                            val epKey = ep.optString("id", "movie")
                            val epLabel = ep.optString("label", "Movie")
                            val sources = ep.optJSONArray("sources") ?: continue
                            for (j in 0 until sources.length()) {
                                val src = sources.getJSONObject(j)
                                val watchUrl = src.optString("url", "")
                                if (watchUrl.isNotEmpty()) {
                                    jsonSources.add(
                                        StreamSource(
                                            src.optInt("quality", 0),
                                            watchUrl,
                                            src.optString("name", ""),
                                            src.optString("audio", ""),
                                            epKey,
                                            epLabel
                                        )
                                    )
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {}

        val fileAnchors = doc.select("a[href*='/file/']").filterNot { isComingSoon(it) }
        val linkAnchors = doc.select("a[href*='/getLink/']").filterNot { isComingSoon(it) }
        val watchAnchors = doc.select("a[href*='/getWatch/']").filterNot { isComingSoon(it) }
        val liveServerAnchors = doc.select("a.mlbd-live-server-btn[href]")

        fun anchorLink(a: Element): String {
            val href = a.attr("abs:href").ifEmpty {
                val h = a.attr("href"); if (h.startsWith("http")) h else "$base$h"
            }
            return "${extractQualityLabel(a.text())}|${fixUrlDomain(href, base)}|$url"
        }

        fun liveServerLink(a: Element): String? {
            val h = a.attr("href").trim()
            if (h.isEmpty()) return null
            val absH = if (h.startsWith("http")) h else "$base$h"
            var label = a.text().trim()
            if (label.isEmpty()) label = "Stream"
            var ql = extractQualityLabel(label)
            if (ql.isEmpty()) ql = label.take(30)
            return "$ql|ext:$absH|$url"
        }

        if (!isSeries) {
            val items = mutableListOf<String>()
            for (src in jsonSources) {
                items.add("${sourceLabel(src)}|watch:${src.url}|$url")
            }
            for (a in fileAnchors) items.add(anchorLink(a))
            for (a in linkAnchors + watchAnchors) items.add(anchorLink(a))
            for (a in liveServerAnchors) liveServerLink(a)?.let { items.add(it) }
            val linksData = items.joinToString(" ; ")

            return newMovieLoadResponse(rawTitle, url, TvType.Movie, linksData) {
                this.posterUrl = poster
                this.year = year
                this.plot = fullPlot.takeIf { it.isNotEmpty() }
                rating?.let { this.score = com.lagradost.cloudstream3.Score.from10(it) }
            }
        }

        val episodesData = mutableListOf<Episode>()

        if (jsonSources.isNotEmpty()) {
            val grouped = jsonSources.groupBy { it.episodeKey }
            for ((epKey, srcs) in grouped) {
                val epNum = Regex("\\d+").find(epKey)?.value?.toIntOrNull() ?: 1
                val epLabel = srcs.firstOrNull()?.episodeLabel?.takeIf { it.isNotBlank() }
                    ?: "Episode $epNum"
                val epData = srcs.map { "${sourceLabel(it)}|watch:${it.url}|$url" }
                    .joinToString(" ; ")
                episodesData.add(newEpisode(epData) {
                    this.name = epLabel
                    this.season = 1
                    this.episode = epNum
                })
            }
        }

        if (episodesData.isEmpty()) {
            val epCards = doc.select("div.ep-card, [data-ep]")
            epCards.forEach { card ->
                val epText = card.attr("data-ep").ifEmpty {
                    card.selectFirst("h1, h2, h3, h4, h5, h6")?.text() ?: ""
                }
                val epNum = Regex("\\d+").find(epText)?.value?.toIntOrNull() ?: 1
                val cardLinks = card.select("a[href*='/getLink/'], a[href*='/getWatch/']")
                    .filterNot { isComingSoon(it) }
                if (cardLinks.isNotEmpty()) {
                    val epUrl = cardLinks.map { anchorLink(it) }.joinToString(" ; ")
                    mergeEpisode(episodesData, epNum, epUrl)
                }
            }
        }

        if (episodesData.isEmpty()) {
            val episodeSections = doc.select(
                "div.episode-section, div.season-section, h3:contains(Episode), h4:contains(Episode), " +
                    "h5:contains(Episode), div[class*='episode'], div[class*='season'], " +
                    "strong:contains(Ep), b:contains(Ep)"
            )
            val epRangeRegex = Regex("(?:Ep|Episode)[^\\d]*(\\d+)(?:[^\\d]+(\\d+))?", RegexOption.IGNORE_CASE)
            for (section in episodeSections) {
                val epRange = epRangeRegex.find(section.text())
                val start = epRange?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                val end = epRange?.groupValues?.getOrNull(2)?.toIntOrNull() ?: start

                val sectionLinks = mutableListOf<String>()
                var sib = section.nextElementSibling()
                while (sib != null && !Regex("h[1-6]").matches(sib.tagName())) {
                    val anchors = mutableListOf<Element>()
                    if (sib.tagName() == "a") {
                        val h = sib.attr("href")
                        if ((h.contains("/getLink/") || h.contains("/getWatch/")) && !isComingSoon(sib)) {
                            anchors.add(sib)
                        }
                    }
                    anchors.addAll(
                        sib.select("a[href*='/getLink/'], a[href*='/getWatch/']").filterNot { isComingSoon(it) }
                    )
                    sectionLinks.addAll(anchors.map { anchorLink(it) })
                    sib = sib.nextElementSibling()
                }

                if (sectionLinks.isNotEmpty()) {
                    val epUrl = sectionLinks.joinToString(" ; ")
                    for (epNum in start..end) {
                        mergeEpisode(episodesData, epNum, epUrl)
                    }
                }
            }
        }

        if (episodesData.isEmpty() && linkAnchors.isNotEmpty()) {
            val allDownload = (linkAnchors + watchAnchors).map { anchorLink(it) }
            val allLive = liveServerAnchors.mapNotNull { liveServerLink(it) }
            val allLinks = (allDownload + allLive).joinToString(" ; ")
            episodesData.add(newEpisode(allLinks) {
                this.name = "Full Season"; this.season = 1; this.episode = 1
            })
        }

        if (episodesData.size > 1) {
            episodesData.sortWith(compareBy { it.episode })
        }

        return newTvSeriesLoadResponse(rawTitle, url, TvType.TvSeries, episodesData) {
            this.posterUrl = poster
            this.year = year
            this.plot = fullPlot.takeIf { it.isNotEmpty() }
            rating?.let { this.score = com.lagradost.cloudstream3.Score.from10(it) }
        }
    }

    // ── Load links (dispatch to resolvers) ──────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!data.contains("|")) return false
        val base = getBase()
        coroutineScope {
            data.split(" ; ").map { item ->
                async {
                    try {
                        val parts = item.split("|")
                        val qualityLabel = parts.getOrNull(0)?.trim() ?: ""
                        val linkUrl = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
                            ?: item.trim()
                        if (linkUrl.isEmpty()) return@async
                        val refererUrl = parts.getOrNull(2)?.trim()?.let { fixUrlDomain(it, base) }
                            ?: base
                        val fixedLink = fixUrlDomain(linkUrl, base)
                        when {
                            fixedLink.contains("/getLink/") -> {
                                resolveGetLink(fixedLink, qualityLabel, refererUrl, callback)
                            }
                            fixedLink.contains("/getWatch/") -> {
                                resolveGetWatch(fixedLink, qualityLabel, refererUrl, callback)
                            }
                            fixedLink.contains("/file/") -> {
                                resolveDirectFile(fixedLink, qualityLabel, refererUrl, callback)
                            }
                            fixedLink.startsWith("watch:") -> {
                                resolveWatchUrl(
                                    fixedLink.removePrefix("watch:"),
                                    qualityLabel,
                                    refererUrl,
                                    subtitleCallback,
                                    callback
                                )
                            }
                            fixedLink.startsWith("ext:") -> {
                                val extUrl = fixedLink.removePrefix("ext:")
                                if (extUrl.contains("xcloud") || extUrl.contains("mcloud")) {
                                    resolveXCloud(extUrl, qualityLabel, callback)
                                } else {
                                    try {
                                        loadExtractor(extUrl, refererUrl, subtitleCallback, callback)
                                    } catch (_: Exception) {}
                                }
                            }
                            else -> {
                                loadExtractor(fixedLink, refererUrl, subtitleCallback, callback)
                            }
                        }
                    } catch (_: Exception) {}
                }
            }.awaitAll()
        }
        return true
    }

    // ── Resolve /getLink/ to direct stream URL ──────────────────────────────
    private suspend fun resolveGetLink(
        getLinkUrl: String,
        qualityLabel: String,
        refererUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val base = getBase()
            val reqHeaders = headers + mapOf("Referer" to refererUrl)
            val doc = httpGetDoc(getLinkUrl, reqHeaders)

            val fileAnchor = doc.selectFirst("a[href*='/file/']")
            if (fileAnchor != null) {
                val fileUrl = fileAnchor.attr("abs:href").ifEmpty { fileAnchor.attr("href") }
                resolveDirectFile(fileUrl, qualityLabel, getLinkUrl, callback)
            }

            doc.select("a[href]").forEach { a ->
                val href = a.attr("href").trim()
                if (href.isEmpty() || href.contains("/file/")) return@forEach
                if (href.startsWith("http") && !href.contains("movielinkbd") &&
                    !href.contains("telegram") && !href.contains("google.com/store")
                ) {
                    com.lagradost.cloudstream3.utils.loadExtractor(href, getLinkUrl, {}, callback)
                }
            }

            val videoSrc = doc.selectFirst("video source, video[src]")?.attr("src")
                ?: doc.selectFirst("iframe[src]")?.attr("src")
            if (!videoSrc.isNullOrEmpty()) {
                val streamUrl = if (videoSrc.startsWith("http")) videoSrc else "$base$videoSrc"
                val fixedStreamUrl = fixUrlDomain(streamUrl, base)
                val type = if (fixedStreamUrl.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                val quality = labelToQuality(qualityLabel)
                callback(ExtractorLink(
                    source = name,
                    name = "$name Link [$qualityLabel]",
                    url = fixedStreamUrl,
                    referer = getLinkUrl,
                    quality = quality,
                    type = type,
                    headers = headers + mapOf("Referer" to getLinkUrl)
                ))
            }
        } catch (_: Exception) { }
    }

    // ── Resolve /getWatch/ to stream URL ────────────────────────────────────
    private suspend fun resolveGetWatch(
        getWatchUrl: String,
        qualityLabel: String,
        refererUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val base = getBase()
            val requestHeaders = headers + mapOf("Referer" to refererUrl)
            val html = httpGetText(getWatchUrl, requestHeaders)
            val doc = Jsoup.parse(html, getWatchUrl)

            val watchAnchor = doc.selectFirst("a[href*='/watch/']")
            if (watchAnchor != null) {
                val watchUrl = watchAnchor.attr("abs:href").ifEmpty { watchAnchor.attr("href") }
                val fixedWatchUrl = fixUrlDomain(watchUrl, base)
                val watchHeaders = headers + mapOf("Referer" to getWatchUrl)
                val watchHtml = httpGetText(fixedWatchUrl, watchHeaders)
                val unescapedWatchHtml = watchHtml.replace("\\/", "/")
                val srcRegex = Regex("const\\s+SRC\\s*=\\s*[\"'](https?://[^\"']+)[\"']")
                val watchRegex = Regex("(https?://[^\\s'\"]+/watch/[^\\s'\"]*)")
                val m3u8Regex = Regex("(https?://[^\\s'\"]+\\.m3u8[^\\s'\"]*)")
                val mp4Regex = Regex("(https?://[^\\s'\"]+\\.(?:mp4|mkv)[^\\s'\"]*)")

                val streamUrl = srcRegex.find(unescapedWatchHtml)?.groupValues?.get(1)
                    ?: watchRegex.find(unescapedWatchHtml)?.value
                    ?: m3u8Regex.find(unescapedWatchHtml)?.value
                    ?: mp4Regex.find(unescapedWatchHtml)?.value

                if (!streamUrl.isNullOrEmpty()) {
                    val resolvedUrl = if (streamUrl.startsWith("http")) streamUrl else "$base$streamUrl"
                    val fixedStreamUrl = fixUrlDomain(resolvedUrl, base)
                    val quality = labelToQuality(qualityLabel)
                    val type = if (fixedStreamUrl.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    callback(ExtractorLink(
                        source = name,
                        name = "$name Stream [$qualityLabel]",
                        url = fixedStreamUrl,
                        referer = fixedWatchUrl,
                        quality = quality,
                        type = type,
                        headers = headers + mapOf("Referer" to fixedWatchUrl)
                    ))
                    return
                }

                val fileAnchor = doc.selectFirst("a[href*='/file/']")
                if (fileAnchor != null) {
                    val fileUrl = fileAnchor.attr("abs:href").ifEmpty { fileAnchor.attr("href") }
                    resolveDirectFile(fileUrl, qualityLabel, getWatchUrl, callback)
                    return
                }

                val videoSrc = doc.selectFirst("video source, video[src]")?.attr("src")
                    ?: doc.selectFirst("iframe[src]")?.attr("src")
                if (!videoSrc.isNullOrEmpty()) {
                    val resolvedUrl = if (videoSrc.startsWith("http")) videoSrc else "$base$videoSrc"
                    val fixedResolvedUrl = fixUrlDomain(resolvedUrl, base)
                    if (fixedResolvedUrl.contains("xcloud")) {
                        resolveXCloud(fixedResolvedUrl, qualityLabel, callback)
                    } else {
                        val quality = labelToQuality(qualityLabel)
                        val type = if (fixedResolvedUrl.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        callback(ExtractorLink(
                            source = name,
                            name = "$name Stream [$qualityLabel]",
                            url = fixedResolvedUrl,
                            referer = getWatchUrl,
                            quality = quality,
                            type = type,
                            headers = headers + mapOf("Referer" to getWatchUrl)
                        ))
                    }
                    return
                }
            }

            val videoSrc2 = doc.selectFirst("video source, video[src]")?.attr("src")
                ?: doc.selectFirst("iframe[src]")?.attr("src")
            if (!videoSrc2.isNullOrEmpty()) {
                val resolvedUrl = if (videoSrc2.startsWith("http")) videoSrc2 else "$base$videoSrc2"
                val fixedResolvedUrl = fixUrlDomain(resolvedUrl, base)
                if (fixedResolvedUrl.contains("xcloud")) {
                    resolveXCloud(fixedResolvedUrl, qualityLabel, callback)
                } else {
                    val quality = labelToQuality(qualityLabel)
                    val type = if (fixedResolvedUrl.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    callback(ExtractorLink(
                        source = name,
                        name = "$name Stream [$qualityLabel]",
                        url = fixedResolvedUrl,
                        referer = getWatchUrl,
                        quality = quality,
                        type = type,
                        headers = headers + mapOf("Referer" to getWatchUrl)
                    ))
                }
            }
        } catch (_: Exception) { }
    }

    // ── Resolve a watch: URL (inline player data) to stream URL ─────────────
    private suspend fun resolveWatchUrl(
        watchUrl: String,
        qualityLabel: String,
        refererUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val isKnownMediaExt = watchUrl.contains(".m3u8") ||
                watchUrl.contains(".mp4") || watchUrl.contains(".mkv")

            // Inline player data provides direct CDN URLs without file
            // extensions (e.g. cdn.dramalinkbd.tv/p/HASH). Probe content type
            // and emit them as direct streams instead of parsing as HTML.
            if (!isKnownMediaExt) {
                val contentType = probeContentType(watchUrl, refererUrl)
                val lower = contentType?.lowercase()
                if (lower != null && (
                    lower.startsWith("video/") || lower.startsWith("audio/") ||
                        lower.contains("mpegurl") || lower.contains("octet-stream")
                    )
                ) {
                    val type = if (lower.contains("mpegurl")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    val quality = labelToQuality(qualityLabel)
                    callback(ExtractorLink(
                        source = name,
                        name = "$name [$qualityLabel]",
                        url = watchUrl,
                        referer = refererUrl,
                        quality = quality,
                        type = type,
                        headers = headers + mapOf("Referer" to refererUrl)
                    ))
                    return
                }
            } else {
                val type = if (watchUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                val quality = labelToQuality(qualityLabel)
                callback(ExtractorLink(
                    source = name,
                    name = "$name [$qualityLabel]",
                    url = watchUrl,
                    referer = refererUrl,
                    quality = quality,
                    type = type,
                    headers = headers + mapOf("Referer" to refererUrl)
                ))
                return
            }

            val html = httpGetText(watchUrl, headers + mapOf("Referer" to refererUrl))
            val unescaped = html.replace("\\/", "/")
            val srcRegex = Regex("const\\s+SRC\\s*=\\s*[\"'](https?://[^\"']+)[\"']")
            val m3u8Regex = Regex("(https?://[^\\s'\"<>]+\\.m3u8[^\\s'\"<>]*)")
            val mp4Regex = Regex("(https?://[^\\s'\"<>]+\\.(?:mp4|mkv)[^\\s'\"<>]*)")

            val streamUrl = srcRegex.find(unescaped)?.groupValues?.get(1)
                ?: m3u8Regex.find(unescaped)?.value
                ?: mp4Regex.find(unescaped)?.value

            if (!streamUrl.isNullOrEmpty()) {
                val type = if (streamUrl.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                val quality = labelToQuality(qualityLabel)
                callback(ExtractorLink(
                    source = name,
                    name = "$name [$qualityLabel]",
                    url = streamUrl,
                    referer = refererUrl,
                    quality = quality,
                    type = type,
                    headers = headers + mapOf("Referer" to refererUrl)
                ))
            } else {
                loadExtractor(watchUrl, refererUrl, subtitleCallback, callback)
            }
        } catch (_: Exception) {}
    }

    // ── Resolve XCloud player URL ───────────────────────────────────────────
    private suspend fun resolveXCloud(
        xcloudUrl: String,
        qualityLabel: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val TAG = "XCloud"
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        try {
            val streamPlayerUrl = xcloudUrl
            val simpleHeaders = mapOf(
                "User-Agent" to userAgent,
                "Accept-Language" to "en-US,en;q=0.9",
                "Referer" to streamPlayerUrl
            )
            val tryUrls = listOf(
                streamPlayerUrl,
                streamPlayerUrl.replace("https://", "https://www.")
            )
            val quality = labelToQuality(qualityLabel)

            for (tryUrl in tryUrls) {
                try {
                    val html = httpGetText(tryUrl, simpleHeaders)
                    val result = resolveXCloudExtractStreamUrl(TAG, qualityLabel, streamPlayerUrl, html, "DIRECT")
                    if (!result.isNullOrEmpty()) {
                        val streamUrl = if (result.startsWith("http")) result else {
                            try {
                                val u = URI(streamPlayerUrl)
                                "${u.scheme}://${u.host}$result"
                            } catch (_: Exception) { result }
                        }
                        val type = if (streamUrl.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        callback(ExtractorLink(
                            source = name,
                            name = "$name XCloud [$qualityLabel]",
                            url = streamUrl,
                            referer = streamPlayerUrl,
                            quality = quality,
                            type = type,
                            headers = simpleHeaders
                        ))
                        return
                    }
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
    }

    private fun resolveXCloudExtractStreamUrl(
        TAG: String, qualityLabel: String, streamPlayerUrl: String,
        html: String, source: String
    ): String? {
        if (html.length < 100) return null
        val unescaped = html.replace("\\/", "/")
        Log.d(TAG, "[$qualityLabel][$source] len=${html.length} hasSRC=${html.contains("SRC")} hasM3u8=${html.contains("m3u8")} hasMp4=${html.contains(".mp4")}")

        val srcRegex = Regex("const\\s+SRC\\s*=\\s*[\"'](https?://[^\"']+)[\"']")
        val fileRegex = Regex("(?:file|src)\\s*:\\s*[\"'](https?://[^\"']+\\.(?:m3u8|mp4|mkv)[^\"']*)")
        val m3u8Regex = Regex("(https?://[^\\s'\"<>]+\\.m3u8[^\\s'\"<>]*)")
        val mp4Regex = Regex("(https?://[^\\s'\"<>]+\\.(?:mp4|mkv)[^\\s'\"<>]*)")
        val redirectRegex = Regex("file\\s*:\\s*[\"'](/apis/redirect/[^\"']+)")

        val found = srcRegex.find(unescaped)?.groupValues?.get(1)
            ?: fileRegex.find(unescaped)?.groupValues?.get(1)
            ?: m3u8Regex.find(unescaped)?.value
            ?: mp4Regex.find(unescaped)?.value

        if (!found.isNullOrEmpty()) {
            Log.d(TAG, "[$qualityLabel][$source] found: $found")
            return found
        }

        val redirectPath = redirectRegex.find(unescaped)?.groupValues?.get(1)
        if (!redirectPath.isNullOrEmpty()) {
            try {
                val uri = URI(streamPlayerUrl)
                val full = "${uri.scheme}://${uri.host}$redirectPath"
                Log.d(TAG, "[$qualityLabel][$source] redirect: $full")
                return full
            } catch (_: Exception) { }
        }

        Log.d(TAG, "[$qualityLabel][$source] no stream found. snippet: ${html.take(300)}")
        return null
    }

    // ── Resolve /file/ to direct stream URL ─────────────────────────────────
    private suspend fun resolveDirectFile(
        fileUrl: String,
        qualityLabel: String,
        refererUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val base = getBase()
            val requestHeaders = headers + mapOf("Referer" to refererUrl)
            val html = httpGetText(fileUrl, requestHeaders)
            val unescapedHtml = html.replace("\\/", "/")
            val srcRegex = Regex("const\\s+SRC\\s*=\\s*[\"'](https?://[^\"']+)[\"']")
            val watchRegex = Regex("(https?://[^\\s'\"]+/watch/[^\\s'\"]*)")
            val m3u8Regex = Regex("(https?://[^\\s'\"]+\\.m3u8[^\\s'\"]*)")
            val mp4Regex = Regex("(https?://[^\\s'\"]+\\.(?:mp4|mkv)[^\\s'\"]*)")

            val streamUrl = srcRegex.find(unescapedHtml)?.groupValues?.get(1)
                ?: watchRegex.find(unescapedHtml)?.value
                ?: m3u8Regex.find(unescapedHtml)?.value
                ?: mp4Regex.find(unescapedHtml)?.value

            if (!streamUrl.isNullOrEmpty()) {
                val resolvedUrl = if (streamUrl.startsWith("http")) streamUrl else "$base$streamUrl"
                val fixedStreamUrl = fixUrlDomain(resolvedUrl, base)
                val quality = labelToQuality(qualityLabel)
                val type = if (fixedStreamUrl.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback(ExtractorLink(
                    source = name,
                    name = "$name Direct [$qualityLabel]",
                    url = fixedStreamUrl,
                    referer = fileUrl,
                    quality = quality,
                    type = type,
                    headers = headers + mapOf("Referer" to fileUrl)
                ))
            }
        } catch (_: Exception) { }
    }

    // ── Quality label helpers ───────────────────────────────────────────────
    private fun extractQualityLabel(text: String): String {
        return when {
            text.contains("4K", true) || text.contains("2160", true) -> "4K"
            text.contains("1080", true) -> "1080p"
            text.contains("720p HEVC", true) || text.contains("720 HEVC", true) -> "720p HEVC"
            text.contains("720", true) -> "720p"
            text.contains("480", true) -> "480p"
            text.contains("360", true) -> "360p"
            text.contains("Watch Online", true) -> "Stream"
            text.contains("Download", true) -> "Download"
            else -> text.take(30).trim().ifEmpty { "Unknown" }
        }
    }

    private fun labelToQuality(label: String): Int {
        return when {
            label.contains("4K", true) || label.contains("2160", true) -> Qualities.P2160.value
            label.contains("1080", true) -> Qualities.P1080.value
            label.contains("720", true) -> Qualities.P720.value
            label.contains("480", true) -> Qualities.P480.value
            label.contains("360", true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private data class StreamSource(
        val quality: Int,
        val url: String,
        val name: String,
        val audio: String,
        val episodeKey: String,
        val episodeLabel: String
    )

    private fun sourceLabel(src: StreamSource): String {
        val audio = src.audio.trim().takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
        return "${qualityFromInt(src.quality)}$audio"
    }

    private fun isComingSoon(a: Element): Boolean {
        val t = a.text().trim().lowercase()
        return t.contains("coming soon") || t == "soon" || t.contains("not available")
    }

    private fun qualityFromInt(q: Int): String {
        return when {
            q >= 2160 -> "4K"
            q >= 1080 -> "1080p"
            q >= 720 -> "720p"
            q >= 480 -> "480p"
            q >= 360 -> "360p"
            else -> "Stream"
        }
    }

    private fun mergeEpisode(episodesData: MutableList<Episode>, epNum: Int, epUrl: String) {
        val existing = episodesData.firstOrNull { it.episode == epNum && it.season == 1 }
        if (existing != null) {
            existing.data = if (existing.data.isNullOrEmpty()) epUrl else "${existing.data} ; $epUrl"
        } else {
            episodesData.add(newEpisode(epUrl) {
                this.name = "Episode $epNum"
                this.season = 1
                this.episode = epNum
            })
        }
    }
}
