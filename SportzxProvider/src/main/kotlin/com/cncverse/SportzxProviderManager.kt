package com.cncverse

import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

// ── Data classes (matching decrypted events.txt / categories.txt) ─────────────

/**
 * A single category entry from the decrypted categories.txt.
 *
 * Decrypted structure (flat JSON array, NOT double-encoded):
 * [
 *   { "id": "2", "title": "Sports", "image": "...", "catLink": "Sports" },
 *   ...
 * ]
 */
data class SportzxCategoryData(
    val id: String?,
    val title: String,
    val image: String?,
    val catLink: String?
)

/**
 * A single event from the decrypted events.txt.
 *
 * Decrypted structure (flat JSON array, NOT double-encoded):
 * [
 *   {
 *     "id": 50002,
 *     "title": "Formula 1",
 *     "image": "o",
 *     "cat": "F1",
 *     "eventInfo": { "teamA": "...", ... , "startTime": "2026/07/03 11:30:00 +0000" },
 *     "publish": "1",
 *     "formatsNew": [ { "title": "SKY F1 FHD", "logo": "..." }, ... ]
 *   },
 *   ...
 * ]
 */
data class SportzxEventData(
    val id: Int?,
    val title: String?,
    val image: String?,
    val cat: String?,
    val eventInfo: SportzxEventInfo?,
    val publish: String?,            // "1" = published
    val formatsNew: List<SportzxFormat>?
)

data class SportzxEventInfo(
    val teamA: String?,
    val teamB: String?,
    val teamAFlag: String?,
    val teamBFlag: String?,
    val eventName: String?,
    val eventType: String?,
    val eventBanner: String?,
    val eventLogo: String?,
    val isHot: String?,
    val startTime: String?,          // "2026/07/03 11:30:00 +0000"
    val endTime: String?
)

data class SportzxFormat(
    val title: String?,
    val logo: String?
)

/**
 * A single stream entry from the decrypted /channels/{id}.json.
 *
 * Decrypted structure (flat JSON array):
 * [
 *   {
 *     "title": "SKY F1 FHD",
 *     "link": "https://…stream.m3u8|Referer=https://example.com",
 *     "api": "kid:key"          // optional DRM clearkey "keyId:keyValue"
 *   },
 *   ...
 * ]
 */
data class SportzxStreamEntry(
    val title: String?,
    val link: String?,
    val api: String?
)

// ── Shared LiveEvent models (re-used by SportzxLiveEventsProvider) ─────────────

data class SportzxLiveEventData(
    val id: Int,
    val title: String,
    val image: String?,
    val eventId: Int,              // numeric event ID used to build channel URL
    val cat: String?,
    val eventInfo: SportzxLiveEventInfo?,
    val publish: Int,
    val formats: List<SportzxLiveEventFormat>?
)

data class SportzxLiveEventInfo(
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

data class SportzxLiveEventFormat(
    val title: String?,
    val logo: String?
)

object SportzxProviderManager {

    @Volatile private var cachedBaseUrl: String? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val baseHeaders = mapOf(
        "User-Agent" to "Dalvik/2.1.0 (Linux; Android 13)"
    )

    // ── URL resolution ────────────────────────────────────────────────────────

    /**
     * Returns the effective API base URL (no trailing slash).
     * Priority: Firebase Remote Config → cached value.
     */
    suspend fun getBaseUrl(): String {
        cachedBaseUrl?.let { return it }
        val firebaseUrl = SportzxFirebaseFetcher.getBaseApiUrl()
        cachedBaseUrl = if (!firebaseUrl.isNullOrBlank()) firebaseUrl.trimEnd('/')
                        else ""
        return cachedBaseUrl!!
    }

    fun invalidateCache() {
        cachedBaseUrl = null
    }

    // ── Private fetch+decrypt helper ──────────────────────────────────────────

    private suspend fun fetchDecrypted(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).apply {
                baseHeaders.forEach { (k, v) -> header(k, v) }
            }.build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                println("Sportzx: HTTP ${response.code} → $url")
                return@withContext null
            }
            val bodyStr = response.body.string()
            if (bodyStr.isBlank()) return@withContext null
            try {
                val envelope = parseJson<Map<String, String>>(bodyStr)
                val encrypted = envelope["data"] ?: run {
                    println("Sportzx: No 'data' field in response from $url")
                    return@withContext null
                }
                val decrypted = SportzxCryptoUtils.decrypt(encrypted)
                if (decrypted.isNullOrBlank()) {
                    println("Sportzx: Decryption failed for $url")
                    return@withContext null
                }
                decrypted
            } catch (e: Exception) {
                println("Sportzx: Failed to parse JSON envelope from $url — ${e.message}")
                null
            }
        } catch (e: Exception) {
            println("Sportzx: Exception fetching $url — ${e.message}")
            null
        }
    }

    // ── fetchProviders → /cats.json → List<Map<String,Any>> ──────────────────

    suspend fun fetchProviders(): List<Map<String, Any>> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = getBaseUrl()
            if (baseUrl.isBlank()) return@withContext emptyList()
            val url = "$baseUrl/cats.json"
            println("Sportzx: Fetching categories from $url")
            val json = fetchDecrypted(url) ?: return@withContext emptyList()
            try {
                val categories = parseJson<List<SportzxCategoryData>>(json)
                categories.mapIndexedNotNull { index, cat ->
                    val catLink = cat.catLink?.trim()
                    if (catLink.isNullOrBlank()) return@mapIndexedNotNull null
                    mapOf(
                        "id" to (cat.id?.toIntOrNull() ?: (index + 1)),
                        "title" to cat.title,
                        "image" to (cat.image ?: ""),
                        "catLink" to catLink
                    )
                }
            } catch (e: Exception) {
                println("Sportzx: Failed to parse categories — ${e.message}")
                emptyList()
            }
        } catch (e: Exception) {
            println("Sportzx: fetchProviders exception — ${e.message}")
            emptyList()
        }
    }

    // ── fetchLiveEvents → /events.json (or custom path) → List<SportzxLiveEventData> ──

    suspend fun fetchLiveEvents(path: String = "events.json"): List<SportzxLiveEventData> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = getBaseUrl()
            if (baseUrl.isBlank()) return@withContext emptyList()
            val url = "$baseUrl/$path"
            println("Sportzx: Fetching events from $url")
            val json = fetchDecrypted(url) ?: return@withContext emptyList()
            try {
                val events = parseJson<List<SportzxEventData>>(json)
                events.mapIndexedNotNull { index, ev ->
                    if (ev.publish == "1") return@mapIndexedNotNull null
                    val eventId = ev.id ?: return@mapIndexedNotNull null
                    val title = ev.title ?: "Unknown Event"
                    val image = if (ev.image.isNullOrBlank() || ev.image == "o") null else ev.image
                    val cat = ev.cat
                    val info = ev.eventInfo
                    val endTime = if (path.contains("highlights", ignoreCase = true)) "1970/01/01 00:00:00 +0000" else info?.endTime
                    val liveEventInfo = SportzxLiveEventInfo(
                        teamA = info?.teamA,
                        teamB = info?.teamB,
                        teamAFlag = info?.teamAFlag,
                        teamBFlag = info?.teamBFlag,
                        eventCat = cat,
                        eventName = info?.eventName ?: ev.title,
                        eventLogo = info?.eventLogo,
                        isHot = info?.isHot,
                        eventType = if (info == null || info.eventType.isNullOrBlank() || info.eventType == "null") null else info.eventType,
                        startTime = info?.startTime,
                        endTime = endTime
                    )
                    val formats = ev.formatsNew?.map { fmt ->
                        SportzxLiveEventFormat(
                            title = fmt.title,
                            logo = if (fmt.logo.isNullOrBlank()) null else fmt.logo
                        )
                    } ?: emptyList()
                    SportzxLiveEventData(
                        id = index + 1,
                        title = title,
                        image = image,
                        eventId = eventId,
                        cat = cat,
                        eventInfo = liveEventInfo,
                        publish = 1,
                        formats = formats
                    )
                }
            } catch (e: Exception) {
                println("Sportzx: Failed to parse events — ${e.message}")
                emptyList()
            }
        } catch (e: Exception) {
            println("Sportzx: fetchLiveEvents exception — ${e.message}")
            emptyList()
        }
    }

    // ── fetchStreamData → /channels/{eventId}.json (fallback {eventId}e.json) ──

    suspend fun fetchStreamData(eventId: Int): String? = fetchStreamData(eventId.toString())

    suspend fun fetchStreamData(eventId: String): String? = withContext(Dispatchers.IO) {
        try {
            val baseUrl = getBaseUrl()
            if (baseUrl.isBlank()) return@withContext null
            val primary = fetchDecrypted("$baseUrl/channels/$eventId.json")
            if (primary != null) return@withContext primary
            println("Sportzx: Primary channel URL failed, trying fallback ${eventId}e.json")
            fetchDecrypted("$baseUrl/channels/${eventId}e.json")
        } catch (e: Exception) {
            println("Sportzx: fetchStreamData exception — ${e.message}")
            null
        }
    }

    // ── fetchVODCategory → /cats/{catLink}.json ──────────────────────────────

    suspend fun fetchVODCategory(catLink: String): List<SportzxVODData> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = getBaseUrl()
            if (baseUrl.isBlank()) return@withContext emptyList()
            val url = "$baseUrl/cats/${catLink.lowercase()}.json"
            println("Sportzx: Fetching VOD category from $url")
            val json = fetchDecrypted(url) ?: return@withContext emptyList()
            try {
                val items = parseJson<List<SportzxVODData>>(json)
                items.filter { it.publish == "1" && !it.id.isNullOrBlank() }
            } catch (e: Exception) {
                println("Sportzx: Failed to parse VOD category $catLink — ${e.message}")
                emptyList()
            }
        } catch (e: Exception) {
            println("Sportzx: fetchVODCategory exception — ${e.message}")
            emptyList()
        }
    }
}
