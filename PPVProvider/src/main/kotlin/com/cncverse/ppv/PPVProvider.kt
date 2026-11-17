package com.cncverse.ppv

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
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.delay
import okhttp3.Interceptor
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PPVProvider : MainAPI() {
    override var mainUrl = "https://ppv.st"
    override var name = "PPV"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)
    override val mainPage = mainPageOf("" to "PPV")

    private val apis = listOf("https://api.ppv.st/api/streams", "https://api.ppv.cx/api/streams")
    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

    private var cache: Pair<Long, List<Event>>? = null

    private suspend fun events(): List<Event> {
        cache?.let { (at, list) -> if (System.currentTimeMillis() - at < 60_000) return list }

        var json: JSONObject? = null
        for (attempt in 0 until 4) {
            json = apis.firstNotNullOfOrNull { api ->
                runCatching {
                    JSONObject(
                        app.get(
                            api,
                            headers = mapOf("User-Agent" to ua, "Referer" to "$mainUrl/", "Origin" to mainUrl),
                            timeout = 10
                        ).text
                    )
                }.getOrNull()?.takeIf { it.optBoolean("success") }
            }
            if (json != null) break
            delay((attempt + 1) * 500L)
        }
        if (json == null) {
            return cache?.second ?: throw ErrorLoadingException("PPV didn't respond, refresh to try again")
        }

        val list = json.optJSONArray("streams").objects().flatMap { cat ->
            val category = cat.optString("category")
            cat.optJSONArray("streams").objects().map { s ->
                val sources = listOf(Source(s.optString("source_tag").ifEmpty { "Main" }, s.optString("iframe"))) +
                    s.optJSONArray("substreams").objects().map { sub ->
                        Source(sub.optString("source_tag").ifEmpty { sub.optString("name") }, sub.optString("iframe"))
                    }
                Event(
                    id = s.optLong("id"),
                    name = s.optString("name"),
                    category = category,
                    tag = s.optString("tag"),
                    poster = s.optString("poster").ifEmpty { null },
                    uri = s.optString("uri_name"),
                    startsAt = s.optLong("starts_at"),
                    endsAt = s.optLong("ends_at"),
                    alwaysLive = s.optInt("always_live") == 1,
                    sources = sources.filter { it.url.startsWith("http") }
                )
            }
        }
        cache = System.currentTimeMillis() to list
        return list
    }

    private fun startLabel(e: Event): String? {
        if (e.startsAt <= 0) return null
        return SimpleDateFormat("MMM dd, yyyy HH:mm z", Locale.US).format(Date(e.startsAt * 1000))
    }

    private fun ended(e: Event, now: Long) = !e.alwaysLive && e.endsAt in 1..now

    private fun status(e: Event, now: Long) = when {
        e.alwaysLive -> ""
        ended(e, now) -> "✅"
        e.live(now) -> "🔴"
        else -> "🔜"
    }

    private fun sportIcon(cat: String) = when (cat.lowercase()) {
        "cricket" -> "🏏"
        "football" -> "⚽"
        "basketball" -> "🏀"
        "american football" -> "🏈"
        "ice hockey" -> "🏒"
        "combat sports" -> "🥊"
        "motorsports" -> "🏎️"
        "rugby", "australian football" -> "🏉"
        "wrestling" -> "🤼"
        "golf" -> "⛳"
        "tennis" -> "🎾"
        else -> "📺"
    }

    private val sportOrder = listOf(
        "Cricket", "Football", "Basketball", "American Football", "Ice Hockey",
        "Combat Sports", "Motorsports", "Rugby", "Wrestling", "Golf"
    )

    private fun card(e: Event, now: Long): SearchResponse {
        val s = status(e, now)
        return newLiveSearchResponse(
            if (s.isEmpty()) e.name else "$s ${e.name}",
            "$mainUrl/live/${e.id}",
            TvType.Live
        ) {
            this.posterUrl = e.poster
        }
    }

    // Live first, then upcoming, then ended; earliest start first within each group.
    private fun sortKey(now: Long): Comparator<Event> = compareBy(
        { if (it.live(now)) 0 else if (ended(it, now)) 2 else 1 },
        { it.startsAt }
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val now = System.currentTimeMillis() / 1000
        val all = events().filter { it.sources.isNotEmpty() && !ended(it, now) }

        val rows = all.filter { !it.alwaysLive }
            .groupBy { it.category }
            .toList()
            .sortedWith(
                compareBy<Pair<String, List<Event>>>(
                    { sportOrder.indexOf(it.first).let { i -> if (i < 0) 99 else i } },
                    { it.first }
                )
            )
            .map { (cat, list) ->
                HomePageList("${sportIcon(cat)} $cat", list.sortedWith(sortKey(now)).map { card(it, now) }, true)
            }
        val channels = all.filter { it.alwaysLive }
            .takeIf { it.isNotEmpty() }
            ?.let { list -> HomePageList("📺 24/7 Channels", list.map { card(it, now) }, true) }

        return newHomePageResponse(rows + listOfNotNull(channels), false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val now = System.currentTimeMillis() / 1000
        return events()
            .filter {
                it.sources.isNotEmpty() && !ended(it, now) &&
                    (it.name.contains(query, true) || it.category.contains(query, true) || it.tag.contains(query, true))
            }
            .sortedWith(sortKey(now))
            .map { card(it, now) }
    }

    override suspend fun load(url: String): LoadResponse {
        val id = url.substringAfterLast("/").toLongOrNull()
        val e = events().firstOrNull { it.id == id }
            ?: throw ErrorLoadingException("This stream is no longer listed on PPV")
        val now = System.currentTimeMillis() / 1000

        val plot = buildString {
            append("📌 ${e.category}\n")
            if (e.tag.isNotEmpty()) append("🏆 ${e.tag}\n")
            startLabel(e)?.let { append("🕐 $it\n") }
            when {
                e.alwaysLive -> append("📺 24/7 channel\n")
                ended(e, now) -> append("✅ Ended\n")
                e.live(now) -> append("🔴 Live now\n")
                else -> append("🔜 Upcoming\n")
            }
            append("\n📡 Available Servers: ${e.sources.size}")
        }

        return newLiveStreamLoadResponse(e.name, url, e.sources.toJson()) {
            this.posterUrl = e.poster
            this.plot = plot
            this.tags = listOf(e.category)
        }
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor = SegmentUnwrap.interceptor

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val sources = tryParseJson<List<Source>>(data) ?: return false
        var found = false
        sources.amap { src ->
            val s = sniffStream(src.url, "$mainUrl/") ?: return@amap
            found = true
            callback(
                newExtractorLink(name, "$name ${src.label}", s.url, ExtractorLinkType.M3U8) {
                    this.referer = s.headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value ?: ""
                    this.headers = s.headers
                    this.quality = if (src.url.contains("540p") || src.label.startsWith("SD")) {
                        Qualities.P480.value
                    } else {
                        Qualities.P1080.value
                    }
                }
            )
        }
        return found
    }

    data class Event(
        val id: Long,
        val name: String,
        val category: String,
        val tag: String,
        val poster: String?,
        val uri: String,
        val startsAt: Long,
        val endsAt: Long,
        val alwaysLive: Boolean,
        val sources: List<Source>
    ) {
        fun live(now: Long) = alwaysLive || (startsAt in 1..now && (endsAt == 0L || now < endsAt))
    }

    data class Source(val label: String, val url: String)
}

internal fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}
