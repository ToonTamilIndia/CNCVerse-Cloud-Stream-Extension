package com.cncverse

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.WebChromeClient
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
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class PlayFyLiveEvents(private val customName: String = "PlayFy Live Events", val customCatLink: String? = null) : MainAPI() {
    companion object {
        var context: android.content.Context? = null
        private var cachedWebUrl: String? = null
        private const val DEFAULT_WEB_URL = "https://welalagaa.site"
    }

    override var mainUrl = DEFAULT_WEB_URL
    override var name = customName
    override var lang = "en"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    data class PlayFyLiveEventLoadData(
        val eventId: Int,
        val title: String,
        val poster: String,
        val slug: String,
        val formats: List<LiveEventFormat>,
        val eventInfo: LiveEventInfo?
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private suspend fun getWebUrl(): String {
        cachedWebUrl?.let { return it }
        val firebaseUrl = PlayFyFirebaseConfigFetcher.getBaseApiUrl()
        if (!firebaseUrl.isNullOrBlank()) {
            cachedWebUrl = firebaseUrl
            mainUrl = cachedWebUrl!!
            return cachedWebUrl!!
        }
        cachedWebUrl = DEFAULT_WEB_URL
        mainUrl = DEFAULT_WEB_URL
        return DEFAULT_WEB_URL
    }

    private fun createDisplayTitle(event: LiveEventData): String {
        val eventInfo = event.eventInfo
        return if (eventInfo != null &&
            !eventInfo.teamA.isNullOrBlank() &&
            !eventInfo.teamB.isNullOrBlank()
        ) {
            if (eventInfo.teamA == eventInfo.teamB) {
                eventInfo.teamA
            } else {
                "${eventInfo.teamA} vs ${eventInfo.teamB}"
            }
        } else {
            event.title
        }
    }

    private fun getEventStatus(event: LiveEventData): String {
        val eventInfo = event.eventInfo ?: return ""
        val now = System.currentTimeMillis()

        try {
            val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
            val startTime = eventInfo.startTime?.let { dateFormat.parse(it)?.time }
            val endTime = eventInfo.endTime?.let { dateFormat.parse(it)?.time }

            return when {
                endTime != null && now >= endTime -> "✅"
                startTime != null && now >= startTime -> "🔴"
                startTime != null && now < startTime -> "🔜"
                else -> ""
            }
        } catch (e: Exception) {
            return ""
        }
    }

    private fun isEventLive(event: LiveEventData): Boolean {
        val eventInfo = event.eventInfo ?: return false
        val now = System.currentTimeMillis()

        return try {
            val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
            val startTime = eventInfo.startTime?.let { dateFormat.parse(it)?.time }
            val endTime = eventInfo.endTime?.let { dateFormat.parse(it)?.time }

            if (endTime != null && now >= endTime) {
                false
            } else if (startTime != null && now >= startTime) {
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun isEventEnded(event: LiveEventData): Boolean {
        val eventInfo = event.eventInfo ?: return false
        val now = System.currentTimeMillis()

        return try {
            val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
            val endTime = eventInfo.endTime?.let { dateFormat.parse(it)?.time }
            endTime != null && now >= endTime
        } catch (e: Exception) {
            false
        }
    }

    private fun generateMatchCardUrl(event: LiveEventData): String {
        val eventInfo = event.eventInfo

        val title = java.net.URLEncoder.encode(eventInfo?.eventName ?: event.title, "UTF-8")
        val teamA = java.net.URLEncoder.encode(eventInfo?.teamA ?: "Team A", "UTF-8")
        val teamB = java.net.URLEncoder.encode(eventInfo?.teamB ?: "Team B", "UTF-8")
        val teamAImg = eventInfo?.teamAFlag ?: ""
        val teamBImg = eventInfo?.teamBFlag ?: ""
        val eventLogo = eventInfo?.eventLogo ?: ""
        val isLive = isEventLive(event)
        val isEnded = isEventEnded(event)

        val time = try {
            eventInfo?.startTime?.let {
                val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
                val displayFormat = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.US)
                val date = dateFormat.parse(it)
                date?.let { d -> java.net.URLEncoder.encode(displayFormat.format(d), "UTF-8") } ?: ""
            } ?: ""
        } catch (e: Exception) {
            ""
        }

        return buildString {
            append("https://live-card-png.cricify.workers.dev/?")
            append("title=$title")
            append("&teamA=$teamA")
            append("&teamB=$teamB")
            if (teamAImg.isNotBlank()) append("&teamAImg=$teamAImg")
            if (teamBImg.isNotBlank()) append("&teamBImg=$teamBImg")
            if (eventLogo.isNotBlank()) append("&eventLogo=$eventLogo")
            if (time.isNotBlank()) append("&time=$time")
            append("&isLive=$isLive")
            append("&isEnded=$isEnded")
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val events = if (customCatLink != null) {
            PlayFyProviderManager.fetchCustomEvents(customCatLink)
        } else {
            PlayFyProviderManager.fetchLiveEvents()
        }

        val groupedEvents = events.groupBy { it.eventInfo?.eventCat ?: it.cat ?: "Other" }

        val homePageLists = groupedEvents
            .map { (category, categoryEvents) ->
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

                val searchResponses = categoryEvents
                    .sortedWith(
                        compareBy<LiveEventData> { event ->
                            val status = getEventStatus(event)
                            when {
                                status.contains("🔴") -> 0
                                status.contains("🔜") -> 1
                                status.contains("✅") -> 2
                                else -> 3
                            }
                        }.thenBy { event ->
                            try {
                                val info = event.eventInfo ?: return@thenBy Long.MAX_VALUE
                                val startTime = info.startTime ?: return@thenBy Long.MAX_VALUE
                                val fmt = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", java.util.Locale.US)
                                fmt.parse(startTime)?.time ?: Long.MAX_VALUE
                            } catch (e: Exception) { Long.MAX_VALUE }
                        }
                    )
                    .map { event ->
                        val displayTitle = createDisplayTitle(event)
                        val status = getEventStatus(event)
                        val fullTitle = if (status.isNotBlank()) "$status $displayTitle" else displayTitle

                        val posterUrl = generateMatchCardUrl(event)

                        val loadData = PlayFyLiveEventLoadData(
                            eventId = event.id,
                            title = displayTitle,
                            poster = posterUrl,
                            slug = event.slug,
                            formats = event.formats ?: emptyList(),
                            eventInfo = event.eventInfo
                        )

                        newLiveSearchResponse(
                            name = fullTitle,
                            url = loadData.toJson(),
                            type = TvType.Live
                        ) { this.posterUrl = posterUrl }
                    }

                HomePageList(
                    name = "$icon $category",
                    list = searchResponses,
                    isHorizontalImages = true
                )
            }

        return newHomePageResponse(homePageLists, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val events = PlayFyProviderManager.fetchLiveEvents()

        return events
            .filter { event ->
                val searchText = listOfNotNull(
                    event.title,
                    event.eventInfo?.teamA,
                    event.eventInfo?.teamB,
                    event.eventInfo?.eventName,
                    event.eventInfo?.eventType
                ).joinToString(" ")

                searchText.contains(query, ignoreCase = true)
            }
            .map { event ->
                val displayTitle = createDisplayTitle(event)
                val status = getEventStatus(event)
                val fullTitle = if (status.isNotBlank()) "$status $displayTitle" else displayTitle

                val posterUrl = generateMatchCardUrl(event)

                val loadData = PlayFyLiveEventLoadData(
                    eventId = event.id,
                    title = displayTitle,
                    poster = posterUrl,
                    slug = event.slug,
                    formats = event.formats ?: emptyList(),
                    eventInfo = event.eventInfo
                )

                newLiveSearchResponse(
                    name = fullTitle,
                    url = loadData.toJson(),
                    type = TvType.Live
                ) { this.posterUrl = posterUrl }
            }
    }

    override suspend fun load(url: String): LoadResponse {
        val data = parseJson<PlayFyLiveEventLoadData>(url)

        val eventInfo = data.eventInfo
        val plot = buildString {
            eventInfo?.let { info ->
                info.eventType?.let { append("📌 $it\n") }
                info.eventName?.let { append("🏆 $it\n") }
                info.startTime?.let {
                    try {
                        val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
                        val displayFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.US)
                        val date = dateFormat.parse(it)
                        date?.let { d -> append("🕐 ${displayFormat.format(d)}\n") }
                    } catch (e: Exception) {
                        append("🕐 $it\n")
                    }
                }
            }
            append("\n📡 Available Servers: ${data.formats.size}")
        }

        return newLiveStreamLoadResponse(name = data.title, url = url, dataUrl = url) {
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
        val loadData = parseJson<PlayFyLiveEventLoadData>(data)

        val streamResponse = fetchChannelStreams(loadData.slug)
        val streams = streamResponse?.streamUrls ?: emptyList()

        if (streams.isEmpty()) {
            for (format in loadData.formats) {
                val serverName = format.title ?: "Server"
                val streamUrl = format.webLink ?: continue
                if (streamUrl.isBlank()) continue
                emitStreamLink(streamUrl, serverName, callback)
            }
            return true
        }

        for (stream in streams) {
            val serverName = stream.name ?: "Server"
            val streamLink = stream.streamUrl

            if (stream.tokenApi != null && stream.tokenApi.isNotBlank()) {
                try {
                    val tokenConfig = parseJson<TokenApiConfig>(stream.tokenApi)
                    val resolvedUrl = fetchStreamFromTokenApi(tokenConfig)
                    if (!resolvedUrl.isNullOrBlank()) {
                        val (url, parsedHeaders) = parseStreamLink(resolvedUrl)
                        val allHeaders = (stream.headers ?: emptyMap()).toMutableMap()
                        allHeaders.putAll(parsedHeaders)
                        emitStreamLink(url, serverName, callback, allHeaders, stream.drm)
                    }
                } catch (e: Exception) {
                    println("PlayFy: Failed to resolve tokenApi for $serverName: ${e.message}")
                }
            } else if (!streamLink.isNullOrBlank()) {
                val (url, parsedHeaders) = parseStreamLink(streamLink)
                val allHeaders = (stream.headers ?: emptyMap()).toMutableMap()
                allHeaders.putAll(parsedHeaders)
                emitStreamLink(url, serverName, callback, allHeaders, stream.drm)
            }
        }

        return true
    }

    private suspend fun emitStreamLink(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit,
        headers: Map<String, String> = emptyMap(),
        drm: String? = null
    ) {
        if (url.isBlank()) return
        val linkType = when {
            url.contains(".mpd") -> ExtractorLinkType.DASH
            url.contains(".m3u8") -> ExtractorLinkType.M3U8
            else -> ExtractorLinkType.M3U8
        }
        val finalHeaders = headers.toMutableMap()
        if (!finalHeaders.containsKey("User-Agent")) {
            finalHeaders["User-Agent"] = "Mozilla/5.0 (Linux; Android 10; Pixel 3 XL) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
        }
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = name,
                url = url,
                type = linkType
            ) {
                this.quality = Qualities.Unknown.value
                if (finalHeaders.isNotEmpty()) {
                    this.headers = finalHeaders
                }
            }
        )
    }

    private suspend fun fetchChannelStreams(slug: String): PlayFyChannelStreamResponse? {
        return withContext(Dispatchers.IO) {
            try {
                val webUrl = getWebUrl()
                val url = "$webUrl/$slug.txt"
                println("PlayFy: Fetching channel streams from $url")

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    println("PlayFy: HTTP ${response.code} fetching channel $slug")
                    return@withContext null
                }

                val encrypted = response.body.string()
                if (encrypted.isBlank()) return@withContext null

                val decrypted = PlayFyCryptoUtils.decrypt(encrypted.trim())
                if (decrypted.isNullOrBlank()) {
                    println("PlayFy: Decryption failed for channel $slug")
                    return@withContext null
                }

                val rawList = parseJson<List<Map<String, Any>>>(decrypted)
                val streamUrls = mutableListOf<PlayFyStreamEntry>()

                for (item in rawList) {
                    when {
                        item.containsKey("channel") -> {
                            val channelStr = item["channel"] as? String
                            if (!channelStr.isNullOrBlank()) {
                                try {
                                    val streamUrl = parseJson<PlayFyStreamEntry>(channelStr)
                                    streamUrls.add(streamUrl)
                                } catch (e: Exception) {
                                    println("PlayFy: Failed to parse channel stream: ${e.message}")
                                }
                            }
                        }
                        item.containsKey("stream") -> {
                            val streamStr = item["stream"] as? String
                            if (!streamStr.isNullOrBlank()) {
                                try {
                                    val streamUrl = parseJson<PlayFyStreamEntry>(streamStr)
                                    streamUrls.add(streamUrl)
                                } catch (e: Exception) {
                                    println("PlayFy: Failed to parse stream: ${e.message}")
                                }
                            }
                        }
                        else -> {
                            try {
                                val streamUrl = parseJson<PlayFyStreamEntry>(item.toJson())
                                streamUrls.add(streamUrl)
                            } catch (e: Exception) {
                                println("PlayFy: Failed to parse item as stream: ${e.message}")
                            }
                        }
                    }
                }

                PlayFyChannelStreamResponse(streamUrls, null, null, null)
            } catch (e: Exception) {
                println("PlayFy: Exception in fetchChannelStreams: ${e.message}")
                e.printStackTrace()
                null
            }
        }
    }

    data class TokenApiConfig(
        val url: String?,
        val api: String?,
        val type: String?,
        val link_key: String?,
        val default_string: String?,
        val request_type: String?,
        val request_body_type: String?,
        val ip_api: String?
    )

    private suspend fun fetchStreamFromTokenApi(config: TokenApiConfig): String? {
        return withContext(Dispatchers.IO) {
            try {
                println("PlayFy: Fetching stream from tokenApi type=${config.type}")

                return@withContext when (config.type?.lowercase()) {
                    "embed" -> handleEmbedExtraction(config)
                    "json", "sp" -> handleJsonExtraction(config)
                    "html" -> handleHtmlExtraction(config)
                    "yt" -> handleYoutubeExtraction(config)
                    "ls" -> handleLocationServiceExtraction(config)
                    else -> handleDirectApiCall(config)
                }
            } catch (e: Exception) {
                println("PlayFy: Exception in fetchStreamFromTokenApi: ${e.message}")
                e.printStackTrace()
                null
            }
        }
    }

    private suspend fun handleEmbedExtraction(config: TokenApiConfig): String? {
        return withContext(Dispatchers.IO) {
            try {
                val embedUrl = config.url?.takeIf { it.isNotBlank() } ?: config.api?.takeIf { it.isNotBlank() }
                if (embedUrl.isNullOrBlank()) {
                    return@withContext null
                }

                if (embedUrl.contains(".m3u8") || embedUrl.contains(".mpd") ||
                    embedUrl.contains(".mp4") || embedUrl.contains(".ts") ||
                    embedUrl.contains(".mkv") || embedUrl.contains(".webm")) {
                    println("PlayFy: Embed URL is already a stream: $embedUrl")
                    return@withContext embedUrl
                }

                return@withContext loadEmbedInWebView(embedUrl, config)
            } catch (e: Exception) {
                println("PlayFy: Exception in handleEmbedExtraction: ${e.message}")
                e.printStackTrace()
                null
            }
        }
    }

    private suspend fun loadEmbedInWebView(embedUrl: String, config: TokenApiConfig): String? {
        return withContext(Dispatchers.Main) {
            suspendCoroutine { continuation ->
                try {
                    val context = PlayFyLiveEvents.context
                    if (context == null) {
                        println("PlayFy: No context available for WebView")
                        continuation.resume(null)
                        return@suspendCoroutine
                    }

                    val webView = WebView(context)
                    val settings = webView.settings

                    settings.javaScriptEnabled = true
                    settings.loadsImagesAutomatically = true
                    settings.domStorageEnabled = true
                    settings.allowContentAccess = true
                    settings.allowFileAccess = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.mediaPlaybackRequiresUserGesture = false

                    var urlCaptured = false
                    var capturedUrl: String? = null

                    val bridge = object {
                        @android.webkit.JavascriptInterface
                        fun onStreamUrlFound(url: String) {
                            println("PlayFy: JavaScript bridge received stream URL: $url")
                            if (!urlCaptured && url.isNotBlank()) {
                                urlCaptured = true
                                capturedUrl = url
                                Handler(Looper.getMainLooper()).post {
                                    try {
                                        webView.destroy()
                                    } catch (e: Exception) {
                                    }
                                    continuation.resume(url)
                                }
                            }
                        }
                    }

                    webView.addJavascriptInterface(bridge, "StreamBridge")

                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                            val url = request.url.toString()

                            if (url.contains(".m3u8") || url.contains(".mpd") || url.contains(".mp4") ||
                                url.contains(".ts") || url.contains(".mkv") || url.contains(".webm")) {
                                println("PlayFy: Intercepted streaming URL from WebView: $url")
                                if (!urlCaptured) {
                                    urlCaptured = true
                                    capturedUrl = url
                                    Handler(Looper.getMainLooper()).post {
                                        try {
                                            webView.destroy()
                                        } catch (e: Exception) {
                                        }
                                        continuation.resume(url)
                                    }
                                }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView, pageUrl: String) {
                            super.onPageFinished(view, pageUrl)
                            println("PlayFy: WebView page finished loading: $pageUrl")

                            if (!urlCaptured) {
                                Handler(Looper.getMainLooper()).postDelayed({
                                    println("PlayFy: Injecting JavaScript to extract stream URL")
                                    try {
                                        val jsCode = """
                                            (function() {
                                                if (typeof playbackURL !== 'undefined' && playbackURL) {
                                                    window.StreamBridge.onStreamUrlFound(playbackURL);
                                                }
                                            })();
                                        """.trimIndent()
                                        webView.evaluateJavascript(jsCode, null)
                                    } catch (e: Exception) {
                                        println("PlayFy: Error injecting JavaScript: ${e.message}")
                                    }
                                }, 500)
                            }

                            if (!urlCaptured) {
                                Handler(Looper.getMainLooper()).postDelayed({
                                    if (!urlCaptured) {
                                        println("PlayFy: No streaming URL found after page load timeout")
                                        try {
                                            webView.destroy()
                                        } catch (e: Exception) {
                                        }
                                        continuation.resume(null)
                                    }
                                }, 3000)
                            }
                        }
                    }

                    webView.webChromeClient = WebChromeClient()

                    println("PlayFy: Loading embed in WebView")
                    println("PlayFy: Loading URL: $embedUrl")
                    webView.loadUrl(embedUrl)

                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!urlCaptured && capturedUrl == null) {
                            println("PlayFy: Embed WebView extraction timeout after 30s")
                            try {
                                webView.destroy()
                            } catch (e: Exception) {
                            }
                            try {
                                continuation.resume(null)
                            } catch (e: Exception) {
                            }
                        }
                    }, 30000)
                } catch (e: Exception) {
                    println("PlayFy: Exception in loadEmbedInWebView: ${e.message}")
                    e.printStackTrace()
                    continuation.resume(null)
                }
            }
        }
    }

    private suspend fun handleJsonExtraction(config: TokenApiConfig): String? {
        return withContext(Dispatchers.IO) {
            try {
                val apiUrl = config.api ?: return@withContext null
                println("PlayFy: Fetching JSON stream from: $apiUrl")

                val request = Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseBody = response.body.string()

                    if (!config.link_key.isNullOrBlank()) {
                        try {
                            val json = parseJson<Map<String, Any>>(responseBody)
                            val streamUrl = json[config.link_key] as? String
                            if (!streamUrl.isNullOrBlank()) {
                                println("PlayFy: Extracted JSON stream URL: $streamUrl")
                                return@withContext streamUrl
                            }
                        } catch (e: Exception) {
                            println("PlayFy: Failed to parse JSON response: ${e.message}")
                        }
                    }

                    return@withContext responseBody.trim()
                }
            } catch (e: Exception) {
                println("PlayFy: Exception in handleJsonExtraction: ${e.message}")
            }
            null
        }
    }

    private suspend fun handleHtmlExtraction(config: TokenApiConfig): String? {
        return withContext(Dispatchers.IO) {
            try {
                val apiUrl = config.api ?: return@withContext null
                println("PlayFy: Fetching HTML stream from: $apiUrl")

                val request = Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseBody = response.body.string()

                    val patterns = listOf(
                        "player\\.load\\(\\{[^}]*source:\\s*\"([^\"]+)\"",
                        "src\\s*=\\s*['\"]([^'\"]*\\.m3u8[^'\"]*)['\"]",
                        "url\\s*:\\s*['\"]([^'\"]*\\.mpd[^'\"]*)['\"]"
                    )

                    for (pattern in patterns) {
                        val regex = Regex(pattern)
                        val match = regex.find(responseBody)
                        if (match != null) {
                            val url = match.groupValues[1]
                            println("PlayFy: Extracted HTML stream URL: $url")
                            return@withContext url
                        }
                    }

                    println("PlayFy: No streaming URL found in HTML response")
                }
            } catch (e: Exception) {
                println("PlayFy: Exception in handleHtmlExtraction: ${e.message}")
            }
            null
        }
    }

    private suspend fun handleYoutubeExtraction(config: TokenApiConfig): String? {
        return withContext(Dispatchers.IO) {
            try {
                val apiUrl = config.url ?: config.api ?: return@withContext null
                println("PlayFy: YouTube URL for extraction: $apiUrl")
                return@withContext apiUrl
            } catch (e: Exception) {
                println("PlayFy: Exception in handleYoutubeExtraction: ${e.message}")
            }
            null
        }
    }

    private suspend fun handleLocationServiceExtraction(config: TokenApiConfig): String? {
        return withContext(Dispatchers.IO) {
            try {
                val ipApiUrl = config.ip_api?.let {
                    if (it.startsWith("aHR0")) {
                        String(Base64.decode(it, Base64.DEFAULT))
                    } else {
                        it
                    }
                } ?: "https://ip-api.streamingucms.com/"

                println("PlayFy: Resolving location service from: $ipApiUrl")

                val request = Request.Builder()
                    .url(ipApiUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseBody = response.body.string()

                    if (!config.link_key.isNullOrBlank()) {
                        try {
                            val json = parseJson<Map<String, Any>>(responseBody)
                            val streamUrl = json[config.link_key] as? String
                            if (!streamUrl.isNullOrBlank()) {
                                println("PlayFy: Extracted location-based stream URL: $streamUrl")
                                return@withContext streamUrl
                            }
                        } catch (e: Exception) {
                        }
                    }

                    return@withContext responseBody.trim()
                }
            } catch (e: Exception) {
                println("PlayFy: Exception in handleLocationServiceExtraction: ${e.message}")
            }
            null
        }
    }

    private suspend fun handleDirectApiCall(config: TokenApiConfig): String? {
        return withContext(Dispatchers.IO) {
            try {
                val apiUrl = config.api ?: return@withContext null
                println("PlayFy: Direct API call to: $apiUrl")

                val request = Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseBody = response.body.string()

                    if (!config.link_key.isNullOrBlank()) {
                        try {
                            val json = parseJson<Map<String, Any>>(responseBody)
                            val streamUrl = json[config.link_key] as? String
                            if (!streamUrl.isNullOrBlank()) {
                                println("PlayFy: Extracted stream URL from API: $streamUrl")
                                return@withContext streamUrl
                            }
                        } catch (e: Exception) {
                        }
                    }

                    return@withContext responseBody.trim()
                }
            } catch (e: Exception) {
                println("PlayFy: Exception in handleDirectApiCall: ${e.message}")
            }
            null
        }
    }

    private fun parseStreamLink(link: String): Pair<String, Map<String, String>> {
        val headers = mutableMapOf<String, String>()

        if (!link.contains("|")) {
            return Pair(link, headers)
        }

        val parts = link.split("|", limit = 2)
        val url = parts[0]

        if (parts.size > 1) {
            val headerPart = parts[1]
            headerPart.split("&").forEach { headerPair ->
                val keyValue = headerPair.split("=", limit = 2)
                if (keyValue.size == 2) {
                    val key = keyValue[0].trim()
                    val value = keyValue[1].trim()
                    val headerName = when (key.lowercase()) {
                        "user-agent" -> "User-Agent"
                        "referer" -> "Referer"
                        "origin" -> "Origin"
                        "cookie" -> "Cookie"
                        else -> key
                    }
                    headers[headerName] = value
                }
            }
        }

        return Pair(url, headers)
    }
}
