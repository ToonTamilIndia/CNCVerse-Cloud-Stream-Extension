package com.horis.cncverse

import android.content.Context
import com.horis.cncverse.entities.EpisodesData
import com.horis.cncverse.entities.PostData
import com.horis.cncverse.entities.SearchData
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.APIHolder.unixTime

class PrimeVideoMirrorProvider : MainAPI() {
    companion object {
        var context: Context? = null
    }

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )
    override var lang = "ta"

    override var mainUrl = "https://net52.cc"
    override var name = "PrimeVideoM"

    override val hasMainPage = true
    private var cookie_value = ""
    private val headers = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "en-IN,en-US;q=0.9,en;q=0.8",
        "Cache-Control" to "max-age=0",
        "Connection" to "keep-alive",
        "sec-ch-ua" to "\"Not(A:Brand\";v=\"8\", \"Chromium\";v=\"144\", \"Android WebView\";v=\"144\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Android\"",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 5 Build/TQ3A.230901.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/144.0.7559.132 Safari/537.36 /OS.Gatu v3.0",
        "X-Requested-With" to "XMLHttpRequest"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        primeBypass(mainUrl)
        val data = app.get("$mainUrl/pv/homepage.php", headers = headers).parsed<PvHomepageData>()
        val homePageLists = mutableListOf<HomePageList>()

        data.slider?.let { sliders ->
            val sliderItems = sliders.mapNotNull { item ->
                newAnimeSearchResponse(item.title ?: "", Id(item.id).toJson()) {
                    posterUrl = "https://imgcdn.kim/pv/v/${item.id}.jpg"
                    posterHeaders = mapOf("Referer" to "$mainUrl/home")
                }
            }
            if (sliderItems.isNotEmpty()) {
                homePageLists.add(HomePageList("Featured", sliderItems, isHorizontalImages = true))
            }
        }

        data.post?.forEach { cat ->
            val idList = cat.ids.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val items = idList.map { id ->
                newAnimeSearchResponse("", Id(id).toJson()) {
                    posterUrl = "https://imgcdn.kim/pv/v/$id.jpg"
                    posterHeaders = mapOf("Referer" to "$mainUrl/home")
                }
            }
            if (items.isNotEmpty()) {
                homePageLists.add(HomePageList(cat.cate, items, isHorizontalImages = false))
            }
        }

        return newHomePageResponse(homePageLists, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        primeBypass(mainUrl)
        val url = "$mainUrl/pv/search.php?s=$query"
        val data = app.get(url, headers = headers, referer = "$mainUrl/home").parsed<SearchData>()

        return data.searchResult.map {
            newAnimeSearchResponse(it.t, Id(it.id).toJson()) {
                posterUrl = "https://imgcdn.kim/pv/v/${it.id}.jpg"
                posterHeaders = mapOf("Referer" to "$mainUrl/home")
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        cookie_value = ensureBypass(mainUrl)
        val id = parseJson<Id>(url).id
        val cookies = mapOf(
            "t_hash_t" to cookie_value,
            "hd" to "on",
            "ott" to "pv"
        )
        val data = app.get(
            "$mainUrl/mobile/pv/post.php?id=$id&t=${APIHolder.unixTime}",
            headers,
            referer = "$mainUrl/home",
            cookies = cookies
        ).parsed<PostData>()

        val episodes = arrayListOf<Episode>()

        val title = data.title
        val castList = data.cast?.split(",")?.map { it.trim() } ?: emptyList()
        val cast = castList.map {
            ActorData(
                Actor(it),
            )
        }
        val genre = data.genre?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }

        val rating = data.match?.replace("IMDb ", "")
        val runTime = convertRuntimeToMinutes(data.runtime.toString())

        val suggest = data.suggest?.map {
            newAnimeSearchResponse("", Id(it.id).toJson()) {
                this.posterUrl = "https://imgcdn.kim/pv/v/${it.id}.jpg"
                posterHeaders = mapOf("Referer" to "$mainUrl/home")
            }
        }

        if (data.episodes.first() == null) {
            episodes.add(newEpisode(LoadData(title, id)) {
                name = data.title
            })
        } else {
            data.episodes.filterNotNull().mapTo(episodes) {
                newEpisode(LoadData(title, it.id)) {
                    this.name = it.t
                    this.episode = it.ep.replace("E", "").toIntOrNull()
                    this.season = it.s.replace("S", "").toIntOrNull()
                    this.posterUrl = "https://imgcdn.kim/pvepimg/${it.id}.jpg"
                    this.runTime = it.time.replace("m", "").toIntOrNull()
                }
            }

            if (data.nextPageShow == 1) {
                episodes.addAll(getEpisodes(title, url, data.nextPageSeason!!, 2))
            }

            data.season?.dropLast(1)?.amap {
                episodes.addAll(getEpisodes(title, url, it.id, 1))
            }
        }

        val type = if (data.episodes.first() == null) TvType.Movie else TvType.TvSeries

        return newTvSeriesLoadResponse(title, url, type, episodes) {
            posterUrl = "https://imgcdn.kim/pv/v/$id.jpg"
            backgroundPosterUrl = "https://imgcdn.kim/pv/h/$id.jpg"
            posterHeaders = mapOf("Referer" to "$mainUrl/home")
            plot = data.desc
            year = data.year.toIntOrNull()
            tags = genre
            actors = cast
            this.score = Score.from10(rating)
            this.duration = runTime
            this.contentRating = data.ua
            this.recommendations = suggest
        }
    }

    private suspend fun getEpisodes(
        title: String, eid: String, sid: String, page: Int
    ): List<Episode> {
        val episodes = arrayListOf<Episode>()
        val cookies = mapOf(
            "t_hash_t" to cookie_value,
            "hd" to "on",
            "ott" to "pv"
        )
        var pg = page
        while (true) {
            val data = app.get(
                "$mainUrl/mobile/pv/episodes.php?s=$sid&series=$eid&t=${APIHolder.unixTime}&page=$pg",
                headers,
                referer = "$mainUrl/home",
                cookies = cookies
            ).parsed<EpisodesData>()
            data.episodes?.mapTo(episodes) { ep ->
                newEpisode(LoadData(title, ep.id)) {
                    name = ep.t
                    episode = ep.ep.replace("E", "").toIntOrNull()
                    season = ep.s.replace("S", "").toIntOrNull()
                    this.posterUrl = "https://imgcdn.kim/pvepimg/${ep.id}.jpg"
                    this.runTime = ep.time.replace("m", "").toIntOrNull()
                }
            }
            if (data.nextPageShow == 0) break
            pg++
        }
        return episodes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        cookie_value = ensureBypass(mainUrl)
        val ld = parseJson<LoadData>(data)
        return netMirrorMobileLoadLinks(
            mainUrl = mainUrl,
            id = ld.id,
            title = ld.title,
            playlistPath = "/mobile/pv/playlist.php",
            baseCookies = mapOf("hd" to "on", "ott" to "pv"),
            name = name,
            subtitleCallback = subtitleCallback,
            callback = callback
        )
    }

    data class PvSliderItem(
        val id: String,
        val title: String? = null,
        val desc: String? = null,
        val img: String? = null
    )

    data class PvCategoryItem(
        val cate: String,
        val ids: String
    )

    data class PvHomepageData(
        val slider: List<PvSliderItem>? = null,
        val post: List<PvCategoryItem>? = null
    )

    @Suppress("ObjectLiteralToLambda")
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        val sessionCookie = buildList {
            add("hd=on")
            add("ott=pv")
            if (cookie_value.isNotEmpty()) add("t_hash_t=$cookie_value")
        }.joinToString("; ")
        val videoHeaders = mapOf(
            "User-Agent" to NETMIRROR_MOBILE_UA,
            "Cookie" to sessionCookie
        )
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                val rawUrl = request.url.toString()
                val targetUrl = if (rawUrl.startsWith("https:///")) {
                    rawUrl.replace("https:///", "$mainUrl/")
                } else rawUrl

                val builder = request.newBuilder().url(targetUrl)
                for ((key, value) in videoHeaders) {
                    builder.header(key, value)
                }
                if (request.header("Referer").isNullOrBlank()) {
                    builder.header("Referer", extractorLink.referer)
                }
                return chain.proceed(builder.build())
            }
        }
    }

    data class Id(val id: String)
    data class LoadData(val title: String, val id: String)
}
