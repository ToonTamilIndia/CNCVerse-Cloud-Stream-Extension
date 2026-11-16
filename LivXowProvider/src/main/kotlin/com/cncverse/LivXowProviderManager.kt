package com.cncverse

import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

// ── Data classes ──────────────────────────────────────────────────────────────

/**
 * Outer wrapper in events.txt — the "event" field is a double-encoded JSON string.
 * Structure: [{"event":"{\"visible\":true,...}"}, ...]
 */
data class LivXowEventWrapper(val event: String = "")

/**
 * A single live event entry (parsed from the inner JSON string of [LivXowEventWrapper]).
 *
 * Field names match the APK JSON exactly.
 */
data class LivXowEvent(
    // Team / match info
    val teamAName: String?   = null,
    val teamBName: String?   = null,
    val teamAFlag: String?   = null,
    val teamBFlag: String?   = null,
    // Event meta
    val eventName: String?   = null,
    val eventLogo: String?   = null,
    val category: String?    = null,
    // Date/time — stored as separate strings: "DD/MM/YYYY" and "HH:mm:ss"
    val date: String?        = null,
    val time: String?        = null,
    val end_date: String?    = null,
    val end_time: String?    = null,
    // Stream path — full relative path including .txt, e.g. "pro/VDIw...MDA.txt"
    val links: String?       = null,
    // Visibility
    val visible: Boolean?    = null,
    val priority: Int?       = null,
    // Links meta — either plain names or {"name": ...} objects
    val link_names: List<Any>? = null
) {
    /** Human-readable display name: "TeamA vs TeamB" or eventName. */
    val displayName: String get() {
        val a = teamAName?.trim()
        val b = teamBName?.trim()
        return when {
            !a.isNullOrBlank() && !b.isNullOrBlank() && a != b -> "$a vs $b"
            !a.isNullOrBlank() -> a
            !eventName.isNullOrBlank() -> eventName
            else -> ""
        }
    }
    val categoryName: String get() = category?.trim() ?: "Sports"
    val thumbUrl: String? get() = eventLogo
    /**
     * Slug to pass to fetchStreamData — strip .txt so that fetchStreamData
     * can append it and build the correct URL.
     * e.g. "pro/VDIw...MDA.txt" → "pro/VDIw...MDA"
     */
    val streamSlug: String get() = links?.removeSuffix(".txt") ?: ""
    /** Converts DD/MM/YYYY + HH:mm:ss to the format expected by SimpleDateFormat in LiveEventsProvider. */
    fun startTimeString(): String? = toIsoString(date, time)
    fun endTimeString(): String?   = toIsoString(end_date, end_time)

    /** Server names from [link_names] (plain strings or {"name": ...} objects). */
    fun linkFormats(): List<LiveEventFormat> = link_names?.mapNotNull { item ->
        val name = when (item) {
            is Map<*, *> -> item["name"] as? String
            is String -> item
            else -> null
        }
        name?.let { LiveEventFormat(title = it, webLink = null) }
    } ?: emptyList()

    companion object {
        /** Converts "DD/MM/YYYY" + "HH:mm:ss" → "YYYY/MM/DD HH:mm:ss +0000" */
        fun toIsoString(date: String?, time: String?): String? {
            if (date == null || time == null) return null
            val parts = date.split("/")
            if (parts.size != 3) return null
            val (day, month, year) = parts
            return "$year/$month/$day $time +0000"
        }
    }
}

/** Shared data model for SKTech-style live events (used by LiveEventsProvider). */
data class LiveEventData(
    val id: Int,
    val title: String,
    val image: String?,
    val slug: String,
    val cat: String?,
    val eventInfo: LiveEventInfo?,
    val publish: Int,
    val formats: List<LiveEventFormat>?
)

data class LiveEventInfo(
    val teamA: String?,
    val teamB: String?,
    val teamAFlag: String?,
    val teamBFlag: String?,
    val eventCat: String?,
    val eventName: String?,
    val eventLogo: String?,
    val isHot: String?,
    val eventType: String?,
    val startTime: String?,
    val endTime: String?
)

data class LiveEventFormat(
    val title: String?,
    val webLink: String?
)

/**
 * Outer wrapper in categories.txt — the "cat" field is a double-encoded JSON string.
 * Structure: [{"cat":"{\"visible\":true,...}"}, ...]
 */
data class LivXowCategoryWrapper(val cat: String = "")

/**
 * Inner category object (parsed from the "cat" string in [LivXowCategoryWrapper]).
 *
 * Field names match the APK JSON exactly:
 *   type = "custom" → api is a relative path  e.g. "channels/U1BPUlRTMTc3Mjc2NTI3ODI3Mw.txt"
 *   type = "m3u"    → api is a full M3U URL
 */
data class LivXowCategoryData(
    val visible: Boolean? = null,
    val name: String      = "",
    val logo: String?     = null,
    val type: String?     = null,
    val api: String?      = null
)

/**
 * Outer wrapper in custom category files — either a "channel" or a "highlight"
 * field, each a double-encoded JSON string.
 */
data class LivXowChannelWrapper(
    val channel: String = "",
    val highlight: String = ""
)

/** Inner channel object (parsed from the "channel" string in [LivXowChannelWrapper]). */
data class LivXowChannelData(
    val name: String?,
    val logo: String?,
    val visible: Boolean?,
    val links: String?,
    val link_names: List<String>?,
    val is_playlist: Boolean?
)

// ── ProviderManager ───────────────────────────────────────────────────────────

/**
 * LivXow Provider Manager
 *
 * Fetches and decrypts all data from the LivXow API.
 * The base URL is resolved dynamically from Firebase Remote Config via
 * [LivXowFirebaseFetcher]; falls back to the hardcoded URL from the APK.
 *
 * API flow:
 *  1. GET <baseUrl>categories.txt   → decrypt → List<[LivXowCategoryWrapper]> (Categories)
 *  2. GET <baseUrl>events.txt       → decrypt → List<[LivXowEvent]>    (Live Events)
 */
object LivXowProviderManager {

    /** Hardcoded fallback URL from the APK — replaced at runtime via Firebase. */
    private const val DEFAULT_BASE_URL = "https://sohaidoegeve2.shop/"

    private val baseUrlValue = ExpiringRemoteValue()

    fun refreshFromFirebase() {
        baseUrlValue.reset()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(StaleDomainInterceptor(baseUrlValue))
        .build()

    private val baseHeaders = mapOf(
        "User-Agent" to "okhttp/4.9.2",
        "Accept"     to "*/*"
    )

    // ── URL resolution ────────────────────────────────────────────────────────

    /**
     * Returns the effective API base URL (with trailing slash), cached with expiry.
     * Priority: Firebase Remote Config → hardcoded APK fallback.
     */
    suspend fun getBaseUrl(): String = baseUrlValue.get(
        fetch = { LivXowFirebaseFetcher.getBaseApiUrl()?.let { if (it.endsWith("/")) it else "$it/" } },
        fallback = { DEFAULT_BASE_URL }
    )

    /** Invalidates cached URL — forces a fresh fetch on next call. */
    fun invalidateCache() {
        baseUrlValue.reset()
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    /**
     * GETs [url], decrypts the response via [LivXowCryptoUtils], and returns
     * the plain JSON string. Returns null on any failure.
     */
    private suspend fun fetchDecrypted(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).apply {
                baseHeaders.forEach { (k, v) -> header(k, v) }
            }.build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                println("LivXow: HTTP ${response.code} → $url")
                return@withContext null
            }
            val body = response.body.string()
            if (body.isBlank()) return@withContext null
            val decrypted = LivXowCryptoUtils.decrypt(body)
            if (decrypted.isNullOrBlank()) {
                println("LivXow: Decryption failed for $url")
                return@withContext null
            }
            decrypted
        } catch (e: Exception) {
            println("LivXow: Exception fetching $url — ${e.message}")
            null
        }
    }


    /**
     * Fetches providers formatted as maps for use by the plugin's category selector.
     * Mirrors the SKTech ProviderManager.fetchProviders() contract.
     *
     * Each map contains: { "id", "title", "image", "catLink" }
     */
    suspend fun fetchProviders(): List<Map<String, Any>> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = getBaseUrl()
            val url     = "${baseUrl}categories.txt"
            println("LivXow: Fetching categories from $url")

            val json = fetchDecrypted(url) ?: return@withContext emptyList()

            // categories.txt is [{"cat": "<double-encoded JSON>"}, ...]
            val wrappers = parseJson<List<LivXowCategoryWrapper>>(json)

            wrappers.mapIndexedNotNull { index, wrapper ->
                if (wrapper.cat.isBlank()) return@mapIndexedNotNull null
                val cat = try {
                    parseJson<LivXowCategoryData>(wrapper.cat)
                } catch (e: Exception) {
                    println("LivXow: Failed to parse category at index $index: ${e.message}")
                    return@mapIndexedNotNull null
                }
                // Skip hidden categories and ones with no API path
                if (cat.visible == false) return@mapIndexedNotNull null
                val api = cat.api?.trim() ?: return@mapIndexedNotNull null

                mapOf<String, Any>(
                    "id"      to (index + 1),
                    "title"   to cat.name,
                    "image"   to (cat.logo ?: ""),
                    "catLink" to api,
                    "type"    to (cat.type ?: "custom")
                )
            }
        } catch (e: Exception) {
            println("LivXow: fetchProviders exception: ${e.message}")
            emptyList()
        }
    }

    // ── Live events ───────────────────────────────────────────────────────────

    /**
     * Fetches live events from `<baseUrl>events.txt`.
     *
     * @return List of [LiveEventData] (only visible events), or empty list on failure.
     */
    suspend fun fetchLiveEvents(): List<LiveEventData> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = getBaseUrl()
            val slug    = "events.txt"
            val url     = "$baseUrl$slug"
            println("LivXow: Fetching events from $url")

            val json = fetchDecrypted(url) ?: return@withContext emptyList()

            // events.txt is [{"event": "<double-encoded JSON>"}, ...]
            val wrappers = parseJson<List<LivXowEventWrapper>>(json)

            wrappers.mapIndexedNotNull { index, wrapper ->
                if (wrapper.event.isBlank()) return@mapIndexedNotNull null
                val ev = try {
                    parseJson<LivXowEvent>(wrapper.event)
                } catch (e: Exception) {
                    println("LivXow: Failed to parse inner event at index $index: ${e.message}")
                    return@mapIndexedNotNull null
                }
                // Skip hidden events
                if (ev.visible == false) return@mapIndexedNotNull null
                // Skip events with no stream slug
                if (ev.streamSlug.isBlank()) return@mapIndexedNotNull null

                LiveEventData(
                    id      = index + 1,
                    title   = ev.displayName,
                    image   = ev.thumbUrl,
                    slug    = ev.streamSlug,
                    cat     = ev.categoryName,
                    publish = 1,
                    eventInfo = LiveEventInfo(
                        teamA     = ev.teamAName,
                        teamB     = ev.teamBName,
                        teamAFlag = ev.teamAFlag,
                        teamBFlag = ev.teamBFlag,
                        eventCat  = ev.categoryName,
                        eventName = ev.eventName ?: ev.displayName,
                        eventLogo = ev.thumbUrl,
                        isHot     = null,
                        eventType = ev.categoryName,
                        startTime = ev.startTimeString(),
                        endTime   = ev.endTimeString()
                    ),
                    formats = ev.linkFormats()
                )
            }
        } catch (e: Exception) {
            println("LivXow: fetchLiveEvents exception: ${e.message}")
            emptyList()
        }
    }

    // ── Custom events (channels / categories) ─────────────────────────────────

    /**
     * Fetches custom events for a given category/channel link.
     * [catLink] is a full URL or relative path (e.g. "channels/XXX.txt").
     * Returns list of LiveEventData, or empty list on failure.
     */
    suspend fun fetchCustomEvents(catLink: String): List<LiveEventData> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = getBaseUrl()
            val url = if (catLink.startsWith("http")) catLink else "$baseUrl$catLink"
            println("LivXow: Fetching custom events from $url")

            val json = fetchDecrypted(url) ?: return@withContext emptyList()

            val wrappers = parseJson<List<LivXowChannelWrapper>>(json)

            wrappers.mapIndexedNotNull { index, wrapper ->
                if (wrapper.channel.isNotBlank()) {
                    try {
                        val channelData = parseJson<LivXowChannelData>(wrapper.channel)
                        if (channelData.visible == false) return@mapIndexedNotNull null
                        if (channelData.links.isNullOrBlank()) return@mapIndexedNotNull null
                        LiveEventData(
                            id = index + 1,
                            title = channelData.name ?: "Unknown Channel",
                            image = channelData.logo,
                            slug = channelData.links.removeSuffix(".txt"),
                            cat = "Custom",
                            publish = 1,
                            eventInfo = LiveEventInfo(
                                null, null, null, null, null,
                                channelData.name, channelData.logo, "0",
                                null, null, null
                            ),
                            formats = channelData.links.split(", ").mapIndexed { i, link ->
                                LiveEventFormat(
                                    title = channelData.link_names?.getOrNull(i) ?: "Link ${i + 1}",
                                    webLink = link
                                )
                            }
                        )
                    } catch (e: Exception) {
                        println("LivXow: Failed to parse inner custom event at index $index: ${e.message}")
                        return@mapIndexedNotNull null
                    }
                } else if (wrapper.highlight.isNotBlank()) {
                    try {
                        val ev = parseJson<LivXowEvent>(wrapper.highlight)
                        if (ev.visible == false) return@mapIndexedNotNull null
                        if (ev.streamSlug.isBlank()) return@mapIndexedNotNull null
                        LiveEventData(
                            id = index + 1,
                            title = ev.displayName,
                            image = ev.thumbUrl,
                            slug = ev.streamSlug,
                            cat = ev.categoryName,
                            publish = 1,
                            eventInfo = LiveEventInfo(
                                teamA = ev.teamAName,
                                teamB = ev.teamBName,
                                teamAFlag = ev.teamAFlag,
                                teamBFlag = ev.teamBFlag,
                                eventCat = ev.categoryName,
                                eventName = ev.eventName ?: ev.displayName,
                                eventLogo = ev.thumbUrl,
                                isHot = null,
                                eventType = ev.categoryName,
                                startTime = ev.startTimeString(),
                                // Highlights are past events — always shown as ended
                                endTime = "1970/01/01 00:00:00 +0000"
                            ),
                            formats = ev.linkFormats()
                        )
                    } catch (e: Exception) {
                        println("LivXow: Failed to parse inner highlight at index $index: ${e.message}")
                        return@mapIndexedNotNull null
                    }
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            println("LivXow: fetchCustomEvents exception: ${e.message}")
            emptyList()
        }
    }

    // ── Stream URL fetching ───────────────────────────────────────────────────

    /**
     * Fetches and decrypts stream URLs for a given [slug].
     * Endpoint: `<baseUrl><slug>.txt` (or `<baseUrl><slug>`)
     *
     * Returns the raw decrypted JSON string so callers can parse it to their own
     * data model (could be an array of stream objects or a plain URL string).
     */
    /**
     * Fetches and decrypts stream URLs for a given [slug].
     *
     * [slug] must NOT include the .txt extension — this function appends it.
     * e.g. slug = "pro/VDIwIEludGVybmF0aW9uYWwtRU5HLXZzLUlORDE3ODI0OTg4NjQ2MDA"
     *   → fetches "<baseUrl>pro/VDIw...MDA.txt"
     */
    suspend fun fetchStreamData(slug: String): String? {
        val baseUrl = getBaseUrl()
        return fetchDecrypted("$baseUrl$slug.txt")
            ?: fetchDecrypted("$baseUrl$slug")
    }
}
