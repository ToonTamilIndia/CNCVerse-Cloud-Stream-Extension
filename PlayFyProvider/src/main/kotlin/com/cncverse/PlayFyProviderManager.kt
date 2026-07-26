package com.cncverse

import com.lagradost.cloudstream3.utils.AppUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
    private const val USER_AGENT = "PLAYFy/1.7 (Android)"

    @Volatile private var cachedBaseUrl: String? = null
    @Volatile private var cachedLora: String? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

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

    suspend fun getLora(): String {
        cachedLora?.let { return it }
        val fb = PlayFyFirebaseConfigFetcher.getLora()
        cachedLora = if (!fb.isNullOrBlank()) fb else PlayFyCryptoUtils.DEFAULT_LORA
        return cachedLora!!
    }

    private fun fetchAndDecrypt(url: String, lora: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                println("PlayFy: HTTP ${response.code} → $url")
                return null
            }
            val raw = response.body.string()
            if (raw.isBlank()) return null
            val encoded = PlayFyCryptoUtils.extractDataField(raw)
            val t = encoded.trimStart()
            if (t.startsWith("[") || t.startsWith("{")) return encoded
            PlayFyCryptoUtils.decryptPlayFy(encoded, lora, PlayFyCryptoUtils.DEFAULT_SIG)
        } catch (e: Exception) {
            println("PlayFy: fetchAndDecrypt error for $url: ${e.message}")
            null
        }
    }

    suspend fun fetchProviders(): List<Map<String, Any>> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = getBaseUrl()
            val url = "${baseUrl}categories.txt"
            println("PlayFy: Fetching categories from $url")
            val json = fetchDecrypted(url) ?: return@withContext emptyList()
            val wrappers = AppUtils.parseJson<List<PlayFyCatFilter>>(json)
            wrappers.mapIndexedNotNull { index, wrapper ->
                if (wrapper.title.isNullOrBlank()) return@mapIndexedNotNull null
                mapOf(
                    "id" to (index + 1),
                    "title" to wrapper.title,
                    "image" to (wrapper.image ?: ""),
                    "catLink" to (wrapper.id ?: ""),
                    "type" to "m3u"
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
            val wrappers = AppUtils.parseJson<List<PlayFyEventWrapper>>(json)
            wrappers.mapIndexedNotNull { index, wrapper ->
                if (wrapper.event.isBlank()) return@mapIndexedNotNull null
                val ev = try {
                    readyEventInfo(wrapper.event)
                } catch (e: Exception) { return@mapIndexedNotNull null }
                if (ev.slug.isBlank()) return@mapIndexedNotNull null
                LiveEventData(
                    id = index + 1,
                    title = ev.title,
                    image = ev.image,
                    slug = ev.slug,
                    cat = ev.cat ?: "Sports",
                    publish = 1,
                    eventInfo = LiveEventInfo(
                        teamA = ev.teamA,
                        teamB = ev.teamB,
                        teamAFlag = ev.teamAFlag,
                        teamBFlag = ev.teamBFlag,
                        eventCat = ev.cat ?: "Sports",
                        eventName = ev.eventName ?: ev.title,
                        eventLogo = ev.image,
                        isHot = null,
                        eventType = ev.cat ?: "Sports",
                        startTime = ev.startTime,
                        endTime = ev.endTime
                    ),
                    formats = ev.formats?.map { LiveEventFormat(title = it, webLink = null) } ?: emptyList()
                )
            }
        } catch (e: Exception) {
            println("PlayFy: fetchLiveEvents exception: ${e.message}")
            emptyList()
        }
    }

    private data class ReadyEvent(
        val slug: String,
        val title: String,
        val image: String?,
        val cat: String?,
        val teamA: String?,
        val teamB: String?,
        val teamAFlag: String?,
        val teamBFlag: String?,
        val eventName: String?,
        val startTime: String?,
        val endTime: String?,
        val formats: List<String>?
    )

    private fun readyEventInfo(json: String): ReadyEvent {
        val ev = AppUtils.parseJson<PlayFyEventInfo>(json)
        return ReadyEvent(
            slug = ev.eventName ?: "",
            title = buildString {
                if (!ev.teamA.isNullOrBlank() && !ev.teamB.isNullOrBlank()) {
                    append(ev.teamA); append(" vs "); append(ev.teamB)
                } else append(ev.eventName ?: "")
            },
            image = ev.eventBanner,
            cat = null,
            teamA = ev.teamA,
            teamB = ev.teamB,
            teamAFlag = ev.teamAFlag,
            teamBFlag = ev.teamBFlag,
            eventName = ev.eventName,
            startTime = ev.startTime,
            endTime = ev.endTime,
            formats = null
        )
    }

    suspend fun fetchHighlights(): List<PlayFyChannel> = withContext(Dispatchers.IO) {
        fetchChannelList("cats/highlights.json")
    }

    private suspend fun fetchChannelList(path: String): List<PlayFyChannel> = withContext(Dispatchers.IO) {
        try {
            val lora = getLora()
            val baseUrl = getBaseUrl()
            val url = "${baseUrl}${path}"
            println("PlayFy: fetchChannelList → $url")
            val json = fetchAndDecrypt(url, lora) ?: return@withContext emptyList()
            val resp = AppUtils.parseJson<PlayFyChannelListResponse>(json)
            resp.channels?.filter { it.publish == "1" } ?: emptyList()
        } catch (e: Exception) {
            println("PlayFy: fetchChannelList($path) error: ${e.message}")
            emptyList()
        }
    }

    suspend fun fetchChannelStreams(channelId: String): List<PlayFyStreamEntry> = withContext(Dispatchers.IO) {
        try {
            val lora = getLora()
            val baseUrl = getBaseUrl()
            val url = "${baseUrl}channels/${channelId}.json"
            println("PlayFy: fetchChannelStreams → $url")
            val json = fetchAndDecrypt(url, lora) ?: return@withContext emptyList()
            AppUtils.parseJson<List<PlayFyStreamEntry>>(json)
        } catch (e: Exception) {
            println("PlayFy: fetchChannelStreams($channelId) error: ${e.message}")
            emptyList()
        }
    }

    fun invalidateCache() {
        cachedBaseUrl = null
        cachedLora = null
    }

    private suspend fun fetchDecrypted(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).apply {
                header("User-Agent", USER_AGENT)
                header("Accept", "*/*")
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

    suspend fun fetchCustomEvents(catLink: String): List<LiveEventData> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = getBaseUrl()
            val url = if (catLink.startsWith("http")) catLink else "$baseUrl$catLink"
            println("PlayFy: Fetching custom events from $url")
            val json = fetchDecrypted(url) ?: return@withContext emptyList()
            val wrappers = AppUtils.parseJson<List<PlayFyChannelListResponse>>(json)
            wrappers.mapIndexedNotNull { index, wrapper ->
                wrapper.channels?.firstOrNull()?.let { ch ->
                    LiveEventData(
                        id = index + 1,
                        title = ch.title ?: "Unknown",
                        image = ch.image,
                        slug = ch.id ?: "",
                        cat = ch.cat ?: ch.category ?: "Custom",
                        publish = 1,
                        eventInfo = LiveEventInfo(
                            teamA = ch.eventInfo?.teamA ?: ch.title,
                            teamB = ch.eventInfo?.teamB,
                            teamAFlag = ch.eventInfo?.teamAFlag ?: ch.image,
                            teamBFlag = ch.eventInfo?.teamBFlag,
                            eventCat = ch.category ?: "Custom",
                            eventName = ch.eventInfo?.eventName ?: ch.title,
                            eventLogo = ch.eventInfo?.eventBanner ?: ch.image,
                            isHot = null,
                            eventType = ch.category ?: "Custom",
                            startTime = ch.eventInfo?.startTime,
                            endTime = ch.eventInfo?.endTime
                        ),
                        formats = ch.formats?.map { LiveEventFormat(title = it, webLink = null) }
                            ?: listOf(LiveEventFormat(title = ch.title ?: "Link 1", webLink = null))
                    )
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
