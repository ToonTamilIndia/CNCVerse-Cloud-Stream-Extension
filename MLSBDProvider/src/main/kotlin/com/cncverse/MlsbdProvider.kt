package com.cncverse

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONTokener
import kotlin.coroutines.resume

class MlsbdProvider : MainAPI() {
    companion object {
        var appContext: Context? = null
    }

    override var mainUrl = "https://mlsbd.co"
    override var name = "Mlsbd"
    override var lang = "bn"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val mainPage = mainPageOf(
        "" to "Latest Movies",
        "/category/bangla-dubbed/page/" to "Bangla Dubbed",
        "/category/dual-audio-movies/page/" to "Multi Audio Movies",
        "/category/tv-series/page/" to "TV Series",
        "/category/bollywood-movies/page/" to "Bollywood Movies",
        "/category/bangla-movies/page/" to "Bengali Movies",
        "/category/hollywood-movies/page/" to "Hollywood Movies"
    )
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.AnimeMovie,
    )
    private val headers = mapOf(
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
    )

    // Upstream uses a mobile UA for the Cloudflare-aware fetch path.
    private val defaultUserAgent =
        "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private fun buildHeaders(cfClearance: String?): Map<String, String> {
        val map = mutableMapOf(
            "User-Agent" to defaultUserAgent,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9,bn;q=0.8"
        )
        if (!cfClearance.isNullOrEmpty()) map["Cookie"] = "cf_clearance=$cfClearance"
        return map
    }

    private fun isCloudflare(html: String, statusCode: Int): Boolean {
        if (statusCode == 403 || statusCode == 503) return true
        if (html.length >= 6000) return false
        val lower = html.lowercase()
        return lower.contains("cf-browser-verification") ||
                lower.contains("checking if the site connection is secure") ||
                lower.contains("just a moment") ||
                lower.contains("attention required! | cloudflare") ||
                lower.contains("enable javascript and cookies to continue")
    }

    /**
     * Ported from upstream `fetchHtml`: performs a normal request with a stored
     * `cf_clearance` cookie and falls back to the captcha WebView when Cloudflare
     * returns a challenge instead of the page.
     */
    private suspend fun fetchHtml(url: String, cacheTime: Int = 0, timeout: Long = 60): Document? {
        try {
            val response = app.get(
                url,
                headers = buildHeaders(MlsbdStorage.getCfCookie().first),
                cacheTime = cacheTime,
                allowRedirects = true,
                timeout = timeout
            )
            if (!isCloudflare(response.text, response.code)) {
                return Jsoup.parse(response.text, url)
            }
        } catch (_: Exception) {
        }

        // Challenge (or transient network error): clear any stale cookie and solve it.
        MlsbdStorage.clearCfCookie()
        fetchViaWebView(url)?.let { return it }

        return try {
            val response = app.get(
                url,
                headers = buildHeaders(MlsbdStorage.getCfCookie().first),
                timeout = timeout,
                allowRedirects = true
            )
            if (!isCloudflare(response.text, response.code)) Jsoup.parse(response.text, url) else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Ported from upstream `fetchViaWebView`: shows a WebView so the user can solve
     * the Cloudflare captcha (touch or D-pad), persists `cf_clearance`, then returns
     * the fully rendered page.
     */
    private suspend fun fetchViaWebView(url: String): Document? {
        val ctx = appContext ?: return null
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                try {
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    val webView = WebView(ctx)
                    cookieManager.setAcceptThirdPartyCookies(webView, true)
                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        userAgentString = defaultUserAgent
                        mediaPlaybackRequiresUserGesture = false
                    }
                    webView.webChromeClient = WebChromeClient()

                    val density = ctx.resources.displayMetrics.density
                    val pad = (10 * density).toInt()

                    val wrapper = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                    val hint = TextView(ctx).apply {
                        text = "🔐 Solve the Cloudflare captcha to continue."
                        setTextColor(Color.WHITE)
                        setBackgroundColor(Color.parseColor("#1A1A2E"))
                        textSize = 13f
                        setPadding(pad, pad, pad, pad)
                    }
                    val container = FrameLayout(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
                        )
                        isFocusable = true
                        isFocusableInTouchMode = true
                    }
                    webView.layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    container.addView(webView)
                    wrapper.addView(hint)
                    wrapper.addView(container)

                    var resolved = false
                    val dialog = AlertDialog.Builder(ctx).setView(wrapper).create()

                    fun finish(doc: Document?) {
                        if (resolved) return
                        resolved = true
                        try {
                            dialog.dismiss()
                        } catch (_: Exception) {
                        }
                        try {
                            webView.destroy()
                        } catch (_: Exception) {
                        }
                        if (cont.isActive) cont.resume(doc)
                    }

                    fun checkAndFinish() {
                        if (resolved) return
                        val cookies = cookieManager.getCookie(url).orEmpty()
                        Regex("cf_clearance=([^;]+)").find(cookies)
                            ?.groupValues?.getOrNull(1)
                            ?.let { MlsbdStorage.saveCfCookie(it) }

                        webView.evaluateJavascript("document.documentElement.outerHTML") { raw ->
                            val html = try {
                                JSONTokener(raw ?: "").nextValue() as? String
                            } catch (_: Exception) {
                                raw
                            }
                            when {
                                html.isNullOrBlank() -> finish(null)
                                isCloudflare(html, 200) ->
                                    Handler(Looper.getMainLooper())
                                        .postDelayed({ checkAndFinish() }, 2000L)
                                else -> finish(Jsoup.parse(html, url))
                            }
                        }
                    }

                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, pageUrl: String) {
                            super.onPageFinished(view, pageUrl)
                            view.postDelayed({ checkAndFinish() }, 1500L)
                        }
                    }

                    dialog.setOnDismissListener { if (!resolved) finish(null) }
                    dialog.show()
                    webView.loadUrl(url)
                    container.requestFocus()

                    // Never block link resolution forever.
                    Handler(Looper.getMainLooper()).postDelayed({ finish(null) }, 120_000L)
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (request.data == "") mainUrl
        else "$mainUrl${request.data}$page/"
        val doc = fetchHtml(url, cacheTime = 1440)
            ?: return newHomePageResponse(
                HomePageList(request.name, emptyList(), isHorizontalImages = true),
                false
            )
        val homeResponse = doc.select("div.single-post")
        val home = homeResponse.mapNotNull { post -> toResult(post) }
        return newHomePageResponse(HomePageList(request.name, home, isHorizontalImages = true), true)
    }

    private fun toResult(post: Element): SearchResponse {
        val title = post.select(".post-title").text()
        val url = post.select(".thumb > a").attr("href")
        val posterEl = post.select(".thumb img").first()
        val poster = posterEl?.attr("src") ?: posterEl?.attr("data-src") ?: ""
        return newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = fetchHtml("$mainUrl/?s=$query") ?: return emptyList()
        val searchResponse = doc.select("div.single-post")
        return searchResponse.mapNotNull { post -> toResult(post) }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = fetchHtml(url)
            ?: return newMovieLoadResponse("", url, TvType.Movie, "") { }
        val title = doc.select(".name").text()
        val year = "(?<=\\()\\d{4}(?=\\))".toRegex().find(title)?.value?.toIntOrNull()
        val image = doc.select("img.aligncenter").attr("src")
        doc.select("br").append("\\n")
        val plot = doc.select(".single-post-title").text() + "\n" +
                doc.select(".misc").text() + "\n" +
                doc.select(".details").text().replace("\\n ", "\n") + "\n" +
                doc.select(".storyline").text() + "\n" +
                doc.select(".production").text().replace("\\n ", "\n") + "\n" +
                doc.select(".media").text().replace("\\n ", "\n")

        val episodeDivs = doc.select("div.post-section-title.download")
        var link = ""
        when (episodeDivs.size) {
            1 -> {
                // collect consecutive <p> siblings after the download title and gather sd/hd/hevc links
                var sib = episodeDivs[0].nextElementSibling()
                val links = mutableListOf<String>()
                while (sib != null && sib.tagName() == "p") {
                    val aElements = sib.select("a")
                    for (a in aElements) {
                        val classes = a.classNames()
                        if (classes.contains("sd") || classes.contains("hd") || classes.contains("hevc")) {
                            val href = a.attr("href")?.trim()
                            if (!href.isNullOrEmpty()) {
                                var quality = "Unknown"
                                val text = a.text().lowercase()
                                if (text.contains("1080")) quality = "1080p"
                                else if (text.contains("720")) quality = "720p"
                                else if (text.contains("480")) quality = "480p"
                                else if (text.contains("4k") || text.contains("2160")) quality = "4K"
                                links.add("$quality|$href")
                            }
                        }
                    }
                    sib = sib.nextElementSibling()
                }
                link = links.joinToString(" ; ")
                return newMovieLoadResponse(title, url, TvType.Movie, link) {
                    this.posterUrl = image
                    this.year = year
                    this.plot = plot
                }
            }
            0 -> return newMovieLoadResponse(title, url, TvType.Movie, "") {
                this.posterUrl = image
                this.year = year
                this.plot = plot
            }
            else -> {
                val episodesData = mutableListOf<Episode>()
                // Each download section may represent a range of episodes (e.g. "Epi-89-96").
                // For each section, parse the range and create episodes with the same set of quality links.
                for (episodeDiv in episodeDivs) {
                    val sectionText = episodeDiv.text()
                    // find episode range like Epi-89-96 or Epi-89
                    val rangeRegex = "Epi-?\\s*(\\d+)(?:-(\\d+))?".toRegex(RegexOption.IGNORE_CASE)
                    val match = rangeRegex.find(sectionText)
                    val start = match?.groups?.get(1)?.value?.toIntOrNull() ?: 0
                    val end = match?.groups?.get(2)?.value?.toIntOrNull() ?: start

                    // gather quality links from consecutive <p> siblings
                    var sib = episodeDiv.nextElementSibling()
                    val qlinks = mutableListOf<String>()
                    while (sib != null && sib.tagName() == "p") {
                        val aElements = sib.select("a")
                        for (a in aElements) {
                            val classes = a.classNames()
                            if (classes.contains("sd") || classes.contains("hd") || classes.contains("hevc")) {
                                val href = a.attr("href")?.trim()
                                if (!href.isNullOrEmpty()) {
                                    var quality = "Unknown"
                                    val text = a.text().lowercase()
                                    if (text.contains("1080")) quality = "1080p"
                                    else if (text.contains("720")) quality = "720p"
                                    else if (text.contains("480")) quality = "480p"
                                    else if (text.contains("4k") || text.contains("2160")) quality = "4K"
                                    qlinks.add("$quality|$href")
                                }
                            }
                        }
                        sib = sib.nextElementSibling()
                    }

                    if (qlinks.isEmpty()) continue

                    val episodeUrl = qlinks.joinToString(" ; ")
                    val actualStart = if (start <= 0) 1 else start
                    val actualEnd = if (end <= 0) actualStart else end
                    for (epNum in actualStart..actualEnd) {
                        episodesData.add(
                            newEpisode(episodeUrl) {
                                this.name = "Episode $epNum"
                                this.season = 1
                                this.episode = epNum
                            }
                        )
                    }
                }
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesData) {
                    this.posterUrl = image
                    this.year = year
                    this.plot = plot
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        data.split(" ; ").forEach { item ->
            val parts = item.split("|")
            val qualityLabel = if (parts.size > 1) parts[0] else ""
            val link = (if (parts.size > 1) parts[1] else item).trim()
            if (link.isEmpty()) return@forEach
            if (link.isSocialOrAuthLink()) return@forEach
            if (link.contains("savelinks")) {
                val doc = fetchHtml(link) ?: return@forEach
                // Links are inside <ul><li><a href="..."> on savelinks.me
                doc.select("ul li a[href^=http]").forEach {
                    val url = it.attr("href").trim()
                    if (url.isNotEmpty()) resolveLink(url, qualityLabel, subtitleCallback, callback)
                }
            } else {
                resolveLink(link, qualityLabel, subtitleCallback, callback)
            }
        }
        return true
    }

    private suspend fun resolveLink(
        link: String,
        qualityLabel: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (link.isSocialOrAuthLink()) return
        if (link.contains("pixeldrain.com", ignoreCase = true)) {
            val id = link.substringAfterLast("/").substringBefore("?")
            val direct = if (link.contains("download", ignoreCase = true)) link
            else "https://pixeldrain.com/api/file/$id?download"
            callback(
                newExtractorLink(
                    "Pixeldrain",
                    "Pixeldrain [$qualityLabel]",
                    direct,
                    INFER_TYPE
                ) {
                    this.quality = getIndexQuality(qualityLabel)
                }
            )
            return
        }
        loadExtractor(link, link, subtitleCallback, callback)
    }

    private fun String.isSocialOrAuthLink(): Boolean {
        val socialHosts = listOf(
            "t.me", "telegram", "whatsapp", "facebook", "twitter",
            "savelinks.me/login", "savelinks.me/register"
        )
        return socialHosts.any { contains(it, ignoreCase = true) }
    }



}
