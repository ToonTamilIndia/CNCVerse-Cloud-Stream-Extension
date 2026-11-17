package com.cncverse

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.Locale

class MoviePlusProvider : MainAPI() {
    override var name = "Movie! Plus"
    override var mainUrl = "https://api.appsxploit.dev"
    override var lang = "es"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "latest_movies" to "Películas",
        "type_2" to "Series",
        "type_3" to "Animes",
        "type_4" to "Cartoons",
        "type_6" to "Doramas",
        "type_5" to "Telenovelas",
        "genre_1" to "Acción",
        "genre_11" to "Terror",
    )

    private fun movieUrl(id: Int) = "$mainUrl/movie/$id"

    private fun seriesUrl(id: Int) = "$mainUrl/serie/$id"

    private fun MoviePlusCatalog.CatalogItem.toSearch(): SearchResponse {
        val poster = MoviePlusTmdb.poster(image)
        return if (type == MoviePlusCatalog.TYPE_MOVIE) {
            newMovieSearchResponse(name, movieUrl(id), TvType.Movie) { this.posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(name, seriesUrl(id), TvType.TvSeries) { this.posterUrl = poster }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (movies, series) = MoviePlusCatalog.loadCatalog()
        val data = request.data
        val items = when {
            data == "latest_movies" -> movies.sortedByDescending { it.date }
            data == "latest_series" -> series.sortedByDescending { it.date }
            data.startsWith("type_") -> {
                val t = data.removePrefix("type_").toIntOrNull()
                series.filter { t != null && it.type == t }.sortedByDescending { it.date }
            }
            data.startsWith("genre_") -> {
                val gid = data.removePrefix("genre_").toIntOrNull()
                if (gid != null) (movies + series).filter { gid in it.genres }.sortedByDescending { it.date } else emptyList()
            }
            else -> emptyList()
        }
        val pageSize = 40
        val start = (page - 1) * pageSize
        val slice = items.drop(start).take(pageSize)
        val avail = MoviePlusWorker.filterAvailable(slice.map { it.id to (it.type == MoviePlusCatalog.TYPE_MOVIE) })
        val shown = slice.filter { it.id in avail }
        return newHomePageResponse(
            HomePageList(request.name, shown.map { it.toSearch() }, false),
            start + pageSize < items.size,
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val (movies, series) = MoviePlusCatalog.loadCatalog()
        val q = MoviePlusCatalog.normalize(query.trim())
        if (q.isEmpty()) return emptyList()
        val matches = (movies + series).asSequence()
            .filter { MoviePlusCatalog.normalize(it.name).contains(q) || MoviePlusCatalog.normalize(it.otherNames).contains(q) }
            .sortedByDescending { it.date }
            .take(60)
            .toList()
        val avail = MoviePlusWorker.filterAvailable(matches.map { it.id to (it.type == MoviePlusCatalog.TYPE_MOVIE) })
        return matches.filter { it.id in avail }.map { it.toSearch() }
    }

    private fun genreTags(tmdbGenres: List<MoviePlusTmdb.TmdbGenre>?, catalogItem: MoviePlusCatalog.CatalogItem?): List<String>? =
        tmdbGenres?.mapNotNull { it.name }
            ?: catalogItem?.genres?.mapNotNull { MoviePlusCatalog.GENRE_NAMES[it] }

    private fun castActors(credits: MoviePlusTmdb.TmdbCredits?): List<ActorData>? =
        credits?.cast?.take(12)?.map {
            ActorData(Actor(it.name ?: "", MoviePlusTmdb.poster(it.profilePath, "w185")), roleString = it.character)
        }

    override suspend fun load(url: String): LoadResponse? {
        val id = url.substringAfterLast('/').substringBefore('?').toIntOrNull() ?: return null
        val isMovie = url.contains("/movie/")
        val catalogItem = if (isMovie) MoviePlusCatalog.findMovie(id) else MoviePlusCatalog.findSeries(id)

        if (isMovie) {
            val tmdb = MoviePlusTmdb.movie(id)
            val title = catalogItem?.name ?: tmdb?.title ?: "Movie! Plus $id"
            return newMovieLoadResponse(title, movieUrl(id), TvType.Movie, "mp|$id") {
                this.posterUrl = MoviePlusTmdb.poster(catalogItem?.image ?: tmdb?.posterPath)
                this.backgroundPosterUrl = MoviePlusTmdb.backdrop(tmdb?.backdropPath)
                this.plot = listOfNotNull(tmdb?.tagline?.takeIf { it.isNotBlank() }, tmdb?.overview)
                    .joinToString("\n\n")
                    .takeIf { it.isNotBlank() }
                this.year = tmdb?.releaseDate?.take(4)?.toIntOrNull() ?: catalogItem?.date?.take(4)?.toIntOrNull()
                this.duration = tmdb?.runtime
                tmdb?.voteAverage?.takeIf { it > 0.0 }?.let { this.score = Score.from10(it) }
                this.tags = genreTags(tmdb?.genres, catalogItem)
                this.actors = castActors(tmdb?.credits)
            }
        }

        val tmdb = MoviePlusTmdb.tv(id)
        val title = catalogItem?.name ?: tmdb?.name ?: "Movie! Plus $id"
        val episodes = ArrayList<Episode>()
        for (season in (tmdb?.seasons ?: emptyList()).filter { it.seasonNumber > 0 }.sortedBy { it.seasonNumber }) {
            for (ep in MoviePlusTmdb.season(id, season.seasonNumber)) {
                episodes.add(newEpisode("mp|$id|${ep.seasonNumber}|${ep.episodeNumber}") {
                    this.name = ep.name
                    this.season = ep.seasonNumber
                    this.episode = ep.episodeNumber
                    this.posterUrl = MoviePlusTmdb.poster(ep.stillPath, "w300")
                    this.description = ep.overview
                    addDate(ep.airDate)
                })
            }
        }
        if (episodes.isEmpty()) {
            MoviePlusWorker.episodeEnvelopes(id)
                .distinctBy { it.season to it.episode }
                .sortedWith(compareBy({ it.season }, { it.episode }))
                .forEach { e ->
                    episodes.add(newEpisode("mp|$id|${e.season}|${e.episode}") {
                        this.name = "Episodio ${e.episode}"
                        this.season = e.season
                        this.episode = e.episode
                    })
                }
        }
        return newTvSeriesLoadResponse(title, seriesUrl(id), TvType.TvSeries, episodes) {
            this.posterUrl = MoviePlusTmdb.poster(catalogItem?.image ?: tmdb?.posterPath)
            this.backgroundPosterUrl = MoviePlusTmdb.backdrop(tmdb?.backdropPath)
            this.plot = tmdb?.overview
            this.year = tmdb?.firstAirDate?.take(4)?.toIntOrNull() ?: catalogItem?.date?.take(4)?.toIntOrNull()
            this.duration = tmdb?.episodeRunTime?.firstOrNull()
            tmdb?.voteAverage?.takeIf { it > 0.0 }?.let { this.score = Score.from10(it) }
            this.tags = genreTags(tmdb?.genres, catalogItem)
            this.actors = castActors(tmdb?.credits)
            this.showStatus = when (tmdb?.status) {
                "Returning Series", "In Production" -> ShowStatus.Ongoing
                "Ended", "Canceled" -> ShowStatus.Completed
                else -> null
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.substringAfterLast('/').split("|")
        if (parts.size < 2 || parts[0] != "mp") return false
        val id = parts[1].toIntOrNull() ?: return false

        if (parts.size >= 4) {
            val season = parts[2].toIntOrNull() ?: return false
            val episode = parts[3].toIntOrNull() ?: return false
            val links = MoviePlusWorker.episodeEnvelopes(id).filter { it.season == season && it.episode == episode }
            val seen = HashSet<String>()
            var found = false
            for (e in links) {
                val url = MoviePlusWorker.resolveEnvelope(e.envelope) ?: continue
                if (!seen.add(url)) continue
                callback(
                    newExtractorLink(name, "$name ${e.quality}".trim(), url, ExtractorLinkType.VIDEO) {
                        this.quality = qualityFromLabel(e.quality)
                    }
                )
                found = true
            }
            return found
        }

        val fallback = MoviePlusCatalog.findMovie(id)?.urls ?: emptyList()
        val resolved = MoviePlusWorker.resolveMovie(id, fallback)
        for (entry in resolved) {
            val label = buildString {
                append(entry.quality.ifBlank { "Movie! Plus" })
                if (entry.size.isNotBlank()) append(" · ").append(entry.size)
            }
            callback(
                newExtractorLink(name, "$name $label", entry.url, ExtractorLinkType.VIDEO) {
                    this.quality = qualityFromLabel(entry.quality)
                }
            )
        }
        return resolved.isNotEmpty()
    }

    fun qualityFromLabel(label: String?): Int {
        val l = (label ?: "").lowercase(Locale.ROOT)
        return when {
            "2160" in l || "4k" in l -> Qualities.P2160.value
            "1080" in l -> Qualities.P1080.value
            "720" in l -> Qualities.P720.value
            "480" in l -> Qualities.P480.value
            "360" in l -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}
