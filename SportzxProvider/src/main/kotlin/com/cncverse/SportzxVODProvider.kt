package com.cncverse

import android.content.Context
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

class SportzxVODProvider(
    val customName: String,
    val catLink: String
) : MainAPI() {

    companion object {
        var context: Context? = null
    }

    override var mainUrl = "https://sportzx.live"
    override var name = customName
    override var lang = "ta"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Live)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        SportzxProviderManager.refreshFromFirebase()
        val items = SportzxProviderManager.fetchVODCategory(catLink)

        val searchResponses = items.mapNotNull { item ->
            val id = item.id ?: return@mapNotNull null
            val title = item.title ?: "Unknown"
            val loadData = SportzxVODLoadData(id, title, item.image, item.cat, item.formats ?: emptyList())
            newMovieSearchResponse(title, loadData.toJson(), TvType.Movie, false) {
                posterUrl = item.image
            }
        }

        return newHomePageResponse(HomePageList(customName, searchResponses, isHorizontalImages = false))
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
        val streamJson = SportzxProviderManager.fetchStreamData(loadData.id)
        if (streamJson.isNullOrBlank()) {
            println("SportzxVOD: No stream data for VOD item ${loadData.id}")
            return false
        }

        try {
            val streams = parseJson<List<SportzxStreamEntry>>(streamJson)
            if (streams.isEmpty()) return false

            streams.forEach { stream ->
                val serverName = stream.title ?: "Server"
                val link = stream.link ?: return@forEach
                val parts = link.split("|", limit = 2)
                val url = parts[0].trim()
                if (url.isBlank()) return@forEach

                val customHeaders = mutableMapOf<String, String>()
                if (parts.size == 2) {
                    parts[1].split("&").forEach { kv ->
                        val eq = kv.split("=", limit = 2)
                        if (eq.size == 2) customHeaders[eq[0].trim()] = eq[1].trim()
                    }
                }

                val api = stream.api
                if (api != null && api.contains(":")) {
                    val apiParts = api.split(":", limit = 2)
                    val kidHex = apiParts[0].trim().replace("-", "")
                    val keyHex = apiParts[1].trim().replace("-", "")
                    val kidB64 = hexToBase64Url(kidHex)
                    val keyB64 = hexToBase64Url(keyHex)
                    if (kidB64 != null && keyB64 != null) {
                        callback.invoke(
                            newDrmExtractorLink(name, serverName, url, INFER_TYPE, CLEARKEY_UUID) {
                                quality = Qualities.Unknown.value
                                this.key = keyB64
                                this.kid = kidB64
                                if (customHeaders.isNotEmpty()) this.headers = customHeaders
                            }
                        )
                    } else {
                        callback.invoke(
                            newExtractorLink(name, serverName, url, ExtractorLinkType.DASH) {
                                quality = Qualities.Unknown.value
                                if (customHeaders.isNotEmpty()) this.headers = customHeaders
                            }
                        )
                    }
                } else {
                    val type = when {
                        url.contains(".m3u") || url.contains(".m3u8") -> ExtractorLinkType.M3U8
                        url.contains(".mpd") -> ExtractorLinkType.DASH
                        else -> INFER_TYPE
                    }
                    callback.invoke(
                        newExtractorLink(name, serverName, url, type) {
                            quality = Qualities.Unknown.value
                            if (customHeaders.isNotEmpty()) this.headers = customHeaders
                        }
                    )
                }
            }
        } catch (e: Exception) {
            println("SportzxVOD: Failed to parse stream list — ${e.message}")
            return false
        }

        return true
    }

    private fun hexToBase64Url(hex: String): String? = try {
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    } catch (_: Exception) {
        null
    }
}
