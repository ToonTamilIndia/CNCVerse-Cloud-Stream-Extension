package com.cncverse

import com.lagradost.cloudstream3.utils.AppUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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

object PlayFyProviderManager {

    private const val DEFAULT_BASE_URL = "https://reliablewebcenter.online"
    private const val USER_AGENT = "PLAYFy/1.7 (Android)"
    private const val CONFIG_TTL_MS = 30 * 60 * 1000L
    private const val FALLBACK_TTL_MS = 2 * 60 * 1000L
    private const val MIN_REFETCH_MS = 60 * 1000L

    private val baseUrl = ExpiringRemoteValue(CONFIG_TTL_MS, FALLBACK_TTL_MS, MIN_REFETCH_MS)
    private val lora = ExpiringRemoteValue(CONFIG_TTL_MS, FALLBACK_TTL_MS, MIN_REFETCH_MS)

    fun refreshFromFirebase() {
        baseUrl.reset()
        lora.reset()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private suspend fun getBaseUrl(): String =
        baseUrl.get({ PlayFyFirebaseConfigFetcher.getBaseUrl() }, { DEFAULT_BASE_URL })

    private suspend fun getLora(): String =
        lora.get({ PlayFyFirebaseConfigFetcher.getLora() }, { PlayFyCryptoUtils.DEFAULT_LORA })

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
                response.close()
                baseUrl.invalidate()
                return null
            }
            val raw = response.body.string()
            if (raw.isBlank()) return null
            val encoded = PlayFyCryptoUtils.extractDataField(raw)
            val t = encoded.trimStart()
            if (t.startsWith("[") || t.startsWith("{")) return encoded
            PlayFyCryptoUtils.decryptPlayFy(encoded, lora, PlayFyCryptoUtils.DEFAULT_SIG) ?: run {
                this.lora.invalidate()
                null
            }
        } catch (e: Exception) {
            println("PlayFy: fetchAndDecrypt error for $url: ${e.message}")
            baseUrl.invalidate()
            null
        }
    }

    suspend fun fetchProviders(): List<Map<String, Any>> = withContext(Dispatchers.IO) {
        try {
            val lora = getLora()
            val url = "${getBaseUrl()}/cc.json"
            println("PlayFy: fetchProviders → $url")
            val json = fetchAndDecrypt(url, lora) ?: return@withContext emptyList()
            val cats = AppUtils.parseJson<List<PlayFyCategoryData>>(json)
            cats.filter {
                it.publish == "1" && it.catLink != null && it.catLink != "null" && it.catLink.isNotBlank()
            }.mapNotNull { c ->
                val t = c.title ?: return@mapNotNull null
                mapOf(
                    "id" to (c.id ?: ""),
                    "title" to t,
                    "image" to (c.image ?: ""),
                    "catLink" to c.catLink!!,
                    "type" to "m3u"
                )
            }
        } catch (e: Exception) {
            println("PlayFy: fetchProviders error: ${e.message}")
            emptyList()
        }
    }

    suspend fun fetchLiveEvents(): List<PlayFyChannel> = withContext(Dispatchers.IO) {
        fetchChannelList("cats/live-events.json")
    }

    suspend fun fetchHighlights(): List<PlayFyChannel> = withContext(Dispatchers.IO) {
        fetchChannelList("cats/highlights.json")
    }

    private suspend fun fetchChannelList(path: String): List<PlayFyChannel> {
        return try {
            val lora = getLora()
            val url = "${getBaseUrl()}/$path"
            println("PlayFy: fetchChannelList → $url")
            val json = fetchAndDecrypt(url, lora) ?: return emptyList()
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
            val url = "${getBaseUrl()}/channels/$channelId.json"
            println("PlayFy: fetchChannelStreams → $url")
            val json = fetchAndDecrypt(url, lora) ?: return@withContext emptyList()
            AppUtils.parseJson<List<PlayFyStreamEntry>>(json)
        } catch (e: Exception) {
            println("PlayFy: fetchChannelStreams($channelId) error: ${e.message}")
            emptyList()
        }
    }
}
