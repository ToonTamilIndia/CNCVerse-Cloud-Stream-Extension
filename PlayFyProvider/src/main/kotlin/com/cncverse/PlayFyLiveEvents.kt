package com.cncverse

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

class PlayFyLiveEvents(
    override var name: String = "⚡PlayFy Live Events",
    val mode: String = "live"
) : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }

    override var mainUrl = "https://reliablewebcenter.online"
    override var lang = "ta"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    data class PlayFyLoadData(
        val channelId: String,
        val title: String,
        val poster: String,
        val category: String,
        val eventInfo: PlayFyEventInfo?,
        val formats: List<String>
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun createDisplayTitle(ch: PlayFyChannel): String {
        val info = ch.eventInfo
        if (info != null && !info.teamA.isNullOrBlank() && !info.teamB.isNullOrBlank()) {
            return if (isSameTeam(info.teamA, info.teamB)) info.teamA else "${info.teamA} vs ${info.teamB}"
        }
        return ch.title ?: "Unknown"
    }

    private fun parseTime(value: String?): Long? {
        val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
        return value?.let { fmt.parse(it)?.time }
    }

    private fun getEventStatus(ch: PlayFyChannel): String {
        val info = ch.eventInfo ?: return ""
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
        } catch (e: Exception) {
            ""
        }
    }

    private fun isEventLive(ch: PlayFyChannel): Boolean {
        val info = ch.eventInfo ?: return false
        val now = System.currentTimeMillis()
        return try {
            val start = parseTime(info.startTime)
            val end = parseTime(info.endTime)
            (end == null || now < end) && start != null && now >= start
        } catch (e: Exception) {
            false
        }
    }

    private fun isEventEnded(ch: PlayFyChannel): Boolean {
        val info = ch.eventInfo ?: return false
        val now = System.currentTimeMillis()
        return try {
            val end = parseTime(info.endTime)
            end != null && now >= end
        } catch (e: Exception) {
            false
        }
    }

    private fun isSameTeam(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        return a.trim().equals(b.trim(), ignoreCase = true)
    }

    private fun generateMatchCardUrl(ch: PlayFyChannel): String {
        val info = ch.eventInfo
        val title = URLEncoder.encode(info?.eventName ?: ch.title ?: "", "UTF-8")
        val teamA = URLEncoder.encode(info?.teamA ?: "Team A", "UTF-8")
        val teamB = URLEncoder.encode(info?.teamB ?: "Team B", "UTF-8")
        val sameTeams = isSameTeam(info?.teamA, info?.teamB)
        val teamAImg = info?.teamAFlag?.takeIf { it.isNotBlank() }
            ?: (if (sameTeams) info?.teamBFlag else null)
            ?: ""
        val teamBImg = if (sameTeams) "" else info?.teamBFlag ?: ""
        val eventLogo = info?.eventBanner ?: ""
        val isLive = isEventLive(ch)
        val isEnded = isEventEnded(ch)

        val time = try {
            info?.startTime?.let {
                val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
                val displayFormat = SimpleDateFormat("MMM dd, yyyy hh:mm a z", Locale.US)
                dateFormat.parse(it)?.let { d -> URLEncoder.encode(displayFormat.format(d), "UTF-8") } ?: ""
            } ?: ""
        } catch (e: Exception) {
            ""
        }

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

    private fun toSearchResponse(ch: PlayFyChannel, posterUrl: String): SearchResponse {
        val displayTitle = createDisplayTitle(ch)
        val status = getEventStatus(ch)
        val fullTitle = if (status.isNotBlank()) "$status $displayTitle" else displayTitle
        val loadData = PlayFyLoadData(
            channelId = ch.id ?: "",
            title = displayTitle,
            poster = posterUrl,
            category = ch.category ?: ch.cat ?: "",
            eventInfo = ch.eventInfo,
            formats = ch.formats ?: emptyList()
        )
        return newLiveSearchResponse(fullTitle, loadData.toJson(), TvType.Live) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        PlayFyProviderManager.refreshFromFirebase()
        val channels = if (mode == "highlights") {
            PlayFyProviderManager.fetchHighlights()
        } else {
            PlayFyProviderManager.fetchLiveEvents()
        }

        val lists = channels
            .groupBy { it.category ?: it.cat ?: "Other" }
            .map { (category, items) ->
                val icon = when (category.lowercase()) {
                    "cricket" -> "🏏"
                    "football" -> "⚽"
                    "basketball" -> "🏀"
                    "ice hockey" -> "🏒"
                    "boxing" -> "🥊"
                    "motorsport" -> "🏎️"
                    "tennis" -> "🎾"
                    else -> "📺"
                }
                val sorted = items
                    .sortedWith(
                        compareBy<PlayFyChannel> { ch ->
                            val status = getEventStatus(ch)
                            when {
                                status.contains("🔴") -> 0
                                status.contains("🔜") -> 1
                                status.contains("✅") -> 2
                                else -> 3
                            }
                        }.thenBy { ch ->
                            try {
                                parseTime(ch.eventInfo?.startTime) ?: Long.MAX_VALUE
                            } catch (e: Exception) {
                                Long.MAX_VALUE
                            }
                        }
                    )
                    .map { ch ->
                        val posterUrl = if (ch.cat == "Custom" && !ch.image.isNullOrBlank()) {
                            ch.image
                        } else {
                            generateMatchCardUrl(ch)
                        }
                        toSearchResponse(ch, posterUrl)
                    }
                HomePageList("$icon $category", sorted, isHorizontalImages = true)
            }
            .sortedBy { list ->
                when {
                    list.name.contains("Cricket", ignoreCase = true) -> 0
                    list.name.contains("Football", ignoreCase = true) -> 1
                    list.name.contains("Basketball", ignoreCase = true) -> 2
                    else -> 10
                }
            }

        return newHomePageResponse(lists, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return PlayFyProviderManager.fetchLiveEvents()
            .filter { ch ->
                listOfNotNull(ch.title, ch.eventInfo?.teamA, ch.eventInfo?.teamB, ch.eventInfo?.eventName)
                    .joinToString(" ")
                    .contains(query, ignoreCase = true)
            }
            .map { ch -> toSearchResponse(ch, generateMatchCardUrl(ch)) }
    }

    override suspend fun load(url: String): LoadResponse {
        val data = parseJson<PlayFyLoadData>(url)
        val info = data.eventInfo
        val plot = buildString {
            if (info != null) {
                info.eventName?.let { append("🏆 $it\n") }
                info.startTime?.let {
                    try {
                        val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
                        val disp = SimpleDateFormat("MMM dd, yyyy HH:mm z", Locale.US)
                        append("🕐 ${disp.format(fmt.parse(it)!!)}\n")
                    } catch (e: Exception) {
                        append("🕐 $it\n")
                    }
                }
            }
            if (data.formats.isNotEmpty()) append("\n📡 Available Servers: ${data.formats.size}")
        }

        return newLiveStreamLoadResponse(data.title, url, url) {
            this.posterUrl = data.poster
            this.plot = plot.ifBlank { null }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = parseJson<PlayFyLoadData>(data)
        val streams = PlayFyProviderManager.fetchChannelStreams(loadData.channelId)
        if (streams.isEmpty()) return false

        streams.forEach { stream ->
            try {
                val serverName = stream.title ?: "Server"
                if (stream.type == "2") {
                    val embedUrl = stream.webLink?.takeIf { it.isNotBlank() }
                        ?: stream.link?.takeIf { it.isNotBlank() }
                        ?: return@forEach
                    val extracted = loadEmbedInWebView(embedUrl) ?: return@forEach
                    val (url, headers) = parseStreamLink(extracted)
                    if (url.isNotBlank()) emitLink(callback, serverName, url, headers, stream.api)
                } else {
                    val rawLink = stream.link?.takeIf { it.isNotBlank() } ?: return@forEach
                    val (url, headers) = parseStreamLink(rawLink)
                    if (url.isNotBlank()) emitLink(callback, serverName, url, headers, stream.api)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return true
    }

    private suspend fun emitLink(
        callback: (ExtractorLink) -> Unit,
        serverName: String,
        url: String,
        headers: Map<String, String>,
        api: String?
    ) {
        if (url.contains(".mpd")) {
            val drmInfo = api?.takeIf { it.contains(":") }?.split(":", limit = 2)
            if (drmInfo != null && drmInfo.size == 2) {
                val kidB64 = hexToBase64(drmInfo[0])
                val keyB64 = hexToBase64(drmInfo[1])
                if (kidB64 != null && keyB64 != null) {
                    callback(
                        newDrmExtractorLink(name, serverName, url, INFER_TYPE, CLEARKEY_UUID) {
                            this.quality = Qualities.Unknown.value
                            this.key = keyB64
                            this.kid = kidB64
                            if (headers.isNotEmpty()) this.headers = headers
                        }
                    )
                    return
                }
            }
            callback(
                newExtractorLink(name, serverName, url, ExtractorLinkType.DASH) {
                    this.quality = Qualities.Unknown.value
                    if (headers.isNotEmpty()) this.headers = headers
                }
            )
            return
        }

        val h = headers.toMutableMap()
        if (!h.containsKey("User-Agent")) {
            h["User-Agent"] = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/122.0.0.0 Mobile Safari/537.36"
        }
        val type = if (url.contains(".m3u8") || url.contains(".m3u")) ExtractorLinkType.M3U8 else INFER_TYPE
        callback(
            newExtractorLink(name, serverName, url, type) {
                this.quality = Qualities.Unknown.value
                if (h.isNotEmpty()) this.headers = h
            }
        )
    }

    private fun hexToBase64(hex: String): String? {
        val n = hex.replace("-", "").trim()
        if (n.isEmpty() || n.length % 2 != 0 || !Regex("^[0-9a-fA-F]+$").matches(n)) return null
        return try {
            val bytes = n.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseStreamLink(link: String): Pair<String, Map<String, String>> {
        val headers = mutableMapOf<String, String>()
        if (!link.contains("|")) return Pair(link, headers)

        val parts = link.split("|", limit = 2)
        val url = parts[0]
        if (parts.size > 1) {
            parts[1].split("&").forEach { pair ->
                val kv = pair.split("=", limit = 2)
                if (kv.size == 2) {
                    val headerName = when (kv[0].trim().lowercase()) {
                        "user-agent" -> "User-Agent"
                        "referer" -> "Referer"
                        "origin" -> "Origin"
                        "cookie" -> "Cookie"
                        else -> kv[0].trim()
                    }
                    headers[headerName] = kv[1].trim()
                }
            }
        }
        return Pair(url, headers)
    }

    private suspend fun loadEmbedInWebView(embedUrl: String): String? {
        if (embedUrl.contains(".m3u8") || embedUrl.contains(".mpd") || embedUrl.contains(".mp4")) {
            return embedUrl
        }
        return withContext(Dispatchers.Main) {
            suspendCoroutine { cont ->
                try {
                    val ctx = context
                    if (ctx == null) {
                        cont.resume(null)
                        return@suspendCoroutine
                    }
                    val wv = WebView(ctx)
                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        mediaPlaybackRequiresUserGesture = false
                    }
                    var captured = false

                    fun finish(result: String?) {
                        Handler(Looper.getMainLooper()).post {
                            try {
                                wv.destroy()
                            } catch (_: Exception) {
                            }
                            cont.resume(result)
                        }
                    }

                    wv.addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onStreamUrlFound(url: String) {
                            if (!captured && url.isNotBlank()) {
                                captured = true
                                finish(url)
                            }
                        }
                    }, "StreamBridge")

                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                            val u = req.url.toString()
                            if (!captured && (u.contains(".m3u8") || u.contains(".mpd") || u.contains(".mp4"))) {
                                captured = true
                                finish(u)
                            }
                            return super.shouldInterceptRequest(view, req)
                        }

                        override fun onPageFinished(view: WebView, pageUrl: String) {
                            super.onPageFinished(view, pageUrl)
                            if (captured) return
                            Handler(Looper.getMainLooper()).postDelayed({
                                wv.evaluateJavascript(
                                    "(function(){ if(typeof playbackURL!=='undefined'&&playbackURL) window.StreamBridge.onStreamUrlFound(playbackURL); })();",
                                    null
                                )
                            }, 500)
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (!captured) {
                                    captured = true
                                    try {
                                        wv.destroy()
                                    } catch (_: Exception) {
                                    }
                                    cont.resume(null)
                                }
                            }, 4000)
                        }
                    }
                    wv.webChromeClient = WebChromeClient()
                    wv.loadUrl(embedUrl)

                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!captured) {
                            captured = true
                            try {
                                wv.destroy()
                            } catch (_: Exception) {
                            }
                            try {
                                cont.resume(null)
                            } catch (_: Exception) {
                            }
                        }
                    }, 30000)
                } catch (e: Exception) {
                    cont.resume(null)
                }
            }
        }
    }
}
