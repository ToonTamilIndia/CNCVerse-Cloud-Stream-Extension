package com.cncverse

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * PlayZTV Live Events provider.
 *
 * Fetches live sports events from the PlayZTV back-end (same base URL as the
 * IPTV provider). Event slugs resolve to stream lists via
 * `PlayZTVProviderManager.fetchChannelStreams(slug)`.
 */
class PlayZTVLiveEventsProvider(override var name: String = "⚡PlayZTV Live Events", val customCatLink: String? = null) : MainAPI() {

    companion object {
        var context: android.content.Context? = null
    }

    override var mainUrl = "https://adsflw.xyz"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // ── Display helpers ───────────────────────────────────────────────────────

    private fun createDisplayTitle(event: PlayZLiveEventData): String {
        val info = event.eventInfo ?: return event.title
        return if (!info.teamA.isNullOrBlank() && !info.teamB.isNullOrBlank() &&
            !isSameTeam(info.teamA, info.teamB)
        ) "${info.teamA} vs ${info.teamB}"
        else info.teamA ?: event.title
    }

    private fun parseTime(value: String?): Long? {
        val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
        return value?.let { fmt.parse(it)?.time }
    }

    private fun getEventStatus(event: PlayZLiveEventData): String {
        val info = event.eventInfo ?: return ""
        val now = System.currentTimeMillis()
        return try {
            val start = parseTime(info.startTime)
            val end = parseTime(info.endTime)
            when {
                end != null && now >= end -> "✅"
                start != null && now >= start -> "🔴"
                start != null && now < start -> "🔜"
                else -> ""
            }
        } catch (_: Exception) { "" }
    }

    private fun isEventLive(event: PlayZLiveEventData): Boolean {
        val info = event.eventInfo ?: return false
        val now = System.currentTimeMillis()
        return try {
            val start = parseTime(info.startTime)
            val end = parseTime(info.endTime)
            (end == null || now < end) && start != null && now >= start
        } catch (_: Exception) { false }
    }

    private fun isEventEnded(event: PlayZLiveEventData): Boolean {
        val info = event.eventInfo ?: return false
        val now = System.currentTimeMillis()
        return try {
            val end = parseTime(info.endTime)
            end != null && now >= end
        } catch (_: Exception) { false }
    }

    private fun isSameTeam(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        return a.trim().equals(b.trim(), ignoreCase = true)
    }

    private fun generateMatchCardUrl(event: PlayZLiveEventData): String {
        val info = event.eventInfo
        val encode: (String) -> String = { java.net.URLEncoder.encode(it, "UTF-8") }

        val title = encode(info?.eventName ?: event.title)
        val teamA = encode(info?.teamA ?: "Team A")
        val teamB = encode(info?.teamB ?: "Team B")
        val sameTeams = isSameTeam(info?.teamA, info?.teamB)
        val teamAImg = info?.teamAFlag?.takeIf { it.isNotBlank() }
            ?: (if (sameTeams) info?.teamBFlag else null)
            ?: ""
        val teamBImg = if (sameTeams) "" else info?.teamBFlag ?: ""
        val eventLogo = info?.eventLogo ?: ""
        val isLive = isEventLive(event)
        val isEnded = isEventEnded(event)

        val time = try {
            info?.startTime?.let {
                val df = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
                val disp = SimpleDateFormat("MMM dd, yyyy hh:mm a z", Locale.US)
                df.parse(it)?.let { d -> encode(disp.format(d)) } ?: ""
            } ?: ""
        } catch (_: Exception) { "" }

        return buildString {
            append("https://card.cncverse.dpdns.org/?")
            append("title=$title")
            append("&teamA=$teamA")
            if (!sameTeams) append("&teamB=$teamB")
            if (teamAImg.isNotBlank()) append("&teamAImg=$teamAImg")
            if (teamBImg.isNotBlank()) append("&teamBImg=$teamBImg")
            if (eventLogo.isNotBlank()) append("&eventLogo=$eventLogo")
            if (time.isNotBlank()) append("&time=$time")
            append("&isLive=$isLive")
            append("&isEnded=$isEnded")
        }
    }

    private fun toSearchResponse(event: PlayZLiveEventData): SearchResponse {
        val displayTitle = createDisplayTitle(event)
        val status = getEventStatus(event)
        val fullTitle = if (status.isNotBlank()) "$status $displayTitle" else displayTitle
        val poster = if (event.cat == "Custom" && !event.image.isNullOrBlank()) {
            event.image
        } else {
            generateMatchCardUrl(event)
        }
        val loadData = LiveEventLoadData(
            eventId = event.id, title = displayTitle, poster = poster,
            slug = event.slug, formats = event.formats ?: emptyList(),
            eventInfo = event.eventInfo
        )
        return newLiveSearchResponse(fullTitle, loadData.toJson(), TvType.Live) {
            this.posterUrl = poster
        }
    }

    // ── Load data ─────────────────────────────────────────────────────────────

    data class LiveEventLoadData(
        val eventId: Int,
        val title: String,
        val poster: String,
        val slug: String,
        val formats: List<PlayZLiveEventFormat>,
        val eventInfo: PlayZLiveEventInfo?
    )

    // ── CloudStream interface ─────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        PlayZTVProviderManager.refreshFromFirebase()
        val events = if (customCatLink != null) {
            PlayZTVProviderManager.fetchCustomEvents(customCatLink)
        } else {
            PlayZTVProviderManager.fetchLiveEvents()
        }
        val grouped = events.groupBy { it.eventInfo?.eventCat ?: it.cat ?: "Other" }

        val pages = grouped
            .map { (category, catEvents) ->
                val icon = when (category.lowercase()) {
                    "cricket" -> "🏏"; "football" -> "⚽"; "basketball" -> "🏀"
                    "ice hockey" -> "🏒"; "boxing" -> "🥊"
                    "motorsport" -> "🏎️"; "tennis" -> "🎾"
                    else -> "📺"
                }
                val items = catEvents
                    .sortedWith(
                        compareBy<PlayZLiveEventData> { event ->
                            val status = getEventStatus(event)
                            when {
                                status.contains("🔴") -> 0
                                status.contains("🔜") -> 1
                                status.contains("✅") -> 2
                                else -> 3
                            }
                        }.thenBy { event ->
                            try {
                                parseTime(event.eventInfo?.startTime) ?: Long.MAX_VALUE
                            } catch (_: Exception) {
                                Long.MAX_VALUE
                            }
                        }
                    )
                    .map { toSearchResponse(it) }
                HomePageList("$icon $category", items, isHorizontalImages = true)
            }
            .sortedBy { list ->
                when {
                    list.name.contains("Cricket", ignoreCase = true) -> 0
                    list.name.contains("Football", ignoreCase = true) -> 1
                    list.name.contains("Basketball", ignoreCase = true) -> 2
                    else -> 10
                }
            }

        return newHomePageResponse(pages, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val events = if (customCatLink != null) {
            PlayZTVProviderManager.fetchCustomEvents(customCatLink)
        } else {
            PlayZTVProviderManager.fetchLiveEvents()
        }
        return events
            .filter { event ->
                listOfNotNull(
                    event.title, event.eventInfo?.teamA, event.eventInfo?.teamB,
                    event.eventInfo?.eventName, event.eventInfo?.eventType
                ).joinToString(" ").contains(query, ignoreCase = true)
            }
            .map { toSearchResponse(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val data = parseJson<LiveEventLoadData>(url)
        val info = data.eventInfo
        val plot = buildString {
            info?.let { i ->
                i.eventType?.let { append("📌 $it\n") }
                i.eventName?.let { append("🏆 $it\n") }
                i.startTime?.let {
                    try {
                        val df = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
                        val disp = SimpleDateFormat("MMM dd, yyyy HH:mm z", Locale.US)
                        df.parse(it)?.let { d -> append("🕐 ${disp.format(d)}\n") }
                    } catch (_: Exception) { append("🕐 $it\n") }
                }
            }
            append("\n📡 Available Servers: ${data.formats.size}")
        }
        return newLiveStreamLoadResponse(data.title, url, url) {
            this.posterUrl = data.poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = parseJson<LiveEventLoadData>(data)
        val streams = if (loadData.slug.isNotBlank() && !loadData.slug.startsWith("http", ignoreCase = true)) {
            PlayZTVProviderManager.fetchChannelStreams(loadData.slug)
        } else {
            null
        }
        // Custom channels carry their links directly in the formats list.
        val streamList = when {
            !streams.isNullOrEmpty() -> streams
            loadData.formats.isNotEmpty() -> loadData.formats.mapNotNull { format ->
                format.webLink?.let { PlayZStreamUrl(format.title ?: "Server", it, null, null, null) }
            }
            else -> emptyList()
        }
        if (streamList.isEmpty()) return false
        streamList.forEach { stream ->
            val serverName = stream.name ?: "Server"
            val streamLink = stream.link ?: return@forEach
            val (url, headers) = parseStreamLink(streamLink)
            if (url.isBlank()) return@forEach

            try {
                if (url.contains(".mpd")) {
                    val drmInfo = stream.api?.split(":")
                    if (drmInfo != null && drmInfo.size == 2) {
                        val kidBase64 = hexToBase64(drmInfo[0])
                        val keyBase64 = hexToBase64(drmInfo[1])
                        callback.invoke(
                            newDrmExtractorLink(this.name, serverName, url, INFER_TYPE, CLEARKEY_UUID) {
                                this.quality = Qualities.Unknown.value
                                this.key = keyBase64
                                this.kid = kidBase64
                                if (headers.isNotEmpty()) this.headers = headers
                            }
                        )
                    } else {
                        callback.invoke(
                            newExtractorLink(this.name, serverName, url, ExtractorLinkType.DASH) {
                                this.quality = Qualities.Unknown.value
                                if (headers.isNotEmpty()) this.headers = headers
                            }
                        )
                    }
                } else {
                    val finalHeaders = headers.toMutableMap()
                    if (!finalHeaders.containsKey("User-Agent")) {
                        finalHeaders["User-Agent"] =
                            "Mozilla/5.0 (Linux; Android 10; Pixel 3 XL) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
                    }
                    val type = if (url.contains(".m3u") || url.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE
                    callback.invoke(
                        newExtractorLink(this.name, serverName, url, type) {
                            this.quality = Qualities.Unknown.value
                            if (finalHeaders.isNotEmpty()) this.headers = finalHeaders
                        }
                    )
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
        return true
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Parses `url|Header1=val1&Header2=val2` format. */
    private fun parseStreamLink(link: String): Pair<String, Map<String, String>> {
        val headers = mutableMapOf<String, String>()
        if (!link.contains("|")) return link to headers
        val parts = link.split("|", limit = 2)
        val url = parts[0]
        if (parts.size > 1) {
            parts[1].split("&").forEach { headerPair ->
                val keyValue = headerPair.split("=", limit = 2)
                if (keyValue.size != 2) return@forEach
                val key = keyValue[0].trim()
                val value = keyValue[1].trim()
                val headerName = when (key.lowercase()) {
                    "cookie" -> "Cookie"
                    "origin" -> "Origin"
                    "user-agent" -> "User-Agent"
                    "referer" -> "Referer"
                    else -> key
                }
                headers[headerName] = value
            }
        }
        return url to headers
    }

    private fun hexToBase64(hex: String): String {
        val bytes = hex.replace("-", "").chunked(2)
            .map { it.toInt(16).toByte() }.toByteArray()
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
