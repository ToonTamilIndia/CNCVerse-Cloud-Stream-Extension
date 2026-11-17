package com.cncverse

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Point
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.lagradost.api.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.coroutines.resume

class MlsbdProvider : MainAPI() {
    companion object {
        var appContext: Context? = null
        private val cfMutex = Mutex()
    }

    override var mainUrl = "https://mlsbd.co"
    override var name = "Mlsbd"
    override var lang = "bn"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val mainPage = mainPageOf(
        "" to "Latest Movies",
        "category/bangla-dubbed" to "Bangla Dubbed",
        "category/dual-audio-movies" to "Multi Audio Movies",
        "category/tv-series" to "TV Series",
        "category/bollywood-movies" to "Bollywood Movies",
        "category/bangla-movies" to "Bengali Movies",
        "category/hollywood-movies" to "Hollywood Movies"
    )
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.AnimeMovie,
    )

    private val defaultUserAgent =
        "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private fun unescapeJsString(raw: String): String {
        if (!raw.startsWith("\"") || !raw.endsWith("\"")) return raw
        return try {
            JSONTokener(raw).nextValue().toString()
        } catch (e: Exception) {
            raw.removeSurrounding("\"")
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\\\", "\\")
        }
    }

    // Plain requests reuse a solved cf_clearance; only a real challenge falls through to the captcha WebView
    private suspend fun fetchHtml(url: String): Document {
        fun buildHeaders(cfClearance: String? = null): Map<String, String> {
            val map = mutableMapOf(
                "User-Agent" to defaultUserAgent,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9,bn;q=0.8"
            )
            if (!cfClearance.isNullOrEmpty()) map["Cookie"] = "cf_clearance=$cfClearance"
            return map
        }

        fun isCloudflare(html: String, statusCode: Int): Boolean {
            if (statusCode == 403 || statusCode == 503) return true
            if (html.length >= 6000) return false
            return html.contains("cf-browser-verification", true) ||
                    html.contains("Checking if the site connection is secure", true) ||
                    html.contains("Just a moment", true) ||
                    html.contains("Attention Required! | Cloudflare", true) ||
                    html.contains("Enable JavaScript and cookies to continue", true)
        }

        val cookieTtl = 23 * 60 * 60 * 1000L
        val (savedCf, savedCfTs) = MlsbdStorage.getCfCookie()
        val hasValidCache = !savedCf.isNullOrEmpty() && System.currentTimeMillis() - savedCfTs < cookieTtl

        for (attempt in 0 until 6) {
            val response = try {
                app.get(url, headers = buildHeaders(if (hasValidCache) savedCf else null), timeout = 15L)
            } catch (e: Exception) {
                null
            }
            if (response != null && response.code == 200 && !isCloudflare(response.text, response.code)) {
                return response.document
            }
            if (response != null) break
            delay((attempt + 1) * 500L)
        }

        return cfMutex.withLock {
            val (freshCf, freshCfTs) = MlsbdStorage.getCfCookie()
            val hasFreshCache = !freshCf.isNullOrEmpty() && System.currentTimeMillis() - freshCfTs < cookieTtl
            if (hasFreshCache) {
                val response = try {
                    app.get(url, headers = buildHeaders(freshCf), timeout = 30L)
                } catch (e: Exception) {
                    null
                }
                if (response != null && response.code == 200 && !isCloudflare(response.text, response.code)) {
                    return@withLock response.document
                }
            }

            MlsbdStorage.clearCfCookie()
            val wvDoc = fetchViaWebView(url)
            if (wvDoc != null && !isCloudflare(wvDoc.html(), 200)) return@withLock wvDoc

            val newCf = MlsbdStorage.getCfCookie().first
            try {
                app.get(url, headers = buildHeaders(newCf), timeout = 30L).document
            } catch (e: Exception) {
                Jsoup.parse("")
            }
        }
    }

    // Loads the page in a hidden WebView and only shows it (with a D-pad cursor on TV) when Cloudflare asks for a captcha
    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private suspend fun fetchViaWebView(url: String): Document? {
        val ctx = appContext ?: return null
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                try {
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    val wv = WebView(ctx)
                    cookieManager.setAcceptThirdPartyCookies(wv, true)
                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        userAgentString = defaultUserAgent
                        mediaPlaybackRequiresUserGesture = false
                    }
                    wv.webChromeClient = WebChromeClient()

                    var resolved = false
                    var dialog: AlertDialog? = null
                    val dp = ctx.resources.displayMetrics.density
                    val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    val metrics = Point()
                    @Suppress("DEPRECATION")
                    wm.defaultDisplay.getSize(metrics)
                    val params = WindowManager.LayoutParams((metrics.x * 0.95f).toInt(), (metrics.y * 0.9f).toInt())

                    val wrapper = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        minimumWidth = (metrics.x * 0.95f).toInt()
                        minimumHeight = (metrics.y * 0.9f).toInt()
                    }
                    val hint = TextView(ctx).apply {
                        text = "🔐 Solve the Cloudflare captcha — use D-pad to move cursor, OK to click."
                        setTextColor(Color.WHITE)
                        setBackgroundColor(Color.parseColor("#1A1A2E"))
                        textSize = 13f
                        val p = (10 * dp).toInt()
                        setPadding(p, p, p, p)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    }

                    val isTv = isLayout(TV)
                    val cursorSize = (22 * dp).toInt()
                    val cursorView = if (isTv) {
                        View(ctx).apply {
                            layoutParams = FrameLayout.LayoutParams(cursorSize, cursorSize)
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(Color.argb(160, 255, 50, 50))
                                setStroke((2 * dp).toInt(), Color.WHITE)
                            }
                            elevation = 999f
                        }
                    } else null

                    val container = FrameLayout(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.MATCH_PARENT
                        ).apply { weight = 1f }
                        isFocusable = true
                        isFocusableInTouchMode = true
                    }
                    wv.layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    container.addView(wv)

                    if (cursorView != null) {
                        container.addView(cursorView)
                        var cursorX = metrics.x * 0.95f / 2f
                        var cursorY = metrics.y * 0.9f / 2f

                        fun moveCursor(dx: Float, dy: Float) {
                            cursorX = (cursorX + dx).coerceIn(0f, container.width.toFloat())
                            cursorY = (cursorY + dy).coerceIn(0f, container.height.toFloat())
                            cursorView.translationX = cursorX - cursorSize / 2f
                            cursorView.translationY = cursorY - cursorSize / 2f
                        }

                        fun clickAtCursor() {
                            val t = SystemClock.uptimeMillis()
                            val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, cursorX, cursorY, 0)
                            val up = MotionEvent.obtain(t, t + 120, MotionEvent.ACTION_UP, cursorX, cursorY, 0)
                            wv.dispatchTouchEvent(down)
                            wv.dispatchTouchEvent(up)
                            down.recycle()
                            up.recycle()
                        }

                        container.viewTreeObserver.addOnGlobalLayoutListener(object :
                            ViewTreeObserver.OnGlobalLayoutListener {
                            override fun onGlobalLayout() {
                                container.viewTreeObserver.removeOnGlobalLayoutListener(this)
                                cursorX = container.width / 2f
                                cursorY = container.height / 2f
                                cursorView.translationX = cursorX - cursorSize / 2f
                                cursorView.translationY = cursorY - cursorSize / 2f
                            }
                        })
                        container.setOnKeyListener { _, keyCode, event ->
                            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                            val step = dp * 10f
                            when (keyCode) {
                                KeyEvent.KEYCODE_DPAD_UP -> { moveCursor(0f, -step); true }
                                KeyEvent.KEYCODE_DPAD_DOWN -> { moveCursor(0f, step); true }
                                KeyEvent.KEYCODE_DPAD_LEFT -> { moveCursor(-step, 0f); true }
                                KeyEvent.KEYCODE_DPAD_RIGHT -> { moveCursor(step, 0f); true }
                                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { clickAtCursor(); true }
                                else -> false
                            }
                        }
                    }
                    wrapper.addView(hint)
                    wrapper.addView(container)

                    fun showDialogIfNeeded() {
                        if (dialog != null || resolved) return
                        dialog = AlertDialog.Builder(ctx).setView(wrapper).setCancelable(true).create()
                        dialog?.window?.apply {
                            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                            setLayout(params.width, params.height)
                        }
                        dialog?.setOnDismissListener {
                            if (!resolved) {
                                resolved = true
                                try {
                                    wv.destroy()
                                } catch (_: Exception) {
                                }
                                if (cont.isActive) cont.resume(null)
                            }
                        }
                        dialog?.show()
                    }

                    fun checkAndFinish(pageUrl: String) {
                        if (resolved) return
                        val title = wv.title ?: ""
                        val isCf = title.contains("Just a moment", true) ||
                                pageUrl.contains("challenge-platform", true) ||
                                title.contains("Cloudflare", true)
                        if (isCf) {
                            showDialogIfNeeded()
                            return
                        }
                        val cookies = cookieManager.getCookie(url) ?: ""
                        val cf = Regex("cf_clearance=([^;]+)").find(cookies)?.groupValues?.get(1)
                        if (!cf.isNullOrEmpty()) MlsbdStorage.saveCfCookie(cf)
                        wv.evaluateJavascript("document.documentElement.outerHTML") { rawHtml ->
                            if (resolved) return@evaluateJavascript
                            val unescaped = unescapeJsString(rawHtml ?: "")
                            if (unescaped.isNotEmpty() && !unescaped.contains("Just a moment", true)) {
                                resolved = true
                                try {
                                    dialog?.dismiss()
                                } catch (_: Exception) {
                                }
                                try {
                                    wv.destroy()
                                } catch (_: Exception) {
                                }
                                if (cont.isActive) cont.resume(Jsoup.parse(unescaped, url))
                            }
                        }
                    }

                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, pageUrl: String) {
                            super.onPageFinished(view, pageUrl)
                            checkAndFinish(pageUrl)
                            if (!resolved) {
                                Handler(Looper.getMainLooper()).postDelayed(object : Runnable {
                                    override fun run() {
                                        if (resolved) return
                                        checkAndFinish(wv.url ?: pageUrl)
                                        if (!resolved) Handler(Looper.getMainLooper()).postDelayed(this, 1000L)
                                    }
                                }, 1000L)
                            }
                        }
                    }

                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!resolved) {
                            resolved = true
                            try {
                                dialog?.dismiss()
                            } catch (_: Exception) {
                            }
                            try {
                                wv.destroy()
                            } catch (_: Exception) {
                            }
                            if (cont.isActive) cont.resume(null)
                        }
                    }, 120_000L)
                    wv.loadUrl(url)
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (request.data.isBlank()) {
            if (page <= 1) "$mainUrl/" else "$mainUrl/page/$page/"
        } else {
            val cleanData = request.data.trim('/')
            if (page <= 1) "$mainUrl/$cleanData/" else "$mainUrl/$cleanData/page/$page/"
        }
        val doc = fetchHtml(url)
        val home = doc.select("article, .post-item, .item, div.single-post").mapNotNull { toResult(it) }
        return newHomePageResponse(HomePageList(request.name, home, isHorizontalImages = true), home.isNotEmpty())
    }

    private fun toResult(post: Element): SearchResponse? {
        val title = post.selectFirst("h2, .post-title, .entry-title")?.text()?.trim() ?: return null
        if (title.isEmpty()) return null
        val url = post.selectFirst("a[href]")?.attr("href") ?: return null
        val poster = post.selectFirst("picture img, img")?.let { img ->
            img.attr("data-src")
                .ifEmpty { img.attr("src") }
                .ifEmpty { img.attr("srcset").split(" ").firstOrNull() ?: "" }
        } ?: ""
        return newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = fetchHtml("$mainUrl/?s=$query")
        return doc.select("article, .post-item, .item, div.single-post").mapNotNull { toResult(it) }
    }

    private fun qualityOf(a: Element): String {
        val text = "${a.text()} ${a.className()}".lowercase()
        return when {
            text.contains("1080") -> "1080p"
            text.contains("720") -> "720p"
            text.contains("480") -> "480p"
            text.contains("4k") || text.contains("2160") -> "4K"
            else -> "Unknown"
        }
    }

    // Download buttons following a section title, up to the next section title
    private fun collectSectionLinks(section: Element): List<String> {
        val links = mutableListOf<String>()
        var sib = section.nextElementSibling()
        while (sib != null && !sib.hasClass("post-section-title")) {
            val aElements = if (sib.tagName() == "a") listOf(sib) else sib.select("a")
            for (a in aElements) {
                val href = a.attr("href").trim()
                if (href.isEmpty()) continue
                if (a.hasClass("Dbtn") || a.hasClass("sd") || a.hasClass("hd") ||
                    a.hasClass("hevc") || a.hasClass("watch") || href.contains("savelinks")
                ) {
                    links.add("${qualityOf(a)}|$href")
                }
            }
            sib = sib.nextElementSibling()
        }
        return links
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = fetchHtml(url)
        val title = doc.selectFirst("h1.entry-title, h1, .name")?.text()?.trim()
            ?: doc.selectFirst("title")?.text()?.trim()
            ?: ""
        val year = "(?<=\\()\\d{4}(?=\\))".toRegex().find(title)?.value?.toIntOrNull()
        val image = doc.selectFirst("img.aligncenter, .entry-content img, picture img")?.let { img ->
            img.attr("src").ifEmpty { img.attr("data-src") }
        } ?: ""
        val plot = doc.selectFirst(".storyline, .details, .entry-content")?.text()?.trim() ?: ""

        val episodeDivs = doc.select("div.post-section-title.download, div.post-section-title")
        val rangeRegex = "Epi-?\\s*(\\d+)(?:-(\\d+))?".toRegex(RegexOption.IGNORE_CASE)
        val isTvSeries = episodeDivs.any { rangeRegex.containsMatchIn(it.text()) } ||
                title.contains("Season", true) ||
                url.contains("/tv-series/")

        if (isTvSeries && episodeDivs.isNotEmpty()) {
            val episodesData = mutableListOf<Episode>()
            // A section may cover a range of episodes (e.g. "Epi-89-96") that share the same links
            for (episodeDiv in episodeDivs) {
                val match = rangeRegex.find(episodeDiv.text())
                val start = match?.groups?.get(1)?.value?.toIntOrNull() ?: 0
                val end = match?.groups?.get(2)?.value?.toIntOrNull() ?: start
                val qlinks = collectSectionLinks(episodeDiv)
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
            if (episodesData.isNotEmpty()) {
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesData) {
                    this.posterUrl = image
                    this.year = year
                    this.plot = plot
                }
            }
        }

        val links = episodeDivs.flatMap { collectSectionLinks(it) }.toMutableList()
        if (links.isEmpty()) {
            doc.select("a.Dbtn, a.sd, a.hd, a.hevc").forEach { a ->
                val href = a.attr("href").trim()
                if (href.isNotEmpty() && (href.contains("savelinks") || href.startsWith("http"))) {
                    links.add("${qualityOf(a)}|$href")
                }
            }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, links.joinToString(" ; ")) {
            this.posterUrl = image
            this.year = year
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // The same host link can appear under several buttons; keep the most specific quality label
        val items = linkedMapOf<String, String>()
        data.split(" ; ").forEach { item ->
            val parts = item.split("|", limit = 2)
            val qualityLabel = if (parts.size > 1) parts[0] else ""
            val link = (if (parts.size > 1) parts[1] else item).trim()
            if (link.isEmpty()) return@forEach
            val existing = items[link]
            if (existing == null || existing == "Unknown" || existing.isEmpty()) items[link] = qualityLabel
        }

        items.entries.toList().amap { (link, qualityLabel) ->
            if (link.contains("savelinks")) {
                val doc = try {
                    fetchHtml(link)
                } catch (e: Exception) {
                    try {
                        app.get(link).document
                    } catch (e: Exception) {
                        null
                    }
                }
                val hosts = doc?.select("ul li a[href^=http], a.btn[href^=http], .card a[href^=http], a[href*='file/'], a[href*='view/']")
                    ?.map { it.attr("href").trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.distinct()
                    ?: emptyList()
                hosts.amap { href -> resolveCustomExtractor(href, qualityLabel, subtitleCallback, callback) }
            } else {
                resolveCustomExtractor(link, qualityLabel, subtitleCallback, callback)
            }
        }
        return true
    }

    private suspend fun resolveCustomExtractor(
        url: String,
        qualityLabel: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty()) return
        val skipHosts = listOf(
            "t.me", "telegram", "whatsapp", "facebook", "twitter",
            "savelinks.me/login", "savelinks.me/register", "mlsbd.co"
        )
        if (skipHosts.any { cleanUrl.contains(it) }) return

        try {
            when {
                cleanUrl.contains("filepress", true) || cleanUrl.contains("filebee", true) ->
                    Filepress().getUrl(cleanUrl, qualityLabel, subtitleCallback, callback)
                cleanUrl.contains("multicloud", true) ->
                    MultiCloudLinks().getUrl(cleanUrl, qualityLabel, subtitleCallback, callback)
                cleanUrl.contains("gdxfiles", true) ->
                    GDxFiles().getUrl(cleanUrl, qualityLabel, subtitleCallback, callback)
                cleanUrl.contains("gdflix", true) ->
                    GDFlix().getUrl(cleanUrl, qualityLabel, subtitleCallback, callback)
                cleanUrl.contains("hubcloud", true) || cleanUrl.contains("shikshakdaak", true) ->
                    HubCloud().getUrl(cleanUrl, qualityLabel, subtitleCallback, callback)
                cleanUrl.contains("gofile", true) ->
                    Gofile().getUrl(cleanUrl, qualityLabel, subtitleCallback, callback)
                cleanUrl.contains("filesdl", true) ->
                    Filesdl().getUrl(cleanUrl, qualityLabel, subtitleCallback, callback)
                cleanUrl.contains("pixeldrain.com", true) -> {
                    val fileId = cleanUrl.substringAfterLast("/").substringBefore("?")
                    val directUrl = if (cleanUrl.contains("download", true)) cleanUrl
                    else "https://pixeldrain.com/api/file/$fileId?download"
                    callback(
                        newExtractorLink("Pixeldrain", "Pixeldrain [$qualityLabel]", directUrl, INFER_TYPE) {
                            this.quality = getIndexQuality(qualityLabel)
                        }
                    )
                }
                else -> loadExtractor(cleanUrl, qualityLabel, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            Log.e("MlsbdProvider", "Error resolving $cleanUrl: ${e.message}")
        }
    }
}
