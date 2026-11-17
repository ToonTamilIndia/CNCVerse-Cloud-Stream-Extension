package com.cncverse.cinevood

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64DecodeArray
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
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class CineVoodProvider : MainAPI() {
    override var mainUrl = "https://one.1cinevood.beer"
    override var name = "CineVood Mirror"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private var resolvedDomain = false
    private val resolveLock = Mutex()

    // The mirror hops domains; the landing pages link to the live one.
    private suspend fun site(): String = resolveLock.withLock {
        if (!resolvedDomain) {
            for (landing in listOf("https://cinevood.garden/", "https://1cinevood.best/")) {
                val live = runCatching {
                    val res = app.get(landing, timeout = 15L)
                    val host = Regex("https://[^/]+").find(res.url)?.value ?: return@runCatching null
                    val doc = res.document
                    if (doc.selectFirst("article.latestpost") != null) host
                    else doc.select("a[href*=cinevood]")
                        .mapNotNull { Regex("^https://[^/?#]+").find(it.attr("href"))?.value }
                        .firstOrNull { it.contains("cinevood") && it != host }
                }.getOrNull()
                if (live != null && !live.contains("cinevood.garden")) {
                    mainUrl = live
                    resolvedDomain = true
                    break
                }
            }
        }
        mainUrl
    }

    override val mainPage = mainPageOf(
        "" to "Latest",
        "category/bollywood-movies" to "Bollywood",
        "category/hollywood-movies" to "Hollywood",
        "category/hindi-dubbed-movies" to "Hindi Dubbed",
        "category/south-indian-movies" to "South Indian",
        "category/dual-audio-movies" to "Dual Audio",
        "category/web-series" to "Web Series",
        "category/tv-shows" to "TV Shows",
        "category/korean-series" to "Korean Series",
        "category/punjabi-movies" to "Punjabi",
    )

    private val adultCategories = Regex("""\bcategory-(?:erotic|hot|adult[\w-]*|18[\w-]*)\b""")
    private val adultTitle = Regex("""(?i)\badult\b|short film|uncut|\bnsfw\b""")

    private fun cleanTitle(raw: String) = raw.removePrefix("Download ")
        .substringBefore(" Full Movie")
        .substringBefore(" Tv-Show")
        .substringBefore(" Web-Series")
        .substringBefore(" 480P")
        .substringBefore(" 720P")
        .trim()

    private fun Element.toResult(): SearchResponse? {
        if (adultCategories.containsMatchIn(attr("class"))) return null
        val link = selectFirst("h2.entry-title a, a#featured-thumbnail") ?: return null
        val title = link.attr("title").ifEmpty { link.text() }
        if (adultTitle.containsMatchIn(title)) return null
        val series = isSeries(title, attr("class"))
        val poster = selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
        return if (series) {
            newTvSeriesSearchResponse(cleanTitle(title), link.attr("href"), TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(cleanTitle(title), link.attr("href"), TvType.Movie) {
                this.posterUrl = poster
                this.year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.get(1)?.toIntOrNull()
            }
        }
    }

    private fun isSeries(title: String, classes: String = "") =
        Regex("""(?i)\bseason\b|\(s\d+\)|tv-show|web-series|episode""").containsMatchIn(title) ||
            Regex("category-(?:web-series|tv-shows|dual-audio-series|korean-series)").containsMatchIn(classes)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = site()
        val path = listOf(request.data, if (page > 1) "page/$page" else "").filter { it.isNotEmpty() }.joinToString("/")
        val doc = app.get("$base/" + if (path.isEmpty()) "" else "$path/").document
        val items = doc.select("article.latestpost, article.post").mapNotNull { it.toResult() }
        return newHomePageResponse(HomePageList(request.name, items), items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> =
        app.get("${site()}/", params = mapOf("s" to query)).document
            .select("article.latestpost, article.post").mapNotNull { it.toResult() }

    private data class Entry(val season: Int, val episode: Int, val last: Int, val name: String?)

    override suspend fun load(url: String): LoadResponse {
        TitleMeta.recommendationQuery(url)?.let { (query, year) ->
            val hits = search(query)
            val hit = hits.firstOrNull { year.isNotEmpty() && it.url.contains(year) } ?: hits.firstOrNull()
                ?: throw ErrorLoadingException("\"$query\" isn't on CineVood Mirror")
            return load(hit.url)
        }

        val doc = app.get(url).document
        val rawTitle = doc.selectFirst("h1.entry-title, h1.title")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content") ?: ""
        val title = cleanTitle(rawTitle)
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst(".thecontent img, .entry-content img")?.attr("src")
        val plot = doc.selectFirst(".mfx-plot-box")?.let { box ->
            (box.selectFirst("p") ?: box).text().substringBefore("🎬").trim().ifEmpty { null }
        } ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
        val year = Regex("""\((\d{4})\)""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
        val tags = doc.select("meta[property=article:section]").map { it.attr("content") }
            .filterNot { Regex("""(?i)^\d+p|^\d{4}$|4k""").containsMatchIn(it) }.distinct()
        val imdb = doc.selectFirst("a[href*=imdb.com/title/]")?.attr("href")
        val trailer = Regex("""youtube(?:-nocookie)?\.com/embed/([\w-]{11})""").find(doc.html())?.groupValues?.get(1)
        val series = isSeries(rawTitle)
        val imdbId = imdb?.let { Regex("""tt\d+""").find(it)?.value }
        val searchName = title.substringBefore("(").substringBefore(" Season").trim()
        val meta = TitleMeta.fetch(series, imdbId, searchName, year)
        val siteTrailer = trailer?.let { "https://www.youtube.com/watch?v=$it" }

        val groups = doc.select(".mfx-download-group").mapNotNull { group ->
            val label = group.selectFirst(".mfx-quality-title, h3")?.text() ?: ""
            group.select("a.mfx-download-link[href]").map { Btn(label, it.attr("href")) }.ifEmpty { null }
        }.flatten()

        if (!series) {
            return newMovieLoadResponse(title, url, TvType.Movie, groups.toJson()) {
                meta.applyTo(this@CineVoodProvider, this, imdbId, poster, plot, tags, year, siteTrailer)
            }
        }

        val postSeason = Regex("""(?i)season\s*(\d+)|\(s(\d+)\)""").find(rawTitle)
            ?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull() ?: 1
        val seasonTag = Regex("""(?i)\bS(\d{1,2})(?:\b|E)|season\s*(\d+)""")
        val rangeTag = Regex("""(?i)\bE(?:P|pisode)?\s*(\d+)\s*(?:to|-|–|~)\s*E?(?:P|pisode)?\s*(\d+)""")
        val singleTag = Regex("""(?i)(?:\bE|\bEP|episode)\s*(\d+)\b""")
        val entries = ConcurrentHashMap<Entry, MutableList<Btn>>()
        val packs = Collections.synchronizedList(mutableListOf<Pair<String, List<Btn>>>())

        fun seasonOf(vararg texts: String): Int = texts.firstNotNullOfOrNull { t ->
            seasonTag.find(t)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull()
        } ?: postSeason

        fun add(entry: Entry, link: Btn) =
            entries.getOrPut(entry) { Collections.synchronizedList(mutableListOf()) }.add(link)

        groups.distinctBy { it.url }.amap { btn ->
            val (lockerTitle, html) = locker(btn.url) ?: return@amap
            val season = seasonOf(lockerTitle, btn.label)
            val els = Jsoup.parse(html).select("h1,h2,h3,h4,h5,a[href]")
            // Lockers that list episodes under headings map each link to the heading above it.
            if (els.any { it.tagName() != "a" && Regex("""(?i)episode\D{0,3}\d+""").containsMatchIn(it.text()) }) {
                var ep: Int? = null
                els.forEach { el ->
                    if (el.tagName() != "a") {
                        Regex("""(?i)episode\D{0,3}(\d+)""").find(el.text())?.let { ep = it.groupValues[1].toInt() }
                    } else {
                        ep?.let { add(Entry(season, it, it, null), Btn("${btn.label} | ${el.text()}", el.attr("href"))) }
                    }
                }
                return@amap
            }
            val links = els.filter { it.tagName() == "a" }.map { Btn("${btn.label} | ${it.text()}", it.attr("href")) }
            if (links.isEmpty()) return@amap
            val range = rangeTag.find(lockerTitle) ?: rangeTag.find(btn.label)
            val single = singleTag.find(lockerTitle) ?: singleTag.find(btn.label)
            when {
                range != null -> {
                    val (a, b) = range.groupValues[1].toInt() to range.groupValues[2].toInt()
                    links.forEach { add(Entry(season, a, b, "Episodes $a–$b"), it) }
                }
                single != null -> {
                    val n = single.groupValues[1].toInt()
                    links.forEach { add(Entry(season, n, n, null), it) }
                }
                else -> packs.add(lockerTitle.ifEmpty { btn.label } to links)
            }
        }

        // Season packs without episode numbers go after the numbered episodes of their season.
        val placed = entries.keys.toList()
        packs.groupBy { it.first }.entries.forEachIndexed { i, (label, group) ->
            val season = seasonOf(label)
            val ep = (placed.filter { it.season == season }.maxOfOrNull { it.last } ?: 0) + i + 1
            group.flatMap { it.second }.forEach { add(Entry(season, ep, ep, label), it) }
        }

        val episodes = entries.entries.sortedWith(compareBy({ it.key.season }, { it.key.episode })).map { (entry, links) ->
            newEpisode(links.distinctBy { it.url }.toJson()) {
                this.season = entry.season
                this.episode = entry.episode
                this.name = entry.name ?: "Episode ${entry.episode}"
            }.also {
                if (entry.episode == entry.last && entry.name == null) meta.decorate(it, entry.season, entry.episode)
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            meta.applyTo(this@CineVoodProvider, this, imdbId, poster, plot, tags, year, siteTrailer)
        }
    }

    private suspend fun lockerHtml(url: String): String? = locker(url)?.second

    // Link lockers embed the real links as base64 in `const encoded`.
    private suspend fun locker(url: String): Pair<String, String>? = runCatching {
        val page = getProtected(url, "$mainUrl/").text
        Regex("""const\s+encoded\s*=\s*"([^"]+)"""").find(page)?.groupValues?.get(1)?.let { encoded ->
            val title = Jsoup.parse(page).title().substringBefore(" – ").substringBefore(" &#8211; ")
            title to String(base64DecodeArray(encoded.replace("\\", "")), Charsets.UTF_8)
        }
    }.getOrNull()

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val btns = tryParseJson<List<Btn>>(data) ?: return false
        val files = btns.amap { btn ->
            if (btn.url.contains("mobilejsr") || btn.url.contains("/genxfm")) {
                val html = lockerHtml(btn.url) ?: return@amap emptyList()
                Jsoup.parse(html).select("a[href]").map { Btn("${btn.label} | ${it.text()}", it.attr("href")) }
            } else {
                listOf(btn)
            }
        }.flatten().distinctBy { it.url }

        var found = false
        files.amap { file ->
            val quality = getIndexQuality(file.label)
            val label = file.label.substringBefore(" | ")
            when {
                file.url.contains("filebee") || file.url.contains("filepress") ->
                    Filepress().getUrl(file.url, label, subtitleCallback) {
                        found = true
                        callback(it)
                    }
                file.url.contains("fastdl") -> gDirect(file.url)?.let { link ->
                    callback(newExtractorLink(name, "$name G-Direct [$label]", link, ExtractorLinkType.VIDEO) {
                        this.quality = quality
                    })
                    found = true
                }
                file.url.contains("mcloud") -> mCloud(file.url).forEach { (server, link) ->
                    callback(newExtractorLink(name, "$name M-Cloud $server [$label]", link, ExtractorLinkType.VIDEO) {
                        this.quality = quality
                    })
                    found = true
                }
                else -> loadExtractor(file.url, "$mainUrl/", subtitleCallback) {
                    found = true
                    callback(it)
                }
            }
        }
        return found
    }

    private suspend fun gDirect(url: String): String? = runCatching {
        val page = getProtected(url, "$mainUrl/").text
        Regex("""var\s+reurl\s*=\s*"[^"]*?[?&]link=(https?://[^"]+)"""").find(page)?.groupValues?.get(1)
            ?: Jsoup.parse(page).select("a[href]").map { it.attr("href") }
                .firstOrNull { it.contains("googleusercontent.com") }
    }.getOrNull()

    private suspend fun mCloud(url: String): List<Pair<String, String>> = runCatching {
        val page = getProtected(url, "$mainUrl/").text
        val encoded = Regex("""atob\(atob\('([^']+)'\)\)""").find(page)?.groupValues?.get(1)
            ?: return@runCatching emptyList()
        val host = URI(url).let { "${it.scheme}://${it.host}" }
        val decoded = String(base64DecodeArray(String(base64DecodeArray(encoded), Charsets.UTF_8)), Charsets.UTF_8)
        val next = Regex("""https?://(?:www\.)?vcloud\.beer""").replace(decoded, host)
        getProtected(next, url).document.select("a[href]").mapNotNull { a ->
            val href = a.attr("href")
            val server = Regex("""\[([^\]]+)]""").find(a.text())?.groupValues?.get(1)
            val pixeldrain = Regex("""pixeldrain\.(?:com|dev)/(?:u|api/file)/([A-Za-z0-9]+)""").find(href)?.groupValues?.get(1)
            when {
                pixeldrain != null -> (server ?: "Pixeldrain") to "https://pixeldrain.dev/api/file/$pixeldrain?download"
                href.contains("r2.dev") || href.contains("r2.cloudflarestorage.com") ->
                    (server ?: "FSL") to href.replace(" ", "%20")
                else -> null
            }
        }.distinctBy { it.second }
    }.getOrElse { emptyList() }
}
