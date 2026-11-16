package com.cncverse

import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

private class ExpiringRemoteValue(
    private val ttlMs: Long,
    private val fallbackTtlMs: Long,
    private val minRetryMs: Long
) {
    @Volatile private var value: String? = null
    @Volatile private var expiresAt = 0L
    @Volatile private var lastAttempt = 0L
    private val mutex = Mutex()

    suspend fun get(fetch: suspend () -> String?, fallback: suspend () -> String): String {
        value?.let { if (System.currentTimeMillis() < expiresAt) return it }
        return mutex.withLock {
            val now = System.currentTimeMillis()
            val current = value
            if (current != null && (now < expiresAt || now - lastAttempt < minRetryMs)) {
                return@withLock current
            }
            lastAttempt = now
            val fresh = (try { fetch() } catch (e: Exception) { null })?.takeIf { it.isNotBlank() }
            if (fresh != null) {
                value = fresh
                expiresAt = now + ttlMs
                fresh
            } else {
                val resolved = current ?: fallback()
                value = resolved
                expiresAt = now + fallbackTtlMs
                resolved
            }
        }
    }

    fun invalidate() {
        expiresAt = 0L
    }

    fun reset() {
        expiresAt = 0L
        lastAttempt = 0L
    }
}

// ── Data classes shared by provider/live-event layers ────────────────────────

data class PlayZTVProviderEntry(
    val id: Int,
    val title: String,
    val image: String,
    val catLink: String?
)

data class PlayZTVCategoryWrapper(
    val cat: String   // inner JSON string
)

data class PlayZTVCategoryData(
    val visible: Boolean?,
    val name: String,
    val logo: String?,
    val type: String?,
    val api: String,
    val xorKey: String?
)

data class PlayZTVEventWrapper(
    val event: String   // inner JSON string
)

data class PlayZTVEventData(
    val category: String?,
    val eventName: String?,
    val eventLogo: String?,
    val teamAName: String?,
    val teamBName: String?,
    val teamAFlag: String?,
    val teamBFlag: String?,
    val date: String?,
    val time: String?,
    val end_date: String?,
    val end_time: String?,
    val links: String?,
    val link_names: List<String>?,
    val visible: Boolean?,
    val priority: Int?
)

// ── Live event domain model ───────────────────────────────────────────────────

data class PlayZLiveEventData(
    val id: Int,
    val title: String,
    val image: String?,
    val slug: String,
    val cat: String?,
    val eventInfo: PlayZLiveEventInfo?,
    val publish: Int,
    val formats: List<PlayZLiveEventFormat>?
)

data class PlayZLiveEventInfo(
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

data class PlayZLiveEventFormat(
    val title: String?,
    val webLink: String?
)

// ── Stream URL model returned by /channels/{slug}.txt ────────────────────────

data class PlayZStreamUrl(
    val name: String?,
    val link: String?,
    val scheme: Int?,
    val api: String?,
    val tokenApi: String?
)

// ── Manager singleton ─────────────────────────────────────────────────────────

object PlayZTVProviderManager {

    private const val USER_AGENT = "Dalvik/2.1.0 (Linux; U; Android 10; SM-A505F)"
    private const val CONFIG_TTL_MS = 1800000L
    private const val FALLBACK_TTL_MS = 120000L
    private const val MIN_REFETCH_MS = 60000L

    private val DEFAULT_BASE_URLS = listOf(
        "https://tourniquest.site",
        "https://adsflw.xyz",
        "https://playztv2828.store"
    )

    private val baseUrl = ExpiringRemoteValue(CONFIG_TTL_MS, FALLBACK_TTL_MS, MIN_REFETCH_MS)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun refreshFromFirebase() {
        baseUrl.reset()
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun parseDateTime(date: String?, time: String?): String? {
        if (date == null || time == null) return null
        return try {
            val parts = date.split("/")
            if (parts.size != 3) return null
            val day = parts[0]; val month = parts[1]; val year = parts[2]
            "$year/$month/$day $time +0000"
        } catch (_: Exception) { null }
    }

    private fun firstReachableDefault(): String {
        for (url in DEFAULT_BASE_URLS) {
            try {
                val req = Request.Builder()
                    .url("$url/categories.txt")
                    .header("User-Agent", USER_AGENT)
                    .head()
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.code < 500) return url
                }
            } catch (_: Exception) { /* try next */ }
        }
        return DEFAULT_BASE_URLS.first()
    }

    private suspend fun getBaseUrl(): String =
        baseUrl.get({ PlayZTVFirebaseFetcher.getBaseApiUrl() }, { firstReachableDefault() })

    private suspend fun fetchDecrypted(path: String): String? = withContext(Dispatchers.IO) {
        val safePath = path.removePrefix("/")
        val isAbsolute = safePath.startsWith("http://", true) || safePath.startsWith("https://", true)
        val url = if (isAbsolute) safePath else "${getBaseUrl()}/$safePath"
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()
            val response = client.newCall(request).execute()
            response.use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body.string()
                    if (body.isBlank()) null else PlayZTVCryptoUtils.decryptPlayZTV(body.trim())
                } else {
                    println("PlayZTV: HTTP ${resp.code} fetching $url")
                    if (!isAbsolute) baseUrl.invalidate()
                    null
                }
            }
        } catch (e: Exception) {
            println("PlayZTV: Exception fetching $url – ${e.message}")
            if (!isAbsolute) baseUrl.invalidate()
            null
        }
    }

    private fun isPlaylistLink(isPlaylist: Boolean?, links: String?): Boolean =
        isPlaylist == true ||
            (links?.contains(".m3u", ignoreCase = true) == true && !links.contains(".m3u8", ignoreCase = true))

    /** Expands a single category wrapper into provider entries (possibly multiple for playlists). */
    private suspend fun processCategoryWrapper(wrapper: PlayZTVCategoryWrapper): List<Map<String, Any>> {
        try {
            val cat = parseJson<PlayZTVCategoryData>(wrapper.cat)
            if (cat.visible == false) return emptyList()
            val serverName = cat.name.trim()
            val api = cat.api.trim()
            val catType = cat.type?.trim() ?: "custom"
            val categoryEntries = mutableListOf<Map<String, Any>>()
            var hasExpandedPlaylists = false

            if (catType == "custom" ||
                api.startsWith("channels/", ignoreCase = true) ||
                (api.endsWith(".txt", ignoreCase = true) && !api.startsWith("http", ignoreCase = true))
            ) {
                try {
                    val channelContent = fetchDecrypted(api)
                    if (!channelContent.isNullOrBlank()) {
                        val channelWrappers = parseJson<List<PlayZTVChannelWrapper>>(channelContent)
                        val channels = channelWrappers.mapNotNull { w ->
                            try { parseJson<PlayZTVChannelData>(w.channel) } catch (_: Exception) { null }
                        }
                        val validChannels = channels.filter { it.visible != false && !it.links.isNullOrBlank() }
                        val containsPlaylists = validChannels.any { ch -> isPlaylistLink(ch.is_playlist, ch.links) }
                        if (containsPlaylists) {
                            hasExpandedPlaylists = true
                            validChannels.forEach { ch ->
                                val rawLinks = ch.links?.trim() ?: ""
                                val isPlaylist = isPlaylistLink(ch.is_playlist, rawLinks)
                                categoryEntries.add(
                                    mapOf(
                                        "title" to "${ch.name?.trim() ?: "Channel"} ($serverName)",
                                        "image" to (ch.logo ?: cat.logo ?: ""),
                                        "catLink" to rawLinks,
                                        "type" to if (isPlaylist) "m3u" else "custom",
                                        "server" to serverName
                                    )
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("PlayZTV: Error parsing custom channels for $serverName – ${e.message}")
                }
            } else {
                hasExpandedPlaylists = true
                categoryEntries.add(
                    mapOf(
                        "title" to serverName,
                        "image" to (cat.logo ?: ""),
                        "catLink" to api,
                        "type" to catType,
                        "server" to serverName,
                        "xorKey" to (cat.xorKey ?: "")
                    )
                )
            }

            if (!hasExpandedPlaylists) {
                categoryEntries.add(
                    mapOf(
                        "title" to serverName,
                        "image" to (cat.logo ?: ""),
                        "catLink" to api,
                        "type" to catType,
                        "server" to serverName,
                        "xorKey" to (cat.xorKey ?: "")
                    )
                )
            }
            return categoryEntries
        } catch (e: Exception) {
            println("PlayZTV: Failed parsing category – ${e.message}")
            return emptyList()
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Fetches the provider/category list from `{baseUrl}/categories.txt`.
     * Returns a list of maps compatible with SKTech's plugin registration.
     */
    suspend fun fetchProviders(): List<Map<String, Any>> = withContext(Dispatchers.IO) {
        val providers = mutableListOf<Map<String, Any>>()
        var providerId = 1
        try {
            val decrypted = fetchDecrypted("categories.txt")
            if (!decrypted.isNullOrBlank()) {
                val wrappers = parseJson<List<PlayZTVCategoryWrapper>>(decrypted)
                val results = coroutineScope {
                    wrappers.map { wrapper ->
                        async(Dispatchers.IO) { processCategoryWrapper(wrapper) }
                    }.awaitAll()
                }.flatten()
                results.forEach { item ->
                    providers += item + ("id" to providerId)
                    providerId++
                }
            }
        } catch (e: Exception) {
            println("PlayZTV: fetchProviders exception – ${e.message}")
        }
        providers
    }

    /**
     * Fetches live events from `{baseUrl}/events.txt`.
     */
    suspend fun fetchLiveEvents(): List<PlayZLiveEventData> = withContext(Dispatchers.IO) {
        try {
            val decrypted = fetchDecrypted("events.txt")
            if (!decrypted.isNullOrBlank()) {
                val wrappers = parseJson<List<PlayZTVEventWrapper>>(decrypted)
                val events = wrappers.mapIndexedNotNull { index, wrapper ->
                    try {
                        val ev = parseJson<PlayZTVEventData>(wrapper.event)
                        PlayZLiveEventData(
                            id = index + 1,
                            title = ev.eventName ?: "Unknown Event",
                            image = ev.eventLogo,
                            slug = ev.links?.substringBeforeLast(".") ?: "",
                            cat = ev.category,
                            eventInfo = PlayZLiveEventInfo(
                                teamA = ev.teamAName,
                                teamB = ev.teamBName,
                                teamAFlag = ev.teamAFlag,
                                teamBFlag = ev.teamBFlag,
                                eventCat = ev.category,
                                eventName = ev.eventName,
                                eventLogo = ev.eventLogo,
                                isHot = null,
                                eventType = ev.category,
                                startTime = parseDateTime(ev.date, ev.time),
                                endTime = parseDateTime(ev.end_date, ev.end_time)
                            ),
                            publish = if (ev.visible == true) 1 else 0,
                            formats = ev.link_names?.map { name ->
                                PlayZLiveEventFormat(title = name, webLink = ev.links)
                            } ?: emptyList()
                        )
                    } catch (e: Exception) {
                        println("PlayZTV: Failed to parse event at $index – ${e.message}")
                        null
                    }
                }
                return@withContext events.filter { it.publish == 1 }
            }
        } catch (e: Exception) {
            println("PlayZTV: fetchLiveEvents exception – ${e.message}")
        }
        emptyList()
    }

    /**
     * Fetches stream list from `{baseUrl}/{slug}.txt`.
     * Returns a list of [PlayZStreamUrl] or null.
     */
    suspend fun fetchCustomEvents(catLink: String): List<PlayZLiveEventData> {
        return withContext(Dispatchers.IO) {
            try {
                val decrypted = fetchDecrypted(catLink)
                if (!decrypted.isNullOrBlank()) {
                    val wrappers = parseJson<List<PlayZTVChannelWrapper>>(decrypted)
                    val events = wrappers.mapIndexedNotNull { index, wrapper ->
                        try {
                            val channelData = parseJson<PlayZTVChannelData>(wrapper.channel)
                            if (channelData.visible == false) return@mapIndexedNotNull null
                            val rawLink = channelData.links
                            if (rawLink.isNullOrBlank()) return@mapIndexedNotNull null
                            PlayZLiveEventData(
                                id = index + 1,
                                title = channelData.name ?: "Unknown Channel",
                                image = channelData.logo,
                                slug = if (rawLink.startsWith("http", ignoreCase = true)) {
                                    rawLink
                                } else {
                                    rawLink.substringBeforeLast(".")
                                },
                                cat = "Custom",
                                eventInfo = PlayZLiveEventInfo(
                                    null, null, null, null, null,
                                    channelData.name, channelData.logo, "0",
                                    null, null, null
                                ),
                                publish = 1,
                                formats = rawLink.split(",").mapIndexed { i, link ->
                                    PlayZLiveEventFormat(
                                        title = channelData.link_names?.getOrNull(i)?.trim() ?: "Link ${i + 1}",
                                        webLink = link.trim()
                                    )
                                }
                            )
                        } catch (e: Exception) {
                            println("PlayZTV: Failed to parse custom event at $index – ${e.message}")
                            null
                        }
                    }
                    return@withContext events.filter { it.publish == 1 }
                }
            } catch (e: Exception) {
                println("PlayZTV: fetchCustomEvents exception – ${e.message}")
            }
            emptyList()
        }
    }

    suspend fun fetchChannelStreams(slug: String): List<PlayZStreamUrl>? = withContext(Dispatchers.IO) {
        try {
            val decrypted = fetchDecrypted("$slug.txt")
            if (!decrypted.isNullOrBlank()) {
                return@withContext parseJson<List<PlayZStreamUrl>>(decrypted)
            }
        } catch (e: Exception) {
            println("PlayZTV: fetchChannelStreams exception for $slug – ${e.message}")
        }
        null
    }
}
