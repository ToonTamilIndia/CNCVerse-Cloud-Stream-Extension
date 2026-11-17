package com.cncverse.cinevood

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.nicehttp.NiceResponse
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

internal val cfKiller by lazy { CloudflareKiller() }

internal val browserHeaders = mapOf(
    "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
    "Accept-Language" to "en-US,en;q=0.9",
    "Sec-Fetch-Dest" to "document",
    "Sec-Fetch-Mode" to "navigate",
    "Sec-Fetch-Site" to "cross-site",
    "Sec-Fetch-User" to "?1",
    "Upgrade-Insecure-Requests" to "1",
    "sec-ch-ua" to "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\", \"Google Chrome\";v=\"137\"",
    "sec-ch-ua-mobile" to "?1",
    "sec-ch-ua-platform" to "\"Android\"",
)

private fun NiceResponse.isChallenge() = code == 403 || text.contains("<title>Just a moment")

// Plain request first, then CloudflareKiller, then the user-solved WebView clearance.
internal suspend fun getProtected(url: String, referer: String? = null): NiceResponse {
    val headers = referer?.let { browserHeaders + ("Referer" to it) } ?: browserHeaders
    fun withClearance(cf: String) = headers + ("Cookie" to "cf_clearance=$cf")

    clearanceFor(url)?.let { cf ->
        val res = app.get(url, headers = withClearance(cf))
        if (!res.isChallenge()) return res
        dropClearance(url)
    }
    val res = app.get(url, headers = headers)
    if (!res.isChallenge()) return res
    val solved = runCatching { app.get(url, headers = headers, interceptor = cfKiller) }.getOrNull()
    if (solved != null && !solved.isChallenge()) return solved
    val cf = solveCloudflareInWebView(url) ?: return solved ?: res
    return app.get(url, headers = withClearance(cf))
}

private var domains: JSONObject? = null

internal suspend fun latestDomain(key: String, fallback: String): String {
    if (domains == null) {
        domains = runCatching {
            JSONObject(app.get("https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json", timeout = 15L).text)
        }.getOrDefault(JSONObject())
    }
    return domains?.optString(key)?.trimEnd('/')?.ifEmpty { null } ?: fallback
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

internal fun tmdbImg(path: String?, size: String): String? =
    path?.takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/$size$it" }

private const val TMDB_API = "https://api.themoviedb.org/3"
private const val TMDB_KEY = "e1a8efff4415028c5c266b3fcd50db6e"

private suspend fun tmdbJson(path: String, params: String = ""): JSONObject? = runCatching {
    JSONObject(app.get("$TMDB_API$path?api_key=$TMDB_KEY$params", timeout = 15L).text)
}.getOrNull()

data class Btn(
    @JsonProperty("label") val label: String,
    @JsonProperty("url") val url: String,
)

class TitleMeta(private val tmdb: JSONObject?, private val cine: JSONObject?, private val series: Boolean) {
    val tmdbId: Int? = tmdb?.optInt("id")?.takeIf { it > 0 }
    private val seasons = mutableMapOf<Int, Map<Int, JSONObject>>()
    private val cineEpisodes: Map<Pair<Int, Int>, JSONObject> = cine?.optJSONArray("videos")?.objects()
        ?.associateBy { it.optInt("season") to it.optInt("episode") } ?: emptyMap()

    suspend fun applyTo(
        api: MainAPI,
        res: LoadResponse,
        imdbId: String?,
        poster: String?,
        plot: String?,
        tags: List<String>?,
        year: Int?,
        trailer: String?,
    ) {
        res.posterUrl = tmdbImg(tmdb?.optString("poster_path"), "w500") ?: poster
        res.backgroundPosterUrl = tmdbImg(tmdb?.optString("backdrop_path"), "original")
            ?: cine?.optString("background")?.ifEmpty { null }
        res.logoUrl = tmdb?.optJSONObject("images")?.optJSONArray("logos")?.objects()
            ?.sortedByDescending { it.optString("iso_639_1") == "en" }
            ?.firstOrNull()?.let { tmdbImg(it.optString("file_path"), "w500") }
            ?: cine?.optString("logo")?.ifEmpty { null }
        res.plot = tmdb?.optString("overview")?.ifEmpty { null } ?: plot
            ?: cine?.optString("description")?.ifEmpty { null }
        res.year = year ?: tmdb?.optString(if (series) "first_air_date" else "release_date")?.take(4)?.toIntOrNull()
        res.tags = tmdb?.optJSONArray("genres")?.objects()?.map { it.optString("name") }?.ifEmpty { null }
            ?: cine?.optJSONArray("genre")?.let { a -> (0 until a.length()).map { a.getString(it) } }?.ifEmpty { null }
            ?: tags
        res.actors = tmdb?.optJSONObject("credits")?.optJSONArray("cast")?.objects()?.take(25)?.map {
            ActorData(
                Actor(it.optString("name"), tmdbImg(it.optString("profile_path"), "w185")),
                roleString = it.optString("character").ifEmpty { null },
            )
        }?.ifEmpty { null }
            ?: cine?.optJSONArray("cast")?.let { a -> (0 until a.length()).map { ActorData(Actor(a.getString(it))) } }
        res.duration = tmdb?.optInt("runtime")?.takeIf { it > 0 }
            ?: tmdb?.optJSONArray("episode_run_time")?.optInt(0)?.takeIf { it > 0 }
            ?: cine?.optString("runtime")?.let { Regex("""(\d+)\s*min""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        res.contentRating = ageRating()
        (cine?.optString("imdbRating")?.toDoubleOrNull()
            ?: tmdb?.takeIf { it.optInt("vote_count") > 0 }?.optDouble("vote_average")?.takeIf { it > 0.0 })
            ?.let { res.score = Score.from10(it) }
        res.recommendations = recommendations(api)
        if (res is TvSeriesLoadResponse) {
            res.showStatus = when (tmdb?.optString("status")) {
                "Returning Series", "In Production" -> ShowStatus.Ongoing
                "Ended", "Canceled" -> ShowStatus.Completed
                else -> null
            }
        }
        res.addImdbId(imdbId)
        res.addTMDbId(tmdbId?.toString())
        res.addTrailer(
            tmdb?.optJSONObject("videos")?.optJSONArray("results")?.objects()
                ?.filter { it.optString("site") == "YouTube" }
                ?.sortedByDescending { it.optString("type") == "Trailer" }
                ?.firstOrNull()?.optString("key")
                ?.let { "https://www.youtube.com/watch?v=$it" }
                ?: trailer
        )
    }

    suspend fun decorate(ep: Episode, season: Int, number: Int) {
        val t = tmdbId?.let { id ->
            seasons.getOrPut(season) {
                tmdbJson("/tv/$id/season/$season")?.optJSONArray("episodes")?.objects()
                    ?.associateBy { it.optInt("episode_number") } ?: emptyMap()
            }[number]
        }
        val c = cineEpisodes[season to number]
        ep.name = t?.optString("name")?.ifEmpty { null } ?: c?.optString("name")?.ifEmpty { null } ?: ep.name
        ep.posterUrl = tmdbImg(t?.optString("still_path"), "w500") ?: c?.optString("thumbnail")?.ifEmpty { null }
        ep.description = t?.optString("overview")?.ifEmpty { null } ?: c?.optString("overview")?.ifEmpty { null }
        ep.runTime = t?.optInt("runtime")?.takeIf { it > 0 }
        t?.takeIf { it.optInt("vote_count") > 0 }?.optDouble("vote_average")?.takeIf { it > 0.0 }
            ?.let { ep.score = Score.from10(it) }
        ep.addDate((t?.optString("air_date") ?: c?.optString("released"))?.take(10)?.ifEmpty { null })
    }

    private fun ageRating(): String? {
        val results = tmdb?.optJSONObject(if (series) "content_ratings" else "release_dates")
            ?.optJSONArray("results")?.objects() ?: return null
        for (country in listOf("IN", "US")) {
            val r = results.firstOrNull { it.optString("iso_3166_1") == country }
            val rating = when {
                r == null -> null
                series -> r.optString("rating").ifEmpty { null }
                else -> r.optJSONArray("release_dates")?.objects()
                    ?.map { it.optString("certification") }?.firstOrNull { it.isNotEmpty() }
            }
            if (rating != null) return rating
        }
        return null
    }

    // Recommendations point back at a search URL; load() resolves them through recommendationQuery().
    private fun recommendations(api: MainAPI): List<SearchResponse>? =
        tmdb?.optJSONObject("recommendations")?.optJSONArray("results")?.objects()?.take(20)?.mapNotNull {
            val name = it.optString("title").ifEmpty { it.optString("name") }
            if (name.isEmpty()) return@mapNotNull null
            val recYear = it.optString("release_date").ifEmpty { it.optString("first_air_date") }.take(4)
            val recUrl = "${api.mainUrl}/?s=${URLEncoder.encode(name, "UTF-8")}" +
                if (recYear.isNotEmpty()) "#y$recYear" else ""
            if (it.optString("media_type") == "tv") {
                api.newTvSeriesSearchResponse(name, recUrl, TvType.TvSeries) {
                    this.posterUrl = tmdbImg(it.optString("poster_path"), "w342")
                }
            } else {
                api.newMovieSearchResponse(name, recUrl, TvType.Movie) {
                    this.posterUrl = tmdbImg(it.optString("poster_path"), "w342")
                    this.year = recYear.toIntOrNull()
                }
            }
        }

    companion object {
        suspend fun fetch(series: Boolean, imdbId: String?, name: String, year: Int?): TitleMeta {
            val kind = if (series) "tv" else "movie"
            val id = imdbId?.let {
                tmdbJson("/find/$it", "&external_source=imdb_id")
                    ?.optJSONArray(if (series) "tv_results" else "movie_results")
                    ?.optJSONObject(0)?.optInt("id")?.takeIf { id -> id > 0 }
            } ?: name.takeIf { it.isNotEmpty() }?.let {
                val yearParam = year?.let { y -> if (series) "&first_air_date_year=$y" else "&year=$y" } ?: ""
                tmdbJson("/search/$kind", "&query=${URLEncoder.encode(it, "UTF-8")}$yearParam")
                    ?.optJSONArray("results")?.optJSONObject(0)?.optInt("id")?.takeIf { id -> id > 0 }
            }
            val ratings = if (series) "content_ratings" else "release_dates"
            val tmdb = id?.let {
                tmdbJson(
                    "/$kind/$it",
                    "&append_to_response=credits,recommendations,videos,images,$ratings&include_image_language=en,hi,null"
                )
            }
            val cine = imdbId?.let {
                runCatching {
                    JSONObject(
                        app.get(
                            "https://v3-cinemeta.strem.io/meta/${if (series) "series" else "movie"}/$it.json",
                            timeout = 15L
                        ).text
                    ).optJSONObject("meta")
                }.getOrNull()
            }
            return TitleMeta(tmdb, cine, series)
        }

        fun recommendationQuery(url: String): Pair<String, String>? =
            Regex("""[?&]s=([^&#]+)(?:#y(\d{4}))?""").find(url)?.let {
                URLDecoder.decode(it.groupValues[1], "UTF-8") to it.groupValues[2]
            }
    }
}
