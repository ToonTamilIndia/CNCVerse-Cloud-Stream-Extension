package com.cncverse

import android.annotation.SuppressLint
import android.net.Uri
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.base64DecodeArray
import com.lagradost.cloudstream3.base64Encode
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max
import java.security.SecureRandom
import kotlin.random.Random
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
class MovieBoxProviderIN : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }

    override var mainUrl = "https://api3.aoneroom.com"
    override var name = "MovieBoxIN"
    override val hasMainPage = true
    override var lang = "ta"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Keys are injected at build time from this module's build.gradle.kts.
    private val secretKeyDefault = base64Decode(BuildConfig.MOVIEBOXIN_SECRET_KEY_DEFAULT)
    private val secretKeyAlt = base64Decode(BuildConfig.MOVIEBOXIN_SECRET_KEY_ALT)

        private fun md5(input: ByteArray): String {
        return MessageDigest.getInstance("MD5").digest(input)
            .joinToString("") { "%02x".format(it) }
    }

    private fun reverseString(input: String): String = input.reversed()

    private fun generateXClientToken(hardcodedTimestamp: Long? = null): String {
        val timestamp = (hardcodedTimestamp ?: System.currentTimeMillis()).toString()
        val reversed = reverseString(timestamp)
        val hash = md5(reversed.toByteArray())
        return "$timestamp,$hash"
    }

    private val random = SecureRandom()

    fun generateDeviceId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
    
    val deviceId = generateDeviceId()

    data class BrandModel(val brand: String, val model: String)

    private val brandModels = mapOf(
        "Samsung" to listOf("SM-S918B", "SM-A528B", "SM-M336B"),
        "Xiaomi" to listOf("2201117TI", "M2012K11AI", "Redmi Note 11"),
        "OnePlus" to listOf("LE2111", "CPH2449", "IN2023"),
        "Google" to listOf("Pixel 6", "Pixel 7", "Pixel 8"),
        "Realme" to listOf("RMX3085", "RMX3360", "RMX3551")
    )

    fun randomBrandModel(): BrandModel {
        val brand = brandModels.keys.random()
        val model = brandModels[brand]!!.random()
        return BrandModel(brand, model)
    }

    @Volatile
    private var cachedGuestToken: String? = null
    @Volatile
    private var tokenLastFetchMs: Long = 0L

    /**
     * Fetches an anonymous guest bearer token from the x-user header of a lightweight API call.
     * Tokens are cached for an hour; pass [forceRefresh] after a 401/441 response.
     */
    private suspend fun fetchAnonymousToken(forceRefresh: Boolean = false): String? {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedGuestToken != null && now - tokenLastFetchMs < 3_600_000) {
            return cachedGuestToken
        }
        try {
            val pingUrl = "https://apig.inmoviebox.com/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=4516404531735022304&page=1&perPage=1"
            val xClientToken = generateXClientToken(now)
            val xTrSignature = generateXTrSignature("GET", "application/json", "application/json", pingUrl, hardcodedTimestamp = now)
            val headers = mapOf(
                "user-agent" to "com.community.mbox.in/50020042 (Linux; U; Android 16; en_IN; sdk_gphone64_x86_64; Build/BP22.250325.006; Cronet/133.0.6876.3)",
                "accept" to "application/json",
                "content-type" to "application/json",
                "x-client-token" to xClientToken,
                "x-tr-signature" to xTrSignature,
                "x-client-info" to """{"package_name":"com.community.mbox.in","version_name":"3.0.03.0529.03","version_code":50020042,"os":"android","os_version":"16","device_id":"$deviceId","install_store":"ps","gaid":"d7578036d13336cc","brand":"google","model":"SM-S918B","system_language":"en","net":"NETWORK_WIFI","region":"IN","timezone":"Asia/Calcutta","sp_code":""}""",
                "x-client-status" to "0"
            )
            val res = app.get(pingUrl, headers = headers)
            val xUserHeader = res.headers["x-user"]
            if (!xUserHeader.isNullOrBlank()) {
                val tok = jacksonObjectMapper().readTree(xUserHeader)["token"]?.asText()
                if (!tok.isNullOrBlank()) {
                    cachedGuestToken = tok
                    tokenLastFetchMs = now
                    return tok
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    @SuppressLint("UseKtx")
    private fun buildCanonicalString(
        method: String,
        accept: String?,
        contentType: String?,
        url: String,
        body: String?,
        timestamp: Long
    ): String {
        val parsed = Uri.parse(url)
        val path = parsed.path ?: ""
        
        // Build query string with sorted parameters (if any)
        val query = if (parsed.queryParameterNames.isNotEmpty()) {
            parsed.queryParameterNames.sorted().joinToString("&") { key ->
                parsed.getQueryParameters(key).joinToString("&") { value ->
                    "$key=$value"  // Don't URL encode here - Python doesn't do it
                }
            }
        } else ""
        
        val canonicalUrl = if (query.isNotEmpty()) "$path?$query" else path

        val bodyBytes = body?.toByteArray(Charsets.UTF_8)
        val bodyHash = if (bodyBytes != null) {
            val trimmed = if (bodyBytes.size > 102400) bodyBytes.copyOfRange(0, 102400) else bodyBytes
            md5(trimmed)
        } else ""

        val bodyLength = bodyBytes?.size?.toString() ?: ""
        return "${method.uppercase()}\n" +
                "${accept ?: ""}\n" +
                "${contentType ?: ""}\n" +
                "$bodyLength\n" +
                "$timestamp\n" +
                "$bodyHash\n" +
                canonicalUrl
    }

    private fun generateXTrSignature(
        method: String,
        accept: String?,
        contentType: String?,
        url: String,
        body: String? = null,
        useAltKey: Boolean = false,
        hardcodedTimestamp: Long? = null
    ): String {
        val timestamp = hardcodedTimestamp ?: System.currentTimeMillis()
        val canonical = buildCanonicalString(method, accept, contentType, url, body, timestamp)
        val secret = if (useAltKey) secretKeyAlt else secretKeyDefault
        val secretBytes = base64DecodeArray(secret)

        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(secretBytes, "HmacMD5"))
        val signature = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
        val signatureB64 = base64Encode(signature)

        return "$timestamp|2|$signatureB64"
    }

     override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        
        val url = "$mainUrl/wefeed-mobile-bff/tab-operating?page=1&tabId=0&version="

        // Generate required security headers.
        val xClientToken = generateXClientToken()
        val xTrSignature = generateXTrSignature("GET", "application/json", "application/json", url)

        val headers = mapOf(
            "user-agent" to "com.community.mbox.in/50020042 (Linux; U; Android 16; en_IN; sdk_gphone64_x86_64; Build/BP22.250325.006; Cronet/133.0.6876.3)",
            "accept" to "application/json",
            "content-type" to "application/json",
            "connection" to "keep-alive",
            "x-client-token" to xClientToken,
            "x-tr-signature" to xTrSignature,
            "x-client-info" to """{"package_name":"com.community.mbox.in","version_name":"3.0.03.0529.03","version_code":50020042,"os":"android","os_version":"16","device_id":"$deviceId","install_store":"ps","gaid":"d7578036d13336cc","brand":"google","model":"${randomBrandModel()}","system_language":"en","net":"NETWORK_WIFI","region":"IN","timezone":"Asia/Calcutta","sp_code":""}""",
            "x-client-status" to "0",
            "x-play-mode" to "2" // Optional, if needed for specific API behavior
        )

        val response = app.get(url, headers = headers)
        val responseBody = response.body?.string() ?: ""

        // Helper function to parse a 'subject' JSON object into your app's data model.
        fun parseSubject(subjectJson: JsonNode?): SearchResponse? {
            subjectJson ?: return null // Return null if the subject object is missing
            val subjectId = subjectJson["subjectId"]?.asText() ?: return null
            val title = subjectJson["title"]?.asText() ?: return null
            val coverUrl = subjectJson["cover"]?.get("url")?.asText()
            val subjectType = when (subjectJson["subjectType"]?.asInt()) {
                1 -> TvType.Movie
                2 -> TvType.TvSeries
                else -> TvType.Movie // Default to Movie
            }
            return newMovieSearchResponse(title, subjectId, subjectType) {
                this.posterUrl = coverUrl
                this.score = Score.from10(subjectJson["imdbRatingValue"]?.asText())
            }
        }

        // Use Jackson to parse the new, multi-section API response structure.
        val homePageLists = try {
            val mapper = jacksonObjectMapper()
            val root = mapper.readTree(responseBody)
            val sections = root["data"]?.get("items") ?: return newHomePageResponse(emptyList())

            // Iterate through each section (e.g., Banners, Trending Now, etc.)
            sections.mapNotNull { section ->
                val title = section["title"]?.asText()?.let {
                    if (it.equals("banner", ignoreCase = true)) "🔥Top Picks" else it
                } ?: return@mapNotNull null
                val type = section["type"]?.asText()

                // Extract the list of media items based on the section type.
                val mediaList = when (type) {
                    "BANNER" -> section["banner"]?.get("banners")
                        ?.mapNotNull { bannerItem -> parseSubject(bannerItem["subject"]) }
                    "SUBJECTS_MOVIE" -> section["subjects"]
                        ?.mapNotNull { subjectItem -> parseSubject(subjectItem) }
                    "CUSTOM" -> section["customData"]?.get("items")
                        ?.mapNotNull { customItem -> parseSubject(customItem["subject"]) }
                    else -> null
                }

                // Only create a HomePageList if the section contains valid media items.
                if (mediaList.isNullOrEmpty()) {
                    null
                } else {
                    HomePageList(title, mediaList)
                }
            }
        } catch (e: Exception) {
            // In case of a parsing error, return an empty list.
            e.printStackTrace()
            emptyList()
        }

        return newHomePageResponse(homePageLists)
    }

    override suspend fun search(query: String,page: Int): SearchResponseList {
        
        val url = "$mainUrl/wefeed-mobile-bff/subject-api/search/v2"
        val jsonBody = """{"page": $page, "perPage": 20, "keyword": "$query"}"""
        val xClientToken = generateXClientToken()
        val xTrSignature = generateXTrSignature("POST", "application/json", "application/json; charset=utf-8", url, jsonBody)
        val token = fetchAnonymousToken()
        val headers = mutableMapOf(
            "user-agent" to "com.community.mbox.in/50020042 (Linux; U; Android 16; en_IN; sdk_gphone64_x86_64; Build/BP22.250325.006; Cronet/133.0.6876.3)",
            "accept" to "application/json",
            "content-type" to "application/json; charset=utf-8",
            "connection" to "keep-alive",
            "x-client-token" to xClientToken,
            "x-tr-signature" to xTrSignature,
            "x-client-info" to """{"package_name":"com.community.mbox.in","version_name":"3.0.03.0529.03","version_code":50020042,"os":"android","os_version":"16","device_id":"$deviceId","install_store":"ps","gaid":"d7578036d13336cc","brand":"google","model":"${randomBrandModel()}","system_language":"en","net":"NETWORK_WIFI","region":"IN","timezone":"Asia/Calcutta","sp_code":""}""",
            "x-client-status" to "0"
        )
        if (!token.isNullOrBlank()) {
            headers["Authorization"] = "Bearer $token"
        }
        var response = app.post(
            url,
            headers = headers,
            requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
        )
        if (response.code == 441 || response.code == 401) {
            val freshToken = fetchAnonymousToken(forceRefresh = true)
            if (!freshToken.isNullOrBlank()) {
                headers["Authorization"] = "Bearer $freshToken"
            }
            headers["x-tr-signature"] = generateXTrSignature("POST", "application/json", "application/json; charset=utf-8", url, jsonBody)
            response = app.post(
                url,
                headers = headers,
                requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
            )
        }

        val responseBody = response.body.string()
        val mapper = jacksonObjectMapper()
        val root = mapper.readTree(responseBody)
        val results = root.get("data")?.get("results") ?: return newSearchResponseList(emptyList())
        val searchList = mutableListOf<SearchResponse>()
        for (result in results) {
            val subjects = result["subjects"] ?: continue
            for (subject in subjects) {
            val title = subject["title"]?.asText() ?: continue
            val id = subject["subjectId"]?.asText() ?: continue
            val coverImg = subject["cover"]?.get("url")?.asText()
            val subjectType = subject["subjectType"]?.asInt() ?: 1
            val type = when (subjectType) {
                        1 -> TvType.Movie
                        2 -> TvType.TvSeries
                        else -> TvType.Movie
                }
            searchList.add(
                newMovieSearchResponse(
                name = title,
                url = id,
                type = type
                ) {
                    this.posterUrl = coverImg
                    this.score = Score.from10(subject["imdbRatingValue"]?.asText())
                }
            )
            }
        }
        return searchList.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        

        val id = Regex("""subjectId=([^&]+)""")
            .find(url)
            ?.groupValues?.get(1)
            ?: url.substringAfterLast('/')


        val finalUrl = "$mainUrl/wefeed-mobile-bff/subject-api/get?subjectId=$id"
        val xClientToken = generateXClientToken()
        val xTrSignature = generateXTrSignature("GET", "application/json", "application/json", finalUrl)

        val token = fetchAnonymousToken()
        val headers = mutableMapOf(
            "user-agent" to "com.community.mbox.in/50020042 (Linux; U; Android 16; en_IN; ${randomBrandModel()}; Build/BP22.250325.006; Cronet/133.0.6876.3)",
            "accept" to "application/json",
            "content-type" to "application/json",
            "connection" to "keep-alive",
            "x-client-token" to xClientToken,
            "x-tr-signature" to xTrSignature,
            "x-client-info" to """{"package_name":"com.community.mbox.in","version_name":"3.0.03.0529.03","version_code":50020042,"os":"android","os_version":"16","device_id":"$deviceId","install_store":"ps","gaid":"d7578036d13336cc","brand":"google","model":"${randomBrandModel()}","system_language":"en","net":"NETWORK_WIFI","region":"IN","timezone":"Asia/Calcutta","sp_code":""}""",
            "x-client-status" to "0",
            "x-play-mode" to "2"
        )
        if (!token.isNullOrBlank()) {
            headers["Authorization"] = "Bearer $token"
        }

        var response = app.get(finalUrl, headers = headers)
        if (response.code == 441 || response.code == 401) {
            val freshToken = fetchAnonymousToken(forceRefresh = true)
            if (!freshToken.isNullOrBlank()) {
                headers["Authorization"] = "Bearer $freshToken"
            }
            headers["x-tr-signature"] = generateXTrSignature("GET", "application/json", "application/json", finalUrl)
            response = app.get(finalUrl, headers = headers)
        }
        if (response.code != 200) {
            throw ErrorLoadingException("Failed to load data: ${response.body.string()}")
        }

        val body = response.body.string()
        val mapper = jacksonObjectMapper()
        val root = mapper.readTree(body)
        val data = root["data"] ?: throw ErrorLoadingException("No data")

        val title = data["title"]?.asText()?.substringBefore("[") ?: throw ErrorLoadingException("No title found")
        val description = data["description"]?.asText()
        val releaseDate = data["releaseDate"]?.asText()
        val duration = data["duration"]?.asText()
        val genre = data["genre"]?.asText()
        val imdbRating = data["imdbRatingValue"]?.asText()?.toDoubleOrNull()?.times(10)?.toInt()
        val year = releaseDate?.substring(0, 4)?.toIntOrNull()

        val coverUrl = data["cover"]?.get("url")?.asText()
        val backgroundUrl = data["cover"]?.get("url")?.asText()

        val subjectType = data["subjectType"]?.asInt() ?: 1

        val actors = data["staffList"]
            ?.mapNotNull { staff ->
                val staffType = staff["staffType"]?.asInt()
                if (staffType == 1) {
                    val name = staff["name"]?.asText() ?: return@mapNotNull null
                    val character = staff["character"]?.asText()
                    val avatarUrl = staff["avatarUrl"]?.asText()
                    ActorData(
                        Actor(name, avatarUrl),
                        roleString = character
                    )
                } else null
            }
            ?.distinctBy { it.actor.name }
            ?: emptyList()


        val tags = genre?.split(",")?.map { it.trim() } ?: emptyList()

        val durationMinutes = duration?.let { dur ->
            val regex = """(\d+)h\s*(\d+)m""".toRegex()
            val m = regex.find(dur)
            if (m != null) {
                val h = m.groupValues[1].toIntOrNull() ?: 0
                val min = m.groupValues[2].toIntOrNull() ?: 0
                h * 60 + min
            } else dur.replace("m", "").toIntOrNull()
        }

        val type = when (subjectType) {
            1 -> TvType.Movie
            2 -> TvType.TvSeries
            7 -> TvType.TvSeries
            else -> TvType.Movie
        }

        val (tmdbId, imdbId) = identifyID(
            title = title.substringBefore("(").substringBefore("["),
            year = releaseDate?.take(4)?.toIntOrNull(),
            imdbRatingValue = imdbRating?.toDouble(),
        )

        val logoUrl = fetchTmdbLogoUrl(
            tmdbAPI = "https://api.themoviedb.org/3",
            apiKey = "98ae14df2b8d8f8f8136499daf79f0e0",
            type = type,
            tmdbId = tmdbId,
            appLangCode = "en"
        )

        val meta = if (!imdbId.isNullOrBlank()) fetchMetaData(imdbId, type) else null
        val metaVideos = meta?.get("videos")?.toList() ?: emptyList()

        val Poster = meta?.get("poster")?.asText() ?: coverUrl
        val Background = meta?.get("background")?.asText() ?: backgroundUrl
        val Description = meta?.get("overview")?.asText() ?: description
        val IMDBRating = meta?.get("imdbRating")?.asText()

        if (type == TvType.TvSeries) {
            val allSubjectIds = mutableListOf<String>()
            allSubjectIds.add(id)
            data["dubs"]?.forEach {
                val sid = it["subjectId"]?.asText()
                if (!sid.isNullOrBlank() && sid !in allSubjectIds) {
                    allSubjectIds.add(sid)
                }
            }

            val episodeMap = mutableMapOf<Int, MutableSet<Int>>() // season -> episodes

            for (subjectId in allSubjectIds) {
                val seasonUrl = "$mainUrl/wefeed-mobile-bff/subject-api/season-info?subjectId=$subjectId"
                val seasonSig = generateXTrSignature("GET", "application/json", "application/json", seasonUrl)

                val seasonHeaders = headers.toMutableMap().apply {
                    put("x-tr-signature", seasonSig)
                }

                val seasonResponse = app.get(seasonUrl, headers = seasonHeaders)
                if (seasonResponse.code != 200) continue

                val seasonRoot = mapper.readTree(seasonResponse.body.string())
                val seasons = seasonRoot["data"]?.get("seasons")

                if (seasons == null || !seasons.isArray || seasons.size() == 0) {
                    continue
                }

                seasons.forEach { season ->
                    val seasonNumber = season["se"]?.asInt() ?: 1
                    val maxEp = (season["maxEp"]?.asInt() ?: 1).coerceIn(1, 10000)

                    val epSet = episodeMap.getOrPut(seasonNumber) { mutableSetOf() }

                    for (ep in 1..maxEp) {
                        epSet.add(ep)
                    }
                }
            }

            val episodes = mutableListOf<Episode>()

            fun epKey(season: Int, episode: Int) = (season.toLong() shl 32) or (episode.toLong() and 0xffffffffL)
            val metaByEpisode = HashMap<Long, JsonNode>(metaVideos.size * 2)
            for (video in metaVideos) {
                val s = video["season"]?.asInt() ?: continue
                val e = video["episode"]?.asInt() ?: continue
                metaByEpisode.putIfAbsent(epKey(s, e), video)
            }

            episodeMap.forEach { (seasonNumber, epSet) ->
                epSet.sorted().forEach { episodeNumber ->

                    val epMeta = metaByEpisode[epKey(seasonNumber, episodeNumber)]

                    val epName = epMeta?.get("name")?.asText()
                        ?: epMeta?.get("title")?.asText()?.takeIf { it.isNotBlank() }
                        ?: "S${seasonNumber}E${episodeNumber}"

                    val epDesc = epMeta?.get("overview")?.asText()
                        ?: epMeta?.get("description")?.asText()
                        ?: "Season $seasonNumber Episode $episodeNumber"

                    val epThumb = epMeta?.get("thumbnail")?.asText()?.takeIf { it.isNotBlank() }
                        ?: coverUrl

                    val runtime = epMeta?.get("runtime")?.asText()
                        ?.filter { it.isDigit() }
                        ?.toIntOrNull()

                    val aired = epMeta?.get("released")?.asText()
                        ?.take(10)
                        ?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }

                    episodes.add(
                        newEpisode("$id|$seasonNumber|$episodeNumber") {
                            this.name = epName
                            this.season = seasonNumber
                            this.episode = episodeNumber
                            this.posterUrl = epThumb
                            this.description = epDesc
                            this.runTime = runtime
                            if (aired != null) addDate(aired)
                        }
                    )
                }
            }

            // fallback
            if (episodes.isEmpty()) {
                episodes.add(
                    newEpisode("$id|1|1") {
                        this.name = "Episode 1"
                        this.season = 1
                        this.episode = 1
                        this.posterUrl = coverUrl
                    }
                )
            }

            return newTvSeriesLoadResponse(title, finalUrl, type, episodes) {
                this.posterUrl = coverUrl ?: Poster
                this.backgroundPosterUrl = Background ?: backgroundUrl ?: Poster
                try { this.logoUrl = logoUrl } catch(_: Throwable) {}
                this.plot = Description ?: description
                this.year = year
                this.tags = tags
                this.actors = actors
                this.score = Score.from10(IMDBRating) ?: imdbRating?.let { Score.from10(it) }
                this.duration = durationMinutes
                addImdbId(imdbId)
                addTMDbId(tmdbId.toString())
            }
        }

        return newMovieLoadResponse(title, finalUrl, type, id) {
            this.posterUrl = coverUrl ?: Poster
            this.backgroundPosterUrl = Background ?: backgroundUrl
            try { this.logoUrl = logoUrl } catch(_:Throwable){}
            this.plot = Description ?: description
            this.year = year
            this.tags = tags
            this.actors = actors
            this.score = Score.from10(IMDBRating) ?:imdbRating?.let { Score.from10(it) }
            this.duration = durationMinutes
            addImdbId(imdbId)
            addTMDbId(tmdbId.toString())
        }
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val parts = data.split("|")
            val originalSubjectId = when {
                parts[0].contains("get?subjectId") -> {
                    Regex("""subjectId=([^&]+)""")
                        .find(parts[0])
                        ?.groupValues?.get(1)
                        ?: parts[0].substringAfterLast('/')
                }
                parts[0].contains("/") -> {
                    parts[0].substringAfterLast('/')
                }
                else -> parts[0]
            }

            val season = if (parts.size > 1) parts[1].toIntOrNull() ?: 0 else 0
            val episode = if (parts.size > 2) parts[2].toIntOrNull() ?: 0 else 0
            val mapper = jacksonObjectMapper()

            var token = fetchAnonymousToken()
            val subjectUrl = "$mainUrl/wefeed-mobile-bff/subject-api/get?subjectId=$originalSubjectId"
            fun subjectHeaders(): Map<String, String> = buildMap {
                put("user-agent", MBOX_APP_UA)
                put("accept", "application/json")
                put("content-type", "application/json")
                put("connection", "keep-alive")
                put("x-client-token", generateXClientToken())
                put("x-tr-signature", generateXTrSignature("GET", "application/json", "application/json", subjectUrl))
                put("x-client-info", """{"package_name":"com.community.mbox.in","version_name":"4.0.02.0831.03","version_code":50020126,"os":"android","os_version":"14","install_ch":"official","device_id":"$deviceId","install_store":"official","gaid":"1b2212c1-dadf-43c3-a0c8-bd6ce48ae22d","brand":"Google","model":"Pixel 8","system_language":"en","net":"NETWORK_WIFI","region":"IN","timezone":"Asia/Calcutta","sp_code":"","X-Play-Mode":"1","X-Idle-Data":"1","X-Family-Mode":"0","X-Content-Mode":"0"}""")
                put("x-client-status", "0")
                if (!token.isNullOrBlank()) put("Authorization", "Bearer $token")
            }

            var subjectResponse = app.get(subjectUrl, headers = subjectHeaders())
            if (subjectResponse.code == 441 || subjectResponse.code == 401) {
                token = fetchAnonymousToken(forceRefresh = true)
                subjectResponse = app.get(subjectUrl, headers = subjectHeaders())
            }

            val subjectIds = mutableListOf<Pair<String, String>>() // Pair of (subjectId, language)
            var originalLanguageName = "Original"
            var detailDomain: String? = null
            var detailPath: String? = null
            if (subjectResponse.code == 200) {
                val subjectData = mapper.readTree(subjectResponse.body.string())["data"]
                val detailUrl = subjectData?.get("detailUrl")?.asText()
                if (!detailUrl.isNullOrBlank()) {
                    runCatching {
                        val uri = URI(detailUrl)
                        detailDomain = "${uri.scheme}://${uri.host}"
                        detailPath = detailUrl.trimEnd('/').substringAfterLast('/')
                    }
                }
                val dubs = subjectData?.get("dubs")
                if (dubs != null && dubs.isArray) {
                    for (dub in dubs) {
                        val dubSubjectId = dub["subjectId"]?.asText()
                        val lanName = dub["lanName"]?.asText()
                        if (dubSubjectId != null && lanName != null) {
                            if (dubSubjectId == originalSubjectId) {
                                originalLanguageName = lanName
                            } else {
                                subjectIds.add(Pair(dubSubjectId, lanName))
                            }
                        }
                    }
                }
            }

            val xUserHeader = subjectResponse.headers["x-user"]
            if (!xUserHeader.isNullOrBlank()) {
                try {
                    val xToken = mapper.readTree(xUserHeader)["token"]?.asText()
                    if (!xToken.isNullOrBlank()) token = xToken
                } catch (_: Exception) {
                }
            }

            // Always add the original subject ID first as the default source with proper language name
            subjectIds.add(0, Pair(originalSubjectId, originalLanguageName))

            val basePath = detailPath
            val baseDomain = detailDomain
            if (basePath.isNullOrBlank() || baseDomain.isNullOrBlank()) return true

            val dubPaths = fetchDubDetailPaths(basePath, mapper)

            // Process each subjectId (including dubs)
            for ((subjectId, language) in subjectIds) {
                try {
                    val langLabel = language.replace("dub", "Audio")
                    val subPath = if (subjectId == originalSubjectId) basePath else dubPaths[subjectId] ?: continue
                    val subDomain = baseDomain

                    val playUrl = "$mainUrl/wefeed-h5api-bff/subject/play?subjectId=$subjectId&se=$season&ep=$episode&detailPath=$subPath&streamSignType=1&supportCodecs%5Bhevc%5D=1&supportCodecs%5Bh264%5D=1"
                    val playHeaders = buildMap {
                        put("User-Agent", MBOX_MOBILE_WEB_UA)
                        put("Referer", "$subDomain/movies/$subPath?id=$subjectId&type=/movie/detail&detailSe=&detailEp=&lang=en")
                        put("Accept", "application/json")
                        put("x-client-info", """{"timezone":"Asia/Calcutta"}""")
                        put("x-request-lang", "en")
                        put("x-vip-restrict", "0")
                        put("x-no-high-risk-restrict", "0")
                        put("x-source", "")
                        if (!token.isNullOrBlank()) put("Authorization", "Bearer $token")
                    }

                    val response = app.get(playUrl, headers = playHeaders)
                    if (response.code != 200) continue
                    val playData = mapper.readTree(response.body.string())["data"] ?: continue

                    suspend fun emitAll(key: String, defaultFormat: String?) {
                        val streams = playData[key]
                        if (streams == null || !streams.isArray || streams.size() == 0) return
                        for (stream in streams) {
                            val fmt = defaultFormat ?: stream["format"]?.asText() ?: "MP4"
                            emitNetStream(
                                subjectId, season, episode, langLabel, token, mapper,
                                subDomain, subPath, stream, fmt, callback, subtitleCallback
                            )
                        }
                    }
                    emitAll("dash", "DASH")
                    emitAll("hls", "HLS")
                    emitAll("streams", null)
                } catch (_: Exception) {
                    continue
                }
            }

            return true

        } catch (_: Exception) {
            return false
        }
    }

    /** Maps each dub's subjectId to the H5 detailPath the web player expects for it. */
    private suspend fun fetchDubDetailPaths(detailPath: String, mapper: ObjectMapper): Map<String, String> {
        return try {
            val res = app.get(
                "https://movie-box.co/wefeed-h5api-bff/detail?detailPath=$detailPath",
                headers = mapOf(
                    "Accept" to "application/json",
                    "Origin" to "https://movie-box.co",
                    "Referer" to "https://mzfi.me/",
                    "User-Agent" to MBOX_DESKTOP_WEB_UA,
                    "x-client-info" to """{"timezone":"Asia/Calcutta"}"""
                )
            )
            val dubs = mapper.readTree(res.body.string())["data"]?.get("subject")?.get("dubs")
            buildMap {
                dubs?.forEach { dub ->
                    val id = dub["subjectId"]?.asText()
                    val path = dub["detailPath"]?.asText()
                    if (!id.isNullOrBlank() && !path.isNullOrBlank()) put(id, path)
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private suspend fun emitNetStream(
        subjectId: String,
        season: Int,
        episode: Int,
        langLabel: String,
        token: String?,
        mapper: ObjectMapper,
        subDomain: String,
        subPath: String,
        stream: JsonNode,
        fmt: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val link = stream["url"]?.asText() ?: return
        val streamId = stream["id"]?.asText() ?: "$subjectId|$season|$episode"
        val resStr = stream["resolutions"]?.asText() ?: ""
        val quality = getHighestQuality(resStr)
        val signCookie = stream["signCookie"]?.asText()?.takeIf { it.isNotEmpty() }
        val signHeaderKey = stream["signHeaderKey"]?.asText()?.takeIf { it.isNotBlank() } ?: "X-MB-Token"

        val linkType = when {
            link.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
            fmt.equals("HLS", ignoreCase = true) || link.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
            fmt.equals("MP4", ignoreCase = true) || link.contains(".mp4", ignoreCase = true) || link.contains(".mkv", ignoreCase = true) -> ExtractorLinkType.VIDEO
            else -> INFER_TYPE
        }
        val linkName = when (linkType) {
            ExtractorLinkType.DASH -> "$name ($langLabel) DASH"
            ExtractorLinkType.VIDEO -> "$name ($langLabel) MP4"
            else -> "$name ($langLabel)"
        }

        callback.invoke(
            newExtractorLink(
                source = "$name $langLabel",
                name = linkName,
                url = link,
                type = linkType
            ) {
                this.headers = if (linkType == ExtractorLinkType.VIDEO) {
                    mapOf(
                        "Referer" to "https://movie-box.co/",
                        "User-Agent" to MBOX_DESKTOP_WEB_UA
                    )
                } else {
                    buildMap {
                        put("Origin", subDomain)
                        put("Referer", "$subDomain/movies/$subPath?id=$subjectId&type=/movie/detail&detailSe=&detailEp=&lang=en")
                        put("User-Agent", MBOX_MOBILE_WEB_UA)
                        if (signCookie != null) put(signHeaderKey, signCookie)
                    }
                }
                if (quality != null) {
                    this.quality = quality
                }
            }
        )

        try {
            val subLink = "$mainUrl/wefeed-mobile-bff/subject-api/get-stream-captions?subjectId=$subjectId&streamId=$streamId"
            val subHeaders = mapOf(
                "Authorization" to "Bearer $token",
                "user-agent" to MBOX_APP_UA,
                "Accept" to "",
                "x-client-info" to """{"package_name":"com.community.mbox.in","version_name":"4.0.02.0831.03","version_code":50020126,"os":"android","os_version":"14","install_ch":"official","device_id":"$deviceId","install_store":"official","gaid":"1b2212c1-dadf-43c3-a0c8-bd6ce48ae22d","brand":"Google","model":"Pixel 8","system_language":"en","net":"NETWORK_WIFI","region":"IN","timezone":"Asia/Calcutta","sp_code":""}""",
                "X-Client-Status" to "0",
                "Content-Type" to "",
                "X-Client-Token" to generateXClientToken(),
                "x-tr-signature" to generateXTrSignature("GET", "", "", subLink),
            )
            val subRoot = mapper.readTree(app.get(subLink, headers = subHeaders).text)
            val extCaptions = subRoot["data"]?.get("extCaptions")
            if (extCaptions != null && extCaptions.isArray) {
                for (caption in extCaptions) {
                    val captionUrl = caption["url"]?.asText() ?: continue
                    val lang = caption["language"]?.asText()
                        ?: caption["lanName"]?.asText()
                        ?: caption["lan"]?.asText()
                        ?: "Unknown"
                    subtitleCallback.invoke(
                        newSubtitleFile(
                            url = captionUrl,
                            lang = "$lang ($langLabel)"
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }

        try {
            val subLink = "$mainUrl/wefeed-mobile-bff/subject-api/get-ext-captions?subjectId=$subjectId&resourceId=$streamId&episode=0"
            val subHeaders = mapOf(
                "Authorization" to "Bearer $token",
                "User-Agent" to MBOX_APP_UA,
                "Accept" to "",
                "X-Client-Info" to """{"package_name":"com.community.mbox.in","version_name":"4.0.02.0831.03","version_code":50020126,"os":"android","os_version":"14","device_id":"$deviceId","install_store":"official","gaid":"d7578036d13336cc","brand":"Google","model":"Pixel 8","system_language":"en","net":"NETWORK_WIFI","region":"IN","timezone":"Asia/Calcutta","sp_code":""}""",
                "X-Client-Status" to "0",
                "Content-Type" to "",
                "X-Client-Token" to generateXClientToken(),
                "x-tr-signature" to generateXTrSignature("GET", "", "", subLink),
            )
            val subRoot = mapper.readTree(app.get(subLink, headers = subHeaders).text)
            val extCaptions = subRoot["data"]?.get("extCaptions")
            if (extCaptions != null && extCaptions.isArray) {
                for (caption in extCaptions) {
                    val captionUrl = caption["url"]?.asText() ?: continue
                    val lang = caption["lan"]?.asText()
                        ?: caption["lanName"]?.asText()
                        ?: caption["language"]?.asText()
                        ?: "Unknown"
                    subtitleCallback.invoke(
                        newSubtitleFile(
                            url = captionUrl,
                            lang = "$lang ($langLabel)"
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
    }

}

private const val MBOX_APP_UA = "com.community.mbox.in/50020126 (Linux; U; Android 14; en_IN; Pixel 8; Build/UD1A.230803.041; Cronet/145.0.7582.0)"
private const val MBOX_MOBILE_WEB_UA = "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36"
private const val MBOX_DESKTOP_WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36"

fun getHighestQuality(input: String): Int? {
    val qualities = listOf(
        "2160" to Qualities.P2160.value,
        "1440" to Qualities.P1440.value,
        "1080" to Qualities.P1080.value,
        "720"  to Qualities.P720.value,
        "480"  to Qualities.P480.value,
        "360"  to Qualities.P360.value,
        "240"  to Qualities.P240.value
    )

    for ((label, mappedValue) in qualities) {
        if (input.contains(label, ignoreCase = true)) {
            return mappedValue
        }
    }
    return null
}


private fun cleanTitle(s: String): String {
    return s.lowercase()
        .replace("[^a-z0-9 ]".toRegex(), " ")
        .replace("\\s+".toRegex(), " ")
        .trim()
}
private suspend fun identifyID(
    title: String,
    year: Int?,
    imdbRatingValue: Double?
): Pair<Int?, String?> {
    val normTitle = normalize(title)
    val res = searchAndPick(normTitle, year, imdbRatingValue)
    if (res.first != null) return res

    return Pair(null, null)
}

private suspend fun searchAndPick(
    normTitle: String,
    year: Int?,
    imdbRatingValue: Double?,
): Pair<Int?, String?> {

    suspend fun doSearch(endpoint: String, extraParams: String = ""): org.json.JSONArray? {
        val url = buildString {
            append("https://api.themoviedb.org/3/").append(endpoint)
            append("?api_key=").append("1865f43a0549ca50d341dd9ab8b29f49")
            append(extraParams)
            append("&include_adult=false&page=1")
            append("&random=").append(Random.nextInt())
        }
        val text = app.get(url).text
        return JSONObject(text).optJSONArray("results")
    }

    val multiResults = doSearch("search/multi", "&query=${URLEncoder.encode(normTitle, "UTF-8")}" + (if (year != null) "&year=$year" else ""))
    val searchQueues: List<Pair<String, org.json.JSONArray?>> = listOf(
        "multi" to multiResults,
        "tv" to doSearch("search/tv", "&query=${URLEncoder.encode(normTitle, "UTF-8")}" + (if (year != null) "&first_air_date_year=$year" else "")),
        "movie" to doSearch("search/movie", "&query=${URLEncoder.encode(normTitle, "UTF-8")}" + (if (year != null) "&year=$year" else ""))
    )

    var bestId: Int? = null
    var bestScore = -1.0
    var bestIsTv = false

    for ((sourceType, results) in searchQueues) {
        if (results == null) continue
        for (i in 0 until results.length()) {
            val o = results.getJSONObject(i)

            val mediaType = when (sourceType) {
                "multi" -> o.optString("media_type", "")
                "tv" -> "tv"
                else -> "movie"
            }

            val candidateId = o.optInt("id", -1)
            if (candidateId == -1) continue

            val titles = listOf(
                o.optString("title"),
                o.optString("name"),
                o.optString("original_title"),
                o.optString("original_name")
            ).filter { it.isNotBlank() }

            val candDate = when (mediaType) {
                "tv" -> o.optString("first_air_date", "")
                else -> o.optString("release_date", "")
            }
            val candYear = candDate.take(4).toIntOrNull()
            val candRating = o.optDouble("vote_average", Double.NaN)

            // scoring
            var score = 0.0
            val normClean = cleanTitle(normTitle)

            var titleScore = 0.0
            for (t in titles) {
                val candClean = cleanTitle(t)

                if (tokenEquals(candClean, normClean)) {
                    titleScore = 50.0
                    break
                }

                if (candClean.contains(normClean) || normClean.contains(candClean)) {
                    titleScore = maxOf(titleScore, 20.0)
                }
            }
            score += titleScore


            if (candYear != null && year != null && candYear == year) score += 35.0

            if (imdbRatingValue != null && !candRating.isNaN()) {
                val diff = kotlin.math.abs(candRating - imdbRatingValue)
                if (diff <= 0.5) score += 10.0 else if (diff <= 1.0) score += 5.0
            }

            if (o.has("popularity")) score += (o.optDouble("popularity", 0.0) / 100.0).coerceAtMost(5.0)

            if (score > bestScore) {
                bestScore = score
                bestId = candidateId
                bestIsTv = (mediaType == "tv")
            }
        }
    }

    if (bestId == null || bestScore < 40.0) return Pair(null, null)

    // fetch details for external_ids
    val detailKind = if (bestIsTv) "tv" else "movie"
    val detailUrl = "https://api.themoviedb.org/3/$detailKind/$bestId?api_key=1865f43a0549ca50d341dd9ab8b29f49&append_to_response=external_ids&random=${Random.nextInt()}"
    val detailText = app.get(detailUrl).text
    val detailJson = JSONObject(detailText)
    val imdbId = detailJson.optJSONObject("external_ids")?.optString("imdb_id")

    return Pair(bestId, imdbId)
}

private fun tokenEquals(a: String, b: String): Boolean {
    val sa = a.split("\\s+".toRegex()).filter { it.isNotBlank() }.toSet()
    val sb = b.split("\\s+".toRegex()).filter { it.isNotBlank() }.toSet()
    if (sa.isEmpty() || sb.isEmpty()) return false
    val inter = sa.intersect(sb).size
    return inter >= max(1, minOf(sa.size, sb.size) * 3 / 4)
}

private fun normalize(s: String): String {
    val t = s.replace("\\[.*?]".toRegex(), " ")
        .replace("\\(.*?\\)".toRegex(), " ")
        .replace("(?i)\\b(dub|dubbed|hd|4k|hindi|tamil|telugu|dual audio)\\b".toRegex(), " ")
        .trim()
        .lowercase()
        .replace(":", " ")
        .replace("\\p{Punct}".toRegex(), " ")
        .replace("\\s+".toRegex(), " ")
    return t
}

private suspend fun fetchMetaData(imdbId: String?, type: TvType): JsonNode? {
    if (imdbId.isNullOrBlank()) return null

    val metaType = if (type == TvType.TvSeries) "series" else "movie"
    val url = "https://v3-cinemeta.strem.io/meta/$metaType/$imdbId.json"

    return try {
        val resp = app.get(url).text
        mapper.readTree(resp)["meta"]
    } catch (_: Exception) {
        null
    }
}

suspend fun fetchTmdbLogoUrl(
    tmdbAPI: String,
    apiKey: String,
    type: TvType,
    tmdbId: Int?,
    appLangCode: String?
): String? {

    if (tmdbId == null) return null

    val url = if (type == TvType.Movie)
        "$tmdbAPI/movie/$tmdbId/images?api_key=$apiKey&random=${Random.nextInt()}"
    else
        "$tmdbAPI/tv/$tmdbId/images?api_key=$apiKey&random=${Random.nextInt()}"

    val json = runCatching { JSONObject(app.get(url).text) }.getOrNull() ?: return null
    val logos = json.optJSONArray("logos") ?: return null
    if (logos.length() == 0) return null

    val lang = appLangCode?.trim()?.lowercase()

    fun path(o: JSONObject) = o.optString("file_path")
    fun isSvg(o: JSONObject) = path(o).endsWith(".svg", true)
    fun urlOf(o: JSONObject) = "https://image.tmdb.org/t/p/w500${path(o)}"

    // Language match
    var svgFallback: JSONObject? = null

    for (i in 0 until logos.length()) {
        val logo = logos.optJSONObject(i) ?: continue
        val p = path(logo)
        if (p.isBlank()) continue

        val l = logo.optString("iso_639_1").trim().lowercase()
        if (l == lang) {
            if (!isSvg(logo)) return urlOf(logo)
            if (svgFallback == null) svgFallback = logo
        }
    }
    svgFallback?.let { return urlOf(it) }

    // Highest voted fallback
    var best: JSONObject? = null
    var bestSvg: JSONObject? = null

    fun voted(o: JSONObject) = o.optDouble("vote_average", 0.0) > 0 && o.optInt("vote_count", 0) > 0

    fun better(a: JSONObject?, b: JSONObject): Boolean {
        if (a == null) return true
        val aAvg = a.optDouble("vote_average", 0.0)
        val aCnt = a.optInt("vote_count", 0)
        val bAvg = b.optDouble("vote_average", 0.0)
        val bCnt = b.optInt("vote_count", 0)
        return bAvg > aAvg || (bAvg == aAvg && bCnt > aCnt)
    }

    for (i in 0 until logos.length()) {
        val logo = logos.optJSONObject(i) ?: continue
        if (!voted(logo)) continue

        if (isSvg(logo)) {
            if (better(bestSvg, logo)) bestSvg = logo
        } else {
            if (better(best, logo)) best = logo
        }
    }

    best?.let { return urlOf(it) }
    bestSvg?.let { return urlOf(it) }

    // No language match & no voted logos
    return null


}
