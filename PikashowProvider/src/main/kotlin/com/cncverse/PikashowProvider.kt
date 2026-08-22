package com.cncverse

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class PikashowProvider : MainAPI() {
    override var mainUrl = "https://manoda.co"
    override var name = "Pikashow"
    override val hasMainPage = true
    override var lang = "ta"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    companion object {
        var context: android.content.Context? = null
        private const val HDBV_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/117.0.0.0 Safari/537.36"
    }

    private val apiKey = "picashow-api-secret-key"
    private val hmacSecret = "picashow-api-secret-2025"
    private val mapper = jacksonObjectMapper()
    private val deviceUuid = UUID.randomUUID().toString()
    private val gaid = UUID.randomUUID().toString()
    private val userAgent = "Pikashow/2509030 (Android 13; Pixel 5; Channel/pikashow; gaid/$gaid); Uuid/$deviceUuid"

    // ── Data models ──────────────────────────────────────────────────────────

    data class PikashowSeries(
        @JsonProperty("t") val title: String? = null,
        @JsonProperty("g") val genre: String? = null,
        @JsonProperty("y") val year: Int? = null,
        @JsonProperty("c") val cover: String? = null,
        @JsonProperty("i") val imdbRating: String? = null,
        @JsonProperty("n") val seasons: Int? = null,
        @JsonProperty("detail") val details: List<SeasonDetail>? = null
    )

    data class SeasonDetail(
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("season") val season: String? = null,
        @JsonProperty("episodes_count") val episodesCount: Int? = null
    )

    data class PikashowSeriesResponse(
        @JsonProperty("series") val series: List<PikashowSeries>? = null
    )

    data class PikashowMovie(
        @JsonProperty("so") val sortOrder: Int? = null,
        @JsonProperty("t") val title: String? = null,
        @JsonProperty("g") val genre: String? = null,
        @JsonProperty("y") val year: Int? = null,
        @JsonProperty("q") val quality: String? = null,
        @JsonProperty("c") val cover: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("f") val format: Int? = null,
        @JsonProperty("clientUrls") val clientUrls: List<ClientUrl>? = null
    )

    data class ClientUrl(
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("url") val url: String? = null
    )

    data class PikashowMovieResponse(
        @JsonProperty("records") val records: List<PikashowMovie>? = null
    )

    data class VideoApiResponse(
        @JsonProperty("code") val code: Int? = null,
        @JsonProperty("message") val message: String? = null,
        @JsonProperty("data") val data: VideoData? = null
    )

    data class VideoData(
        @JsonProperty("t") val title: String? = null,
        @JsonProperty("g") val genre: String? = null,
        @JsonProperty("y") val year: Int? = null,
        @JsonProperty("c") val cover: String? = null,
        @JsonProperty("i") val imdbRating: String? = null,
        @JsonProperty("n") val seasons: Int? = null,
        @JsonProperty("detail") val details: List<VideoSeasonDetail>? = null,
        @JsonProperty("so") val sortOrder: Int? = null,
        @JsonProperty("q") val quality: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("f") val format: Int? = null,
        @JsonProperty("clientUrls") val clientUrls: List<ClientUrl>? = null,
        @JsonProperty("videoUrl") val videoUrl: String? = null,
        @JsonProperty("playUrl") val playUrl: String? = null,
        @JsonProperty("resolutions") val resolutions: List<Resolution>? = null,
        @JsonProperty("headers") val headers: Map<String, String>? = null,
        @JsonProperty("languages") val languages: List<Language>? = null,
        @JsonProperty("languageOptions") val languageOptions: List<Language>? = null,
        @JsonProperty("heastr") val heastr: String? = null,
        @JsonProperty("uastr") val uastr: String? = null,
        @JsonProperty("uaStr") val uaStr: String? = null,
        @JsonProperty("headerStr") val headerStr: String? = null,
        @JsonProperty("sourceType") val sourceType: String? = null,
        @JsonProperty("host") val host: String? = null,
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("key") val key: String? = null,
        @JsonProperty("supportedLanguages") val supportedLanguages: List<String>? = null,
        @JsonProperty("season") val season: String? = null,
        @JsonProperty("episode") val episode: String? = null
    )

    data class VideoSeasonDetail(
        @JsonProperty("season") val season: String? = null,
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("episodes") val episodes: List<VideoEpisode>? = null
    )

    data class VideoEpisode(
        @JsonProperty("e") val episode: String? = null,
        @JsonProperty("url") val url: String? = null
    )

    data class Resolution(
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("width") val width: Int? = null,
        @JsonProperty("height") val height: Int? = null
    )

    data class Language(
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("playUrl") val playUrl: String? = null,
        @JsonProperty("resolutions") val resolutions: List<Resolution>? = null
    )

    // ── Signature & headers ─────────────────────────────────────────────────

    private fun generateSignature(timestampMs: Long? = null): Map<String, String> {
        val timestamp = timestampMs ?: System.currentTimeMillis()
        val timestampStr = (timestamp / 1000).toString()
        val message = "$apiKey:$timestampStr"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hmacSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signatureHex = mac.doFinal(message.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return mapOf(
            "X-Timestamp" to timestampStr,
            "X-API-Key" to apiKey,
            "X-Signature" to signatureHex
        )
    }

    private fun getPikashowHeaders(): Map<String, String> {
        val sig = generateSignature()
        return mapOf(
            "Host" to "manoda.co",
            "user-agent" to userAgent,
            "X-API-Key" to sig["X-API-Key"]!!,
            "X-Signature" to sig["X-Signature"]!!,
            "X-Timestamp" to sig["X-Timestamp"]!!
        )
    }

    // ── Quality helpers ─────────────────────────────────────────────────────

    private fun getQualityFromString(qualityString: String?): SearchQuality? {
        return when (qualityString?.uppercase()) {
            "HD", "720P", "FHD", "1080P", "4K", "2160P" -> SearchQuality.HD
            "CAM", "CAMRIP" -> SearchQuality.Cam
            "HDCAM" -> SearchQuality.HdCam
            "TELECINE", "TC" -> SearchQuality.Telecine
            "TELESYNC", "TS" -> SearchQuality.Telesync
            "WORKPRINT", "WP" -> SearchQuality.WorkPrint
            else -> null
        }
    }

    private fun getQualityValue(qualityString: String?): Int {
        return when (qualityString?.uppercase()) {
            "4K", "2160P" -> Qualities.P2160.value
            "FHD", "1080P" -> Qualities.P1080.value
            "HD", "720P" -> Qualities.P720.value
            "SD", "480P" -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    }

    private fun getQualityValueFromLabel(label: String?): Int {
        return when (label?.lowercase()) {
            "1080p" -> Qualities.P1080.value
            "720p" -> Qualities.P720.value
            "480p" -> Qualities.P480.value
            "360p" -> Qualities.P360.value
            "default" -> Qualities.P720.value
            else -> Qualities.Unknown.value
        }
    }

    // ── getMainPage ─────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val headers = getPikashowHeaders()
        val homePageList = mutableListOf<HomePageList>()

        val categories = listOf(
            "series" to "TV Series",
            "hollywood" to "Hollywood Movies",
            "bollywood" to "Bollywood Movies"
        )

        for ((type, displayName) in categories) {
            try {
                val params = mapOf("type" to type, "channel" to "pikashow")
                val response = app.get("$mainUrl/v1/api/videos", params = params, headers = headers, timeout = 30)
                if (response.code != 200) continue

                val searchResults = when (type) {
                    "series" -> {
                        val seriesResponse = mapper.readValue<PikashowSeriesResponse>(response.text)
                        seriesResponse.series?.mapNotNull { series ->
                            series.title?.let { title ->
                                newTvSeriesSearchResponse(title, "pikashow:$title:$type", TvType.TvSeries) {
                                    posterUrl = series.cover
                                    year = series.year
                                    quality = SearchQuality.HD
                                }
                            }
                        }?.asReversed() ?: emptyList()
                    }
                    "hollywood", "bollywood" -> {
                        val movieResponse = mapper.readValue<PikashowMovieResponse>(response.text)
                        movieResponse.records?.mapNotNull { movie ->
                            movie.title?.let { title ->
                                newMovieSearchResponse(title, "pikashow:${movie.sortOrder}:$type", TvType.Movie) {
                                    posterUrl = movie.cover
                                    year = movie.year
                                    quality = getQualityFromString(movie.quality)
                                }
                            }
                        }?.asReversed() ?: emptyList()
                    }
                    else -> emptyList()
                }

                if (searchResults.isNotEmpty()) {
                    homePageList.add(HomePageList(displayName, searchResults))
                }
            } catch (_: Exception) { }
        }

        return newHomePageResponse(homePageList)
    }

    // ── search ──────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val searchResults = mutableListOf<SearchResponse>()
        val headers = getPikashowHeaders()
        val searchQuery = query.lowercase().trim()

        val categories = listOf(
            "series" to TvType.TvSeries,
            "hollywood" to TvType.Movie,
            "bollywood" to TvType.Movie
        )

        for ((type, tvType) in categories) {
            try {
                val params = mapOf("type" to type, "channel" to "pikashow")
                val response = app.get("$mainUrl/v1/api/videos", params = params, headers = headers, timeout = 30)
                if (response.code != 200) continue

                when (type) {
                    "series" -> {
                        val seriesResponse = mapper.readValue<PikashowSeriesResponse>(response.text)
                        seriesResponse.series?.forEach { series ->
                            series.title?.let { title ->
                                if (title.lowercase().contains(searchQuery) ||
                                    series.genre?.lowercase()?.contains(searchQuery) == true
                                ) {
                                    searchResults.add(
                                        newTvSeriesSearchResponse(title, "pikashow:$title:$type", tvType) {
                                            posterUrl = series.cover
                                            year = series.year
                                            quality = SearchQuality.HD
                                        }
                                    )
                                }
                            }
                        }
                    }
                    "hollywood", "bollywood" -> {
                        val movieResponse = mapper.readValue<PikashowMovieResponse>(response.text)
                        movieResponse.records?.forEach { movie ->
                            movie.title?.let { title ->
                                if (title.lowercase().contains(searchQuery) ||
                                    movie.genre?.lowercase()?.contains(searchQuery) == true
                                ) {
                                    searchResults.add(
                                        newMovieSearchResponse(title, "pikashow:${movie.sortOrder}:$type", tvType) {
                                            posterUrl = movie.cover
                                            year = movie.year
                                            quality = getQualityFromString(movie.quality)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) { }
        }

        return searchResults.sortedWith(
            compareBy<SearchResponse> { response ->
                val title = response.name.lowercase()
                when {
                    title == searchQuery -> 0
                    title.startsWith(searchQuery) -> 1
                    title.contains(searchQuery) -> 2
                    else -> 3
                }
            }.thenBy { it.name }
        ).take(50)
    }

    // ── load ────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        try {
            val withoutUrlScheme = url.removePrefix("$mainUrl/")
            val parts = withoutUrlScheme.split(":")
            if (parts.size != 3 || parts[0] != "pikashow") return null

            val identifier = parts[1]
            val type = parts[2]
            val headers = getPikashowHeaders()
            val baseParams = mapOf("type" to type, "channel" to "pikashow")
            val response = app.get("$mainUrl/v1/api/videos", params = baseParams, headers = headers, timeout = 30)
            if (response.code != 200) return null

            return when (type) {
                "series" -> {
                    val seriesResponse = mapper.readValue<PikashowSeriesResponse>(response.text)
                    val series = seriesResponse.series?.find { it.title == identifier } ?: return null
                    val episodes = mutableListOf<Episode>()
                    series.details?.forEach { detail ->
                        val seasonNumber = detail.season?.toIntOrNull() ?: 1
                        val episodeCount = detail.episodesCount ?: 1
                        for (episodeNum in 1..episodeCount) {
                            episodes.add(
                                newEpisode("pikashow_episode:${series.title}:$seasonNumber:$episodeNum") {
                                    name = "Episode $episodeNum"
                                    season = seasonNumber
                                    episode = episodeNum
                                }
                            )
                        }
                    }
                    newTvSeriesLoadResponse(
                        name = series.title ?: "Unknown Series",
                        url = url,
                        type = TvType.TvSeries,
                        episodes = episodes
                    ) {
                        posterUrl = series.cover
                        year = series.year
                        plot = series.genre
                        tags = series.genre?.split(",")?.map { it.trim() }
                    }
                }
                "hollywood", "bollywood" -> {
                    val movieResponse = mapper.readValue<PikashowMovieResponse>(response.text)
                    val movie = movieResponse.records?.find { it.sortOrder.toString() == identifier } ?: return null
                    newMovieLoadResponse(
                        name = movie.title ?: "Unknown Movie",
                        url = url,
                        type = TvType.Movie,
                        dataUrl = url
                    ) {
                        posterUrl = movie.cover
                        year = movie.year
                        plot = movie.genre
                        tags = movie.genre?.split(",")?.map { it.trim() }
                    }
                }
                else -> null
            }
        } catch (_: Exception) {
            return null
        }
    }

    // ── loadLinks ───────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val withoutUrlScheme = data.removePrefix("$mainUrl/")
            val headers = getPikashowHeaders()

            when {
                withoutUrlScheme.startsWith("pikashow_episode:") -> {
                    val parts = withoutUrlScheme.split(":")
                    if (parts.size < 4) return false
                    val (_, seriesTitle, season, episode) = parts
                    val params = mapOf(
                        "type" to "series",
                        "videoId" to "0",
                        "title" to seriesTitle,
                        "noseasons" to season,
                        "noepisodes" to episode
                    )
                    val response = app.get("$mainUrl/v1/api/video", params = params, headers = headers, timeout = 30)
                    if (response.code == 200) {
                        val videoData = mapper.readValue<VideoApiResponse>(response.text).data
                        if (videoData != null) {
                            addVideoLinksToCallback(videoData, callback, "Episode $episode", episode.toIntOrNull())
                            return true
                        }
                    }
                }

                withoutUrlScheme.startsWith("pikashow:") -> {
                    val parts = withoutUrlScheme.split(":")
                    if (parts.size < 3) return false
                    val identifier = parts[1]
                    val type = parts[2]

                    val listParams = mapOf("type" to type, "channel" to "pikashow")
                    val listResponse = app.get("$mainUrl/v1/api/videos", params = listParams, headers = headers, timeout = 30)
                    if (listResponse.code != 200) return false

                    var videoId: String? = null
                    var title: String? = null

                    when (type) {
                        "series" -> {
                            val seriesResponse = mapper.readValue<PikashowSeriesResponse>(listResponse.text)
                            val series = seriesResponse.series?.find { it.title == identifier }
                            if (series != null) {
                                videoId = "0"
                                title = series.title
                            }
                        }
                        "hollywood", "bollywood" -> {
                            val movieResponse = mapper.readValue<PikashowMovieResponse>(listResponse.text)
                            val movie = movieResponse.records?.find { it.sortOrder.toString() == identifier }
                            if (movie != null) {
                                videoId = movie.sortOrder.toString()
                                title = movie.title
                            }
                        }
                    }

                    if (videoId != null && title != null) {
                        val safeTitle = title
                        val videoParams = mapOf(
                            "type" to type,
                            "videoId" to videoId,
                            "title" to safeTitle,
                            "noseasons" to "1",
                            "noepisodes" to "0"
                        )
                        val videoResponse = app.get("$mainUrl/v1/api/video", params = videoParams, headers = headers, timeout = 30)
                        if (videoResponse.code != 404) {
                            val videoApiResponse = mapper.readValue<VideoApiResponse>(videoResponse.text)
                            videoApiResponse.data?.let { videoData ->
                                addVideoLinksToCallback(videoData, callback, safeTitle)
                                return true
                            }
                        }
                    }
                }
            }

            return false
        } catch (_: Exception) {
            return false
        }
    }

    // ── addVideoLinksToCallback ─────────────────────────────────────────────

    private fun originOf(url: String): String {
        return try {
            val uri = java.net.URI(url)
            "${uri.scheme ?: "https"}://${uri.host}"
        } catch (_: Exception) {
            ""
        }
    }

    private suspend fun addVideoLinksToCallback(
        videoData: VideoData,
        callback: (ExtractorLink) -> Unit,
        contentName: String,
        episodeNumber: Int? = null
    ) {
        val baseHeaders = mutableMapOf<String, String>()
        videoData.heastr?.let { baseHeaders["heastr"] = it }
        videoData.uastr?.let { baseHeaders["user-agent"] = it }
        videoData.uaStr?.let { baseHeaders["user-agent"] = it }

        videoData.headerStr?.let { headerStr ->
            try {
                baseHeaders.putAll(mapper.readValue<Map<String, String>>(headerStr))
            } catch (_: Exception) { }
        }

        val finalHeaders = if (videoData.headers != null) {
            val merged = baseHeaders.toMutableMap()
            merged.putAll(videoData.headers)
            videoData.heastr?.let { merged["heastr"] = it }
            videoData.uastr?.let { merged["user-agent"] = it }
            videoData.uaStr?.let { merged["user-agent"] = it }
            merged
        } else {
            baseHeaders
        }

        // Collect candidate player/embed URLs (main + extra servers)
        val candidates = mutableListOf<Pair<String?, String>>() // label to url
        videoData.url?.let { candidates.add(null to it) }
        videoData.clientUrls?.forEach { client ->
            client.url?.let { u -> candidates.add(client.label to u) }
        }

        var emitted = 0

        val hasResolutions = !videoData.resolutions.isNullOrEmpty()
        val hasLanguageResolutions =
            videoData.languageOptions?.any { !it.resolutions.isNullOrEmpty() } == true ||
            videoData.languages?.any { !it.resolutions.isNullOrEmpty() } == true

        if (hasResolutions || hasLanguageResolutions) {
            videoData.resolutions?.forEach { resolution ->
                resolution.url?.let { url ->
                    val linkType = if (url.contains("m3u8") || videoData.sourceType == "hls") {
                        ExtractorLinkType.M3U8
                    } else {
                        ExtractorLinkType.VIDEO
                    }
                    callback(
                        newExtractorLink(name, "${resolution.label ?: "Unknown"} - $contentName", url, linkType) {
                            referer = originOf(url).ifEmpty { "$name" }
                            quality = getQualityValueFromLabel(resolution.label)
                            headers = finalHeaders
                        }
                    )
                    emitted++
                }
            }

            (videoData.languageOptions ?: videoData.languages)?.forEach { lang ->
                lang.resolutions?.forEach { resolution ->
                    resolution.url?.let { url ->
                        val linkType = if (url.contains("m3u8") || videoData.sourceType == "hls") {
                            ExtractorLinkType.M3U8
                        } else {
                            ExtractorLinkType.VIDEO
                        }
                        val langName = lang.language.takeIf { !it.isNullOrBlank() } ?: "Default"
                        callback(
                            newExtractorLink(name, "${resolution.label ?: "Unknown"} ($langName) - $contentName", url, linkType) {
                                referer = originOf(url).ifEmpty { "$name" }
                                quality = getQualityValueFromLabel(resolution.label)
                                headers = finalHeaders
                            }
                        )
                        emitted++
                    }
                }
            }
        }

        if (emitted == 0) {
            for ((serverLabel, playerUrl) in candidates) {
                val serverSuffix = serverLabel?.takeIf { it.isNotBlank() && it != "Server1" }
                    ?.let { " [$it]" } ?: ""

                // 1. HDVB player pages (/play/...) → resolve via playlist API
                if (playerUrl.contains("/play")) {
                    try {
                        val links = resolveHdbvLinks(playerUrl, episodeNumber)
                        for ((label, streamUrl) in links) {
                            callback(
                                newExtractorLink(name, "$contentName$serverSuffix - ${label.ifEmpty { "HDBV" }}", streamUrl, ExtractorLinkType.M3U8) {
                                    referer = originOf(playerUrl)
                                    quality = Qualities.P720.value
                                    headers = finalHeaders
                                }
                            )
                            emitted++
                        }
                    } catch (_: Exception) { }
                }

                // 2. Embed pages (streamtape /e/, dood, etc.) → route through extractors
                if (isEmbedUrl(playerUrl)) {
                    val resolved = try {
                        loadExtractor(playerUrl, originOf(playerUrl).ifEmpty { playerUrl }, { }, callback)
                    } catch (_: Exception) {
                        false
                    }
                    if (resolved) emitted++
                }

                // If this server produced anything, don't fall through to next server blindly
                if (emitted > 0) break
            }
        }

        if (emitted == 0) {
            fallbackToDirectUrls(videoData, callback, contentName, finalHeaders)
        }
    }

    private fun isEmbedUrl(url: String): Boolean {
        val embedMarkers = listOf("/e/", "/embed-", "/embed/", "streamtape", "dood", "dsvplay", "mp4upload", "vidmoly", "ok.ru", "sibnet")
        return embedMarkers.any { url.contains(it, ignoreCase = true) }
    }

    // ── fallbackToDirectUrls ────────────────────────────────────────────────

    private suspend fun fallbackToDirectUrls(
        videoData: VideoData,
        callback: (ExtractorLink) -> Unit,
        contentName: String,
        finalHeaders: Map<String, String>
    ) {
        val directUrl = videoData.playUrl ?: videoData.videoUrl ?: videoData.url
        directUrl?.let { url ->
            val quality = when {
                videoData.quality?.lowercase()?.contains("1080") == true -> Qualities.P1080.value
                videoData.quality?.lowercase()?.contains("hd") == true -> Qualities.P720.value
                videoData.quality?.lowercase()?.contains("720") == true -> Qualities.P720.value
                videoData.quality?.lowercase()?.contains("480") == true -> Qualities.P480.value
                else -> Qualities.Unknown.value
            }
            val linkType = when {
                url.contains("m3u8") || videoData.sourceType == "hls" -> ExtractorLinkType.M3U8
                videoData.sourceType == "direct" -> ExtractorLinkType.VIDEO
                else -> ExtractorLinkType.VIDEO
            }
            callback(
                newExtractorLink(name, "$contentName - ${videoData.host ?: "Direct"}", url, linkType) {
                    referer = originOf(url).ifEmpty { "$name" }
                    this.quality = quality
                    headers = finalHeaders
                }
            )
        }
    }

    // ── HDVB player resolution ──────────────────────────────────────────────

    /**
     * Fetches an HDVB player page (/play/...) and resolves every stream it exposes.
     * Handles both inline configs (`new HDVBPlayer({...})`) and variable configs
     * (`var p = {...}; new HDVBPlayer(p)`), scanning every script tag.
     * Player responses nest either as season→episode→audiotrack or flat audiotracks.
     */
    private suspend fun resolveHdbvLinks(playerUrl: String, episodeNumber: Int? = null): List<Pair<String, String>> {
        val origin = originOf(playerUrl)
        if (origin.isEmpty()) return emptyList()

        val pageHeaders = mapOf(
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9",
            "Origin" to origin,
            "Referer" to "$origin/",
            "User-Agent" to HDBV_UA,
            "X-Requested-With" to "com.offshore.pikachu"
        )

        // Some player URLs 404 with their query suffix; retry without it.
        var pageResponse = app.get(url = playerUrl, headers = pageHeaders, timeout = 30)
        if (pageResponse.code != 200 && playerUrl.contains("?")) {
            pageResponse = app.get(
                url = playerUrl.substringBefore("?"),
                headers = pageHeaders,
                timeout = 30
            )
        }
        if (pageResponse.code != 200) return emptyList()
        val pageHtml = pageResponse.text

        // 1. Inline style: HDVBPlayer({...});
        val configs = mutableListOf<String>()
        Regex("""HDVBPlayer\s*\(\s*(\{.*?\})\s*\)\s*;""", RegexOption.DOT_MATCHES_ALL)
            .findAll(pageHtml).forEach { configs.add(it.groupValues[1]) }

        // 2. Variable style: var|let|const NAME = {...}; ... HDVBPlayer(NAME);
        val declaredVars = mutableMapOf<String, String>()
        Regex("""(?:var|let|const)\s+(\w+)\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
            .findAll(pageHtml).forEach { declaredVars[it.groupValues[1]] = it.groupValues[2] }
        Regex("""HDVBPlayer\s*\(\s*(\w+)\s*\)""").findAll(pageHtml).forEach { m ->
            declaredVars[m.groupValues[1]]?.let { configs.add(it) }
        }

        val links = mutableListOf<Pair<String, String>>()
        val seenUrls = mutableSetOf<String>()

        for (configJson in configs) {
            val config = try {
                JSONObject(configJson)
            } catch (_: Exception) {
                continue
            }
            val playlistPath = config.optString("file", "")
            if (playlistPath.isEmpty()) continue
            val csrfKey = config.optString("key", "")

            for ((label, streamUrl) in resolveHdbvPlaylist(origin, playlistPath, csrfKey, playerUrl, episodeNumber)) {
                if (seenUrls.add(streamUrl)) links.add(label to streamUrl)
            }
            if (links.isNotEmpty()) break
        }
        return links
    }

    private suspend fun hdbvPost(url: String, referer: String, csrfKey: String): String? {
        val postHeaders = mutableMapOf(
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9",
            "Content-Type" to "application/x-www-form-urlencoded",
            "Origin" to originOf(url),
            "User-Agent" to HDBV_UA
        )
        if (csrfKey.isNotEmpty()) postHeaders["X-Csrf-Token"] = csrfKey
        return try {
            val response = app.post(url = url, headers = postHeaders, referer = referer)
            if (response.code == 200) response.text else null
        } catch (_: Exception) {
            null
        }
    }

    /** Resolves one level: playlist endpoint → entries, then each entry token → final stream URL. */
    private suspend fun resolveHdbvPlaylist(
        origin: String,
        playlistPath: String,
        csrfKey: String,
        referer: String,
        episodeNumber: Int? = null
    ): List<Pair<String, String>> {
        val playlistUrl = if (playlistPath.startsWith("http")) {
            playlistPath
        } else {
            "$origin/${playlistPath.trimStart('/')}"
        }
        val body = hdbvPost(playlistUrl, referer, csrfKey) ?: return emptyList()

        val root = try {
            JSONArray(body)
        } catch (_: Exception) {
            return emptyList()
        }

        val results = mutableListOf<Pair<String, String>>()
        val seenTokens = mutableSetOf<String>()
        walkHdbvEntries(root, "", csrfKey, referer, origin, results, seenTokens, episodeNumber)
        return results
    }

    private suspend fun walkHdbvEntries(
        node: Any?,
        prefix: String,
        csrfKey: String,
        referer: String,
        origin: String,
        out: MutableList<Pair<String, String>>,
        seenTokens: MutableSet<String>,
        episodeNumber: Int? = null
    ) {
        when (node) {
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    walkHdbvEntries(node.opt(i), prefix, csrfKey, referer, origin, out, seenTokens, episodeNumber)
                }
            }
            is JSONObject -> {
                val rawFile = node.optString("file", "")
                if (rawFile.isNotEmpty()) {
                    val labelParts = mutableListOf<String>()
                    if (prefix.isNotEmpty()) labelParts.add(prefix.trim())
                    node.optString("title", "").takeIf { it.isNotEmpty() }?.let { labelParts.add(it) }
                    val label = labelParts.joinToString(" ")

                    // Skip non-matching episodes before making the resolution POST
                    val matchesEpisode = episodeNumber == null ||
                        Regex("""(?:^|\s)E0*${episodeNumber}(?:\s|$)""").containsMatchIn(label)

                    if (matchesEpisode) {
                        val token = rawFile.replace("~", "").trimStart('/')
                        if (token.isNotEmpty() && seenTokens.add(token)) {
                            val finalUrl = hdbvPost("$origin/playlist/$token.txt", referer, csrfKey)?.trim()?.removeSurrounding("\"")
                            if (!finalUrl.isNullOrEmpty() && finalUrl.startsWith("http")) {
                                out.add(label to finalUrl)
                            }
                        }
                    }
                }

                node.optJSONArray("folder")?.let { folder ->
                    val childPrefix = when {
                        node.optString("episode", "").isNotEmpty() ->
                            "$prefix E${node.optString("episode")}".trim()
                        node.optString("title", "").startsWith("Season", true) ->
                            "$prefix ${node.optString("title").replace("Season", "S", true)}".trim()
                        else -> prefix
                    }
                    walkHdbvEntries(folder, childPrefix, csrfKey, referer, origin, out, seenTokens, episodeNumber)
                }
            }
        }
    }
}
