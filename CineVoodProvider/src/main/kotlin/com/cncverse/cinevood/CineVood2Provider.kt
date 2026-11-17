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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class CineVood2Provider : MainAPI() {
    override var mainUrl = "https://cinevood.net"
    override var name = "CineVood"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private var resolvedDomain = false
    private val resolveLock = Mutex()

    private suspend fun site(): String = resolveLock.withLock {
        if (!resolvedDomain) {
            runCatching { app.get("https://cinevood.net/", timeout = 15L).url }.getOrNull()
                ?.let { Regex("https://[^/]+").find(it)?.value }
                ?.takeIf { it.contains("cinevood") }
                ?.let { mainUrl = it }
            resolvedDomain = true
        }
        mainUrl
    }

    // Rows other than "Latest" are search queries against the site's search API.
    override val mainPage = mainPageOf(
        "" to "Latest",
        "bollywood" to "Bollywood",
        "hollywood" to "Hollywood",
        "south dubbed" to "South Hindi Dubbed",
        "web series" to "Web Series",
        "tamil" to "Tamil",
        "telugu" to "Telugu",
        "malayalam" to "Malayalam",
        "punjabi" to "Punjabi",
    )

    private val hiddenCategories = setOf("18+ Adult", "XXX", "Trailer")
    private val seriesTag = Regex("""(?i)\bS\d{1,2}\b|\bS\d{1,2}E\d|\bseason\b""")
    private val hosts = Regex("hubcloud|filepress|filebee|gdflix|gdlink")

    private fun cleanTitle(raw: String) = Regex("""\s*CineVood\s*$""", RegexOption.IGNORE_CASE).replace(raw, "").trim()

    private fun result(title: String, url: String, poster: String?, series: Boolean): SearchResponse =
        if (series) {
            newTvSeriesSearchResponse(cleanTitle(title), url, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(cleanTitle(title), url, TvType.Movie) {
                this.posterUrl = poster
                this.year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.get(1)?.toIntOrNull()
            }
        }

    private suspend fun apiSearch(query: String, page: Int): List<SearchResponse> {
        val base = site()
        val json = runCatching {
            JSONObject(
                app.get(
                    "$base/search.php",
                    headers = mapOf(
                        "Referer" to "$base/search.html?q=${URLEncoder.encode(query, "UTF-8")}",
                        "User-Agent" to browserHeaders.getValue("User-Agent"),
                    ),
                    params = mapOf("q" to query, "page" to page.toString()),
                ).text
            )
        }.getOrNull() ?: return emptyList()
        return json.optJSONArray("hits")?.objects()
            ?.mapNotNull { it.optJSONObject("document") }
            ?.filter { d ->
                val cats = d.optJSONArray("category")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
                cats.none { it in hiddenCategories } && d.optString("post_type") == "post"
            }
            ?.sortedByDescending { it.optLong("sort_by_date") }
            ?.distinctBy { it.optString("permalink") }
            ?.map { d ->
                val title = d.optString("post_title")
                val cats = d.optJSONArray("category")?.toString() ?: ""
                result(
                    title,
                    base + d.optString("permalink"),
                    d.optString("post_thumbnail").ifEmpty { null },
                    seriesTag.containsMatchIn(title) || cats.contains("Web Series") || cats.contains("TV Shows"),
                )
            } ?: emptyList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data.isNotEmpty()) {
            val items = apiSearch(request.data, page)
            return newHomePageResponse(HomePageList(request.name, items), items.isNotEmpty())
        }
        if (page > 1) return newHomePageResponse(HomePageList(request.name, emptyList()), false)
        val items = app.get("${site()}/").document.select("article.latestPost").mapNotNull { a ->
            val link = a.selectFirst("h2.title a, a[title]") ?: return@mapNotNull null
            val title = link.attr("title").ifEmpty { link.text() }
            result(title, link.attr("href"), a.selectFirst("img")?.attr("src"), seriesTag.containsMatchIn(title))
        }
        return newHomePageResponse(HomePageList(request.name, items), false)
    }

    override suspend fun search(query: String): List<SearchResponse> =
        (apiSearch(query, 1) + apiSearch(query, 2)).distinctBy { it.url }

    override suspend fun load(url: String): LoadResponse {
        TitleMeta.recommendationQuery(url)?.let { (query, year) ->
            val hits = search(query)
            val hit = hits.firstOrNull { year.isNotEmpty() && it.name.contains("($year)") } ?: hits.firstOrNull()
                ?: throw ErrorLoadingException("\"$query\" isn't on CineVood")
            return load(hit.url)
        }

        val doc = getProtected(url, "${site()}/").document
        val rawTitle = doc.selectFirst("h1")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content") ?: ""
        val title = cleanTitle(rawTitle)
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val year = Regex("""\((\d{4})\)""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""\b(19\d{2}|20\d{2})\b""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
        val imdbId = doc.selectFirst("a[href*=imdb.com/title/]")?.attr("href")
            ?.let { Regex("""tt\d+""").find(it)?.value }
        val series = seriesTag.containsMatchIn(rawTitle)
        val searchName = Regex("""(?i)\bS\d{1,2}\b.*|\bseason\b.*""").replace(rawTitle.substringBefore("("), "").trim()
        val meta = TitleMeta.fetch(series, imdbId, searchName, year)

        // Each h6 names the file whose host links follow it.
        val files = mutableListOf<Btn>()
        var label = ""
        doc.select(".thecontent h6, .thecontent a[href], article h6, article a[href]").distinct().forEach { el ->
            if (el.tagName() == "h6") {
                label = el.text().trim()
            } else if (label.isNotEmpty() && !label.contains(".zip", true)) {
                val href = el.attr("href")
                if (hosts.containsMatchIn(href) || href.contains("justpaste.it")) files.add(Btn(label, href))
            }
        }

        if (!series) {
            return newMovieLoadResponse(title, url, TvType.Movie, files.filter { hosts.containsMatchIn(it.url) }.toJson()) {
                meta.applyTo(this@CineVood2Provider, this, imdbId, poster, null, null, year, null)
            }
        }

        val episodeTag = Regex("""(?i)\bS(\d{1,2})\s*E(\d{1,3})\b""")
        val byEpisode = ConcurrentHashMap<Pair<Int, Int>, MutableList<Btn>>()

        fun add(key: Pair<Int, Int>, btn: Btn) =
            byEpisode.getOrPut(key) { Collections.synchronizedList(mutableListOf()) }.add(btn)

        files.amap { file ->
            if (file.url.contains("justpaste.it")) {
                // Paste pages list HubCloud links under SxxEyy headings.
                val html = runCatching { app.get(file.url, headers = browserHeaders).text }.getOrNull() ?: return@amap
                Regex("""https?://hubcloud\.[a-z]+/drive/[A-Za-z0-9_-]+""").findAll(html)
                    .map { it.value to it.range.first }
                    .distinctBy { it.first }
                    .forEach { (link, at) ->
                        val before = Regex("<[^>]+>").replace(html.substring(maxOf(0, at - 400), at), " ")
                        episodeTag.findAll(before).lastOrNull()?.let { tag ->
                            add(tag.groupValues[1].toInt() to tag.groupValues[2].toInt(), Btn(file.label, link))
                        }
                    }
            } else {
                episodeTag.find(file.label)?.let {
                    add(it.groupValues[1].toInt() to it.groupValues[2].toInt(), file)
                }
            }
        }

        val episodes = byEpisode.entries.sortedWith(compareBy({ it.key.first }, { it.key.second })).map { (key, links) ->
            newEpisode(links.distinctBy { it.url }.toJson()) {
                this.season = key.first
                this.episode = key.second
                this.name = "Episode ${key.second}"
            }.also { meta.decorate(it, key.first, key.second) }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            meta.applyTo(this@CineVood2Provider, this, imdbId, poster, null, null, year, null)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val files = tryParseJson<List<Btn>>(data) ?: return false
        var found = false
        files.distinctBy { it.url }.amap { file ->
            val ok = when {
                file.url.contains("hubcloud") -> hubCloud(file.url, file.label, callback)
                file.url.contains("gdflix") || file.url.contains("gdlink") -> gdFlix(file.url, file.label, callback)
                file.url.contains("filepress") || file.url.contains("filebee") -> {
                    var any = false
                    Filepress().getUrl(file.url, file.label, subtitleCallback) {
                        any = true
                        callback(it)
                    }
                    any
                }
                else -> false
            }
            if (ok) found = true
        }
        return found
    }
}
