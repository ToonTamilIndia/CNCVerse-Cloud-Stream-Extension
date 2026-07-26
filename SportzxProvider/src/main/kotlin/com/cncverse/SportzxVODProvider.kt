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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class SportzxVODProvider(
    private val customName: String,
    private val catLink: String
) : MainAPI() {

    override var mainUrl = "https://sportzx.live"
    override var name = customName
    override var lang = "ta"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Live)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = SportzxProviderManager.fetchVODCategory(catLink)

        val searchResponses = items.mapNotNull { item ->
            val id = item.id ?: return@mapNotNull null
            val title = item.title ?: "Unknown"
            val image = item.image
            val cat = item.cat
            val formats = item.formats ?: emptyList()
            val loadData = SportzxVODLoadData(id, title, image, cat, formats)
            newMovieSearchResponse(title, loadData.toJson(), TvType.Movie, false) {
                posterUrl = image
            }
        }

        return newHomePageResponse(
            listOf(HomePageList(customName, searchResponses, isHorizontalImages = false)),
            hasNext = false
        )
    }

    override suspend fun load(url: String): LoadResponse {
        val data = parseJson<SportzxVODLoadData>(url)
        val plot = "📡 Available Formats: ${data.formats.size}"
        return newMovieLoadResponse(data.title, url, TvType.Movie, url) {
            posterUrl = data.poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = parseJson<SportzxVODLoadData>(data)
        val baseUrl = SportzxProviderManager.getBaseUrl().ifEmpty { mainUrl }
        val apiUrl = "${baseUrl}/channels/${loadData.cat}/${loadData.id}.json"

        val request = Request.Builder()
            .url(apiUrl)
            .header("User-Agent", "Dalvik/2.1.0 (Linux; Android 13)")
            .header("Accept", "*/*")
            .build()

        val responseBody = try {
            client.newCall(request).execute().use { it.body.string() }
        } catch (e: Exception) {
            return false
        }

        if (responseBody.isBlank()) return false

        val decrypted = SportzxCryptoUtils.decrypt(responseBody) ?: return false

        val streams = try {
            parseJson<List<SportzxStreamEntry>>(decrypted)
        } catch (e: Exception) {
            return false
        }

        if (streams.isEmpty()) return false

        streams.forEach { stream ->
            val serverName = stream.title ?: "Server"
            val link = stream.link ?: return@forEach
            val parts = link.split("|", limit = 2)
            val url = parts[0].trim()
            if (url.isBlank()) return@forEach

            val headers = mutableMapOf<String, String>()
            if (parts.size > 1) {
                parts[1].split("&").forEach { kv ->
                    val eq = kv.split("=", limit = 2)
                    if (eq.size == 2) {
                        val k = when (eq[0].trim().lowercase()) {
                            "user-agent" -> "User-Agent"
                            "referer" -> "Referer"
                            "origin" -> "Origin"
                            "cookie" -> "Cookie"
                            else -> eq[0].trim()
                        }
                        headers[k] = eq[1].trim()
                    }
                }
            }

            try {
                when {
                    url.contains(".mpd") -> {
                        val apiParts = stream.api?.split(":", limit = 2)
                        if (!apiParts.isNullOrEmpty() && apiParts.size == 2 &&
                            apiParts[0].isNotBlank() && apiParts[1].isNotBlank()
                        ) {
                            val kidHex = apiParts[0].trim().replace("-", "")
                            val keyHex = apiParts[1].trim().replace("-", "")

                            fun hexToBase64Url(hex: String): String? = try {
                                val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                                Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                            } catch (_: Exception) { null }

                            val kidB64 = hexToBase64Url(kidHex)
                            val keyB64 = hexToBase64Url(keyHex)

                            if (kidB64 != null && keyB64 != null) {
                                callback.invoke(
                                    newDrmExtractorLink(name, serverName, url, INFER_TYPE, CLEARKEY_UUID) {
                                        quality = Qualities.Unknown.value
                                        this.key = keyB64
                                        this.kid = kidB64
                                        if (headers.isNotEmpty()) this.headers = headers
                                    }
                                )
                            } else {
                                callback.invoke(
                                    newExtractorLink(name, serverName, url, ExtractorLinkType.DASH) {
                                        quality = Qualities.Unknown.value
                                        if (headers.isNotEmpty()) this.headers = headers
                                    }
                                )
                            }
                        } else {
                            callback.invoke(
                                newExtractorLink(name, serverName, url, ExtractorLinkType.DASH) {
                                    quality = Qualities.Unknown.value
                                    if (headers.isNotEmpty()) this.headers = headers
                                }
                            )
                        }
                    }
                    else -> {
                        val finalHeaders = headers.toMutableMap()
                        if (!finalHeaders.containsKey("User-Agent")) {
                            finalHeaders["User-Agent"] =
                                "Mozilla/5.0 (Linux; Android 10; Pixel 3 XL) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
                        }
                        callback.invoke(
                            newExtractorLink(name, serverName, url, ExtractorLinkType.M3U8) {
                                quality = Qualities.Unknown.value
                                if (finalHeaders.isNotEmpty()) this.headers = finalHeaders
                            }
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }

        return true
    }
}
