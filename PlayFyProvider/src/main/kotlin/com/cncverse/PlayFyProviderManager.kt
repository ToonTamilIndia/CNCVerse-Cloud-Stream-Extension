package com.cncverse

import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

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

data class PlayFyChannelStreamResponse(
    val streamUrls: List<PlayFyStreamEntry>?,
    val related: List<LiveEventData>?,
    val prevChannel: String?,
    val nextChannel: String?
)

object PlayFyProviderManager {

    private const val DEFAULT_BASE_URL = "https://sohaidoegeve2.shop/"

    @Volatile private var cachedBaseUrl: String? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val baseHeaders = mapOf(
        "User-Agent" to "okhttp/4.9.2",
        "Accept" to "*/*"
    )

    suspend fun getBaseUrl(): String {
        cachedBaseUrl?.let { return it }
        val firebaseUrl = PlayFyFirebaseConfigFetcher.getBaseApiUrl()
        cachedBaseUrl = if (!firebaseUrl.isNullOrBlank()) {
            if (firebaseUrl.endsWith("/")) firebaseUrl else "$firebaseUrl/"
        } else {
            DEFAULT_BASE_URL
        }
        return cachedBaseUrl!!
    }

    fun invalidateCache() {
        cachedBaseUrl = null
    }

    private suspend fun fetchDecrypted(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).apply {
                baseHeaders.forEach { (k, v) -> header(k, v) }
            }.build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                println("PlayFy: HTTP ${response.code} -> $url")
                return@withContext null
            }
            val body = response.body.string()
            if (body.isBlank()) return@withContext null
            val decrypted = PlayFyCryptoUtils.decrypt(body)
            if (decrypted.isNullOrBlank()) {
                println("PlayFy: Decryption failed for $url")
                return@withContext null
            }
            decrypted
        } catch (e: Exception) {
            println("PlayFy: Exception fetching $url - ${e.message}")
            null
        }
    }

    suspend fun fetchProviders(): List<Map<String, Any>> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = getBaseUrl()
            val url = "${baseUrl}categories.txt"
            println("PlayFy: Fetching categories from $url")

            val json = fetchDecrypted(url) ?: return@withContext emptyList()

            val wrappers = parseJson<List<PlayFyCatFilter>>(json)

            wrappers.mapIndexedNotNull { index, wrapper ->
                if (wrapper.cat.isBlank()) return@mapIndexedNotNull null
                val cat = try {
                    parseJson<PlayFyCategoryData>(wrapper.cat)
                } catch (e: Exception) {
                    println("PlayFy: Failed to parse category at index $index: ${e.message}")
                    return@mapIndexedNotNull null
                }
                if (cat.visible == false) return@mapIndexedNotNull null
                val api = cat.api?.trim() ?: return@mapIndexedNotNull null

                mapOf<String, Any>(
                    "id" to (index + 1),
                    "title" to cat.name,
                    "image" to (cat.logo ?: ""),
                    "catLink" to api,
                    "type" to (cat.type ?: "custom")
                )
            }
        } catch (e: Exception) {
            println("PlayFy: fetchProviders exception: ${e.message}")
            emptyList()
        }
    }

    suspend fun fetchLiveEvents(): List<LiveEventData> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = getBaseUrl()
            val slug = "events.txt"
            val url = "$baseUrl$slug"
            println("PlayFy: Fetching events from $url")

            val json = fetchDecrypted(url) ?: return@withContext emptyList()

            val wrappers = parseJson<List<PlayFyEventWrapper>>(json)

            wrappers.mapIndexedNotNull { index, wrapper ->
                if (wrapper.event.isBlank()) return@mapIndexedNotNull null
                val ev = try {
                    parseJson<PlayFyEventInfo>(wrapper.event)
                } catch (e: Exception) {
                    println("PlayFy: Failed to parse event at index $index: ${e.message}")
                    return@mapIndexedNotNull null
                }
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
                        endTime = ev.endTimeString()
                    ),
                    formats = ev.link_names?.map { linkName ->
                        LiveEventFormat(
                            title = linkName["name"],
                            webLink = null
                        )
                    } ?: emptyList()
                )
            }
        } catch (e: Exception) {
            println("PlayFy: fetchLiveEvents exception: ${e.message}")
            emptyList()
        }
    }

    suspend fun fetchCustomEvents(catLink: String): List<LiveEventData> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = getBaseUrl()
            val url = if (catLink.startsWith("http")) catLink else "$baseUrl$catLink"
            println("PlayFy: Fetching custom events from $url")

            val json = fetchDecrypted(url) ?: return@withContext emptyList()

            val wrappers = parseJson<List<PlayFyChannelListResponse>>(json)

            wrappers.mapIndexedNotNull { index, wrapper ->
                if (wrapper.channel.isNotBlank()) {
                    try {
                        val channelData = parseJson<PlayFyChannel>(wrapper.channel)
                        if (channelData.visible == false) return@mapIndexedNotNull null
                        val links = channelData.links?.trim()
                        if (links.isNullOrBlank()) return@mapIndexedNotNull null
                        val slug = links.removeSuffix(".txt")
                        LiveEventData(
                            id = index + 1,
                            title = channelData.name ?: "Unknown Channel",
                            image = channelData.logo,
                            slug = slug,
                            cat = "Custom",
                            publish = 1,
                            eventInfo = LiveEventInfo(
                                teamA = channelData.name,
                                teamB = null,
                                teamAFlag = channelData.logo,
                                teamBFlag = null,
                                eventCat = "Custom",
                                eventName = channelData.name,
                                eventLogo = channelData.logo,
                                isHot = null,
                                eventType = null,
                                startTime = null,
                                endTime = null
                            ),
                            formats = listOf(LiveEventFormat(title = channelData.name, webLink = null))
                        )
                    } catch (e: Exception) {
                        println("PlayFy: Failed to parse channel at index $index: ${e.message}")
                        return@mapIndexedNotNull null
                    }
                } else if (wrapper.highlight.isNotBlank()) {
                    try {
                        val ev = parseJson<PlayFyEventInfo>(wrapper.highlight)
                        if (ev.visible == false) return@mapIndexedNotNull null
                        if (ev.streamSlug.isBlank()) return@mapIndexedNotNull null
                        val formats = ev.link_names?.mapNotNull { linkName ->
                            linkName["name"]?.let { LiveEventFormat(title = it, webLink = null) }
                        } ?: listOf(LiveEventFormat(title = "Link 1", webLink = null))
                        LiveEventData(
                            id = index + 1,
                            title = ev.displayName.ifBlank { ev.eventName ?: "Event $index" },
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
                                endTime = ev.endTimeString()
                            ),
                            formats = formats
                        )
                    } catch (e: Exception) {
                        println("PlayFy: Failed to parse highlight at index $index: ${e.message}")
                        return@mapIndexedNotNull null
                    }
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            println("PlayFy: fetchCustomEvents exception: ${e.message}")
            emptyList()
        }
    }

    suspend fun fetchStreamData(slug: String): String? {
        val baseUrl = getBaseUrl()
        return fetchDecrypted("$baseUrl$slug.txt")
            ?: fetchDecrypted("$baseUrl$slug")
    }

    suspend fun getTelegramUrl(): String {
        val firebaseTelegram = PlayFyFirebaseConfigFetcher.getTelegramUrl()
        if (!firebaseTelegram.isNullOrBlank()) return firebaseTelegram
        return "https://t.me/PlayFyofficial"
    }
}
