package com.cncverse

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
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
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class MovieLinkBDProvider : MainAPI() {
    companion object {
        var appContext: Context? = null
        private const val HUB_URL = "https://movielinkbd.com"
        private const val FALLBACK_URL = "https://2gsyne.movielinkbd.at"
        private const val PREF_KEY = "movielinkbd_main_url"
        private val MIRROR_REGEX =
            Regex("^https?://([a-z0-9]+)\\.movielinkbd\\.([a-z]+)(?:/|$)", RegexOption.IGNORE_CASE)
        private val NON_MIRROR_SUBDOMAINS = setOf("www", "fast", "watch", "cdn", "static")
    }

    override var mainUrl = FALLBACK_URL
    override var name = "MovieLinkBD"
    override var lang = "bn"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false

    override val mainPage = mainPageOf(
        "/" to "Recently Updated",
        "/type/movies" to "All Movies",
        "/type/series" to "All Web Series",
        "/language/hindi" to "Hindi Movies",
        "/language/bangla" to "Bangla Movies",
        "/language/bangla-dubbed" to "Bangla Dubbed",
        "/language/dual-audio" to "Dual Audio",
        "/language/english" to "English",
        "/southIndian" to "South Indian",
        "/language/korean" to "Korean",
        "/anime" to "Anime Zone",
        "/drama" to "K/J/C Drama",
        "/ongoing" to "Ongoing Series",
        "/genre/action" to "Action",
        "/genre/thriller" to "Thriller",
        "/genre/horror" to "Horror",
        "/genre/romance" to "Romance",
        "/category/wwe" to "WWE"
    )

    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.AsianDrama,
        TvType.AnimeMovie, TvType.Anime,
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1"
    )

    @Volatile private var resolvedBase: String? = null
    @Volatile private var hpToken: String? = null
    @Volatile private var webCookies: String? = null
    @Volatile private var webViewUa: String? = null

    private val mgTokens = ConcurrentHashMap<String, MgToken>()
    private val cdnStreamRegex =
        Regex("^(https?://([a-z0-9.-]+)/p/[A-Za-z0-9._%-]+)(\\?.*)?$", RegexOption.IGNORE_CASE)
    private val hpTokenRegex = Regex("mlbd_hp=(?:\"\\s*\\+\\s*\")?([0-9]+\\.[0-9a-fA-F]+)")
    private val verifyMutex = Mutex()

    private data class MgToken(val value: String, val expiresAtSec: Long)

    private data class PlayerEpisode(
        val name: String?,
        val season: Int,
        val number: Int,
        val links: List<String>
    )

    // ── Live mirror resolution ──────────────────────────────────────────────
    private suspend fun getBase(): String {
        resolvedBase?.let { return it }
        val base = resolveFromHub() ?: savedBase() ?: FALLBACK_URL
        resolvedBase = base
        mainUrl = base
        return base
    }

    /** The hub links to the current mirror many times; the most linked host wins. */
    private suspend fun resolveFromHub(): String? {
        return try {
            val doc = app.get(HUB_URL, headers = headers, timeout = 15L).document
            val base = doc.select("a[href]").asSequence()
                .mapNotNull { mirrorBase(it.attr("href")) }
                .groupingBy { it }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key ?: return null
            saveBase(base)
            base
        } catch (_: Exception) {
            null
        }
    }

    private fun mirrorBase(url: String): String? {
        val m = MIRROR_REGEX.find(url.trim()) ?: return null
        val sub = m.groupValues[1].lowercase()
        if (sub in NON_MIRROR_SUBDOMAINS) return null
        return "https://$sub.movielinkbd.${m.groupValues[2].lowercase()}"
    }

    private fun prefs(): SharedPreferences? =
        appContext?.getSharedPreferences("com.lagradost.cloudstream3", Context.MODE_PRIVATE)

    private fun savedBase(): String? {
        return try {
            prefs()?.getString(PREF_KEY, null)?.let { mirrorBase(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun saveBase(base: String) {
        try {
            prefs()?.edit()?.putString(PREF_KEY, base)?.apply()
        } catch (_: Exception) {
        }
    }

    // ── Homepage / search ───────────────────────────────────────────────────
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = getBase()
        val path = request.data
        val url = when {
            path == "/" && page == 1 -> "$base/"
            path == "/" -> "$base/page/$page"
            page == 1 -> "$base$path"
            else -> "$base$path/page/$page"
        }
        val items = parseMovieCards(httpGetDoc(url))
        return newHomePageResponse(HomePageList(request.name, items), items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val base = getBase()
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        return parseMovieCards(httpGetDoc("$base/search?q=$encodedQuery"))
    }

    private fun parseMovieCards(doc: Element): List<SearchResponse> {
        return doc.select("div.movie-card").mapNotNull { card ->
            val a = card.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.attr("abs:href").ifEmpty { return@mapNotNull null }
            val img = card.selectFirst("img")
            val title = img?.attr("title")?.trim()?.ifEmpty { null }
                ?: img?.attr("alt")?.trim()?.ifEmpty { null }
                ?: a.text().trim().ifEmpty { null }
                ?: return@mapNotNull null
            newMovieSearchResponse(title, href, typeFromUrl(href)) {
                posterUrl = img?.attr("data-src")?.ifEmpty { null }
            }
        }.distinctBy { it.url }
    }

    private fun typeFromUrl(url: String): TvType = when {
        url.contains("/series/") || url.contains("/anime/") -> TvType.TvSeries
        url.contains("/drama/") -> TvType.AsianDrama
        else -> TvType.Movie
    }

    // ── Detail page ─────────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        val base = getBase()
        val doc = httpGetDoc(fixUrlDomain(url, base))
        val player = doc.selectFirst("script#mlbdInlinePlayerData")?.let {
            runCatching { JSONObject(decodeManifest(it)) }.getOrNull()
        }

        val title = doc.selectFirst(".movie-info-view h1")?.text()?.trim()?.ifEmpty { null }
            ?: player?.optStr("title")
            ?: doc.title().substringBefore(" Download").trim()
        val poster = doc.selectFirst(".image-container-view img")?.attr("src")?.ifEmpty { null }
            ?: player?.optStr("poster")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst(".story-text")?.text()?.trim()?.ifEmpty { null }

        val meta = doc.select("li.flex-item").associate { li ->
            li.ownText().trim() to (li.selectFirst("b")?.text()?.trim() ?: "")
        }
        val info = doc.select(".movie-extra-info p.info-line").associate { p ->
            (p.selectFirst("b")?.text()?.trim()?.removeSuffix(":") ?: "") to p
        }
        fun infoText(key: String): String? {
            val p = info[key] ?: return null
            return p.text().substringAfter(p.selectFirst("b")?.text() ?: "").trim().ifEmpty { null }
        }

        val genres = infoText("Genre")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: emptyList()
        val tags = (genres + listOfNotNull(meta["Language"], meta["Quality"]))
            .filter { it.isNotEmpty() }
            .distinct()
        val rating = infoText("IMDb")?.substringBefore("/")?.trim()?.toDoubleOrNull()
        val released = infoText("Released")
        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: released?.let { Regex("\\b(19|20)\\d{2}\\b").find(it)?.value?.toIntOrNull() }

        val actors = doc.select(".mlbd-cast__p").mapNotNull { a ->
            val actorName = a.selectFirst("b")?.text()?.trim()?.ifEmpty { null }
                ?: return@mapNotNull null
            ActorData(
                Actor(actorName, a.selectFirst("img")?.attr("abs:src")?.ifEmpty { null }),
                roleString = a.selectFirst("small")?.text()?.trim()?.ifEmpty { null }
            )
        }.ifEmpty {
            info["Cast"]?.select("a")
                ?.map { ActorData(Actor(it.text().trim())) }
                ?.filter { it.actor.name.isNotEmpty() }
                ?: emptyList()
        }

        val trailer = doc.selectFirst("[data-mlbd-trailer]")?.attr("data-mlbd-trailer")
            ?.ifEmpty { null }
            ?.let { "https://www.youtube.com/watch?v=$it" }
        val recommendations = doc.select(".mlbd-page-head")
            .firstOrNull { it.text().contains("Also Download", true) }
            ?.nextElementSibling()
            ?.let { parseMovieCards(it) }
            ?: emptyList()
        val seriesStatus = if (player?.has("completed") == true) {
            if (player.optBoolean("completed")) ShowStatus.Completed else ShowStatus.Ongoing
        } else null

        val playerEpisodes = parseInlineManifest(player).ifEmpty { parseWatchFrame(doc, base) }
        val allLinks = playerEpisodes.flatMap { it.links }
        val episodes = playerEpisodes.map { ep ->
            newEpisode(ep.links.joinToString("\n")) {
                name = ep.name
                season = ep.season
                episode = ep.number
            }
        }

        val type = typeFromUrl(url)
        if (type == TvType.Movie) {
            return newMovieLoadResponse(title, url, TvType.Movie, allLinks.joinToString("\n")) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.actors = actors
                this.recommendations = recommendations
                rating?.let { score = Score.from10(it) }
                addTrailer(trailer)
            }
        }
        return newTvSeriesLoadResponse(
            title, url, type,
            episodes.sortedWith(compareBy({ it.season }, { it.episode }))
        ) {
            posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            this.actors = actors
            this.recommendations = recommendations
            showStatus = seriesStatus
            rating?.let { score = Score.from10(it) }
            addTrailer(trailer)
        }
    }

    /** Links are encoded as `quality|label|url`, one per line. */
    private fun parseInlineManifest(player: JSONObject?): List<PlayerEpisode> {
        val epArr = player?.optJSONArray("episodes") ?: return emptyList()
        return (0 until epArr.length()).mapNotNull { i ->
            val ep = epArr.getJSONObject(i)
            val sources = ep.optJSONArray("sources")
            val links = (0 until (sources?.length() ?: 0)).mapNotNull { j ->
                val src = sources!!.getJSONObject(j)
                val streamUrl = src.optStr("url")?.takeIf { it.startsWith("http") }
                    ?: return@mapNotNull null
                val q = src.optInt("quality", 0)
                val label = listOfNotNull(src.optStr("quality_label") ?: qualityName(q), src.optStr("audio"))
                    .joinToString(" • ")
                "$q|$label|$streamUrl"
            }
            if (links.isEmpty()) return@mapNotNull null
            PlayerEpisode(ep.optStr("label"), ep.optIntOrNull("season") ?: 1, ep.optIntOrNull("number") ?: (i + 1), links)
        }
    }

    /** Fallback when the page has no inline manifest: read `window.__PD2` from the HD player iframe. */
    private suspend fun parseWatchFrame(doc: Document, base: String): List<PlayerEpisode> {
        val src = doc.selectFirst("iframe#mlbdHdFrame, iframe[src*='/p/']")?.attr("src")?.ifEmpty { null }
            ?: return emptyList()
        val frameUrl = if (src.startsWith("http")) src else base.trimEnd('/') + "/" + src.trimStart('/')
        val html = try {
            app.get(
                frameUrl,
                headers = requestHeaders() + mapOf("Referer" to "$base/", "Sec-Fetch-Dest" to "iframe"),
                timeout = 30L
            ).text
        } catch (_: Exception) {
            return emptyList()
        }
        val pd = runCatching {
            val start = html.indexOf("window.__PD2").takeIf { it >= 0 } ?: return emptyList()
            JSONObject(JSONTokener(html.substring(html.indexOf('{', start))))
        }.getOrNull() ?: return emptyList()

        fun linksOf(o: JSONObject): List<String> = listOfNotNull(
            o.optStr("master")?.takeIf { it.startsWith("http") }?.let { "0|HLS Auto|$it" },
            o.optStr("mp4")?.takeIf { it.startsWith("http") }?.let { "0|MP4 Original|$it" }
        )

        val epArr = pd.optJSONArray("episodes")
        val episodes = (0 until (epArr?.length() ?: 0)).mapNotNull { i ->
            val ep = epArr!!.getJSONObject(i)
            val links = linksOf(ep)
            if (links.isEmpty()) return@mapNotNull null
            PlayerEpisode(
                ep.optStr("label"),
                ep.optIntOrNull("season")?.takeIf { it > 0 } ?: 1,
                ep.optIntOrNull("number")?.takeIf { it > 0 } ?: (i + 1),
                links
            )
        }
        if (episodes.isNotEmpty()) return episodes
        val links = linksOf(pd)
        return if (links.isEmpty()) emptyList() else listOf(PlayerEpisode(pd.optStr("title"), 1, 1, links))
    }

    // ── Load links ──────────────────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val base = getBase()
        var found = false
        for (line in data.lines()) {
            val parts = line.split("|", limit = 3)
            if (parts.size < 3) continue
            val (q, label, rawLink) = parts
            val link = withMgToken(rawLink, base)
            val type = if (link.contains(".m3u8") || label.startsWith("HLS")) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }
            callback(
                newExtractorLink(name, "$name [$label]", link, type) {
                    referer = "$base/"
                    quality = q.toIntOrNull()?.takeIf { it > 0 } ?: Qualities.Unknown.value
                }
            )
            found = true
        }
        return found
    }

    /** fsldownload CDN hosts reject `/p/` streams without a short-lived `mg=` token. */
    private suspend fun withMgToken(link: String, base: String): String {
        val m = cdnStreamRegex.find(link)
        if (m == null || link.contains("mg=")) return link
        val host = m.groupValues[2]
        if (!host.contains("fsldownload", true)) return link
        val token = mgToken(host, base) ?: return link
        val query = m.groupValues[3].removePrefix("?")
        val mg = "mg=" + URLEncoder.encode(token, "UTF-8")
        return m.groupValues[1] + "?" + if (query.isEmpty()) mg else "$mg&$query"
    }

    private suspend fun mgToken(host: String, base: String): String? {
        val nowSec = System.currentTimeMillis() / 1000
        mgTokens[host]?.takeIf { it.expiresAtSec > nowSec }?.let { return it.value }
        return try {
            val json = JSONObject(
                app.get(
                    "https://$host/g",
                    headers = mapOf(
                        "User-Agent" to headers.getValue("User-Agent"),
                        "Origin" to base,
                        "Referer" to "$base/",
                        "Accept" to "application/json"
                    ),
                    timeout = 15L
                ).text
            )
            val g = json.optStr("g")?.takeIf { json.optBoolean("ok") } ?: return null
            val expiry = g.substringBefore('.').toLongOrNull()?.let { it - 600 } ?: (nowSec + 1800)
            mgTokens[host] = MgToken(g, expiry)
            g
        } catch (_: Exception) {
            null
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────
    /** The inline manifest is either plain JSON or base64 XOR-ed with an LCG keystream. */
    private fun decodeManifest(el: Element): String {
        val raw = el.data().trim()
        val seed = el.attr("data-s").ifEmpty { return raw }
        var x = seed.toLong(16) and 0xFFFFFFFFL
        repeat(el.attr("data-r").toIntOrNull() ?: 0) {
            x = (1103515245L * x + 12345L) and 0xFFFFFFFFL
        }
        val bytes = Base64.decode(raw, Base64.DEFAULT)
        for (i in bytes.indices) {
            x = (x * 1103515245L + 12345L) and 0xFFFFFFFFL
            bytes[i] = (((x ushr 16).toInt() and 0xFF) xor bytes[i].toInt()).toByte()
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun JSONObject.optStr(key: String): String? =
        if (isNull(key)) null else optString(key).trim().ifEmpty { null }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (isNull(key)) null else optInt(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }

    private fun qualityName(q: Int): String = when {
        q >= 2160 -> "4K"
        q > 0 -> "${q}p"
        else -> "Stream"
    }

    private fun fixUrlDomain(url: String, base: String): String {
        return try {
            val uri = URI(url)
            if (uri.host == null || !uri.host.contains("movielinkbd")) return url
            val query = uri.rawQuery?.let { "?$it" } ?: ""
            base.trimEnd('/') + (uri.rawPath ?: "") + query
        } catch (_: Exception) {
            url
        }
    }

    private fun requestHeaders(): Map<String, String> {
        val cookies = listOfNotNull(hpToken?.let { "mlbd_hp=$it" }, webCookies).joinToString("; ")
        if (cookies.isEmpty()) return headers
        val ua = webViewUa?.takeIf { webCookies != null }
        return headers + mapOf("Cookie" to cookies) + (ua?.let { mapOf("User-Agent" to it) } ?: emptyMap())
    }

    private fun isTurnstileGate(html: String): Boolean =
        html.length < 20000 && html.contains("cf-turnstile")

    // ── HTTP with hp-cookie and Turnstile handling ──────────────────────────
    private suspend fun httpGetDoc(url: String): Document {
        return try {
            var resp = app.get(url, headers = requestHeaders(), timeout = 30L)
            if (resp.text.length < 20000) {
                val token = resp.headers.values("Set-Cookie")
                    .firstNotNullOfOrNull { hpTokenRegex.find(it)?.groupValues?.get(1) }
                    ?: hpTokenRegex.find(resp.text)?.groupValues?.get(1)
                if (token != null) {
                    hpToken = token
                    resp = app.get(url, headers = requestHeaders(), timeout = 30L)
                }
            }
            if (!isTurnstileGate(resp.text)) return Jsoup.parse(resp.text, url)
            verifyMutex.withLock {
                val retry = app.get(url, headers = requestHeaders(), timeout = 30L)
                if (isTurnstileGate(retry.text)) {
                    Jsoup.parse(fetchViaWebView(url) ?: retry.text, url)
                } else {
                    Jsoup.parse(retry.text, url)
                }
            }
        } catch (_: Exception) {
            Jsoup.parse("", url)
        }
    }

    /**
     * Shows the page in a WebView so the user can pass the Turnstile check, then keeps the
     * WebView cookies and user agent for later plain HTTP requests.
     */
    private suspend fun fetchViaWebView(url: String): String? {
        val ctx = appContext ?: return null
        return withContext(Dispatchers.Main) {
            suspendCoroutine { cont ->
                var resolved = false
                var dialog: AlertDialog? = null
                val wv = WebView(ctx)

                fun finish(html: String?) {
                    if (resolved) return
                    resolved = true
                    try {
                        dialog?.dismiss()
                    } catch (_: Exception) {
                    }
                    try {
                        wv.destroy()
                    } catch (_: Exception) {
                    }
                    cont.resume(html)
                }

                try {
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(wv, true)
                    wv.settings.javaScriptEnabled = true
                    wv.settings.domStorageEnabled = true
                    webViewUa = wv.settings.userAgentString
                    wv.layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

                    val metrics = ctx.resources.displayMetrics
                    val dp = metrics.density
                    val container = FrameLayout(ctx).apply {
                        isFocusable = true
                        isFocusableInTouchMode = true
                        addView(wv)
                    }
                    if (isLayout(TV)) addTvCursor(ctx, container, wv, dp)

                    val layout = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(TextView(ctx).apply {
                            text = "MovieLinkBD: tap \"Verify you are human\" to continue."
                            setTextColor(Color.WHITE)
                            setBackgroundColor(Color.parseColor("#0d1117"))
                            val p = (10 * dp).toInt()
                            setPadding(p, p, p, p)
                        })
                        addView(
                            container,
                            LinearLayout.LayoutParams(
                                (metrics.widthPixels * 0.95f).toInt(),
                                (metrics.heightPixels * 0.8f).toInt()
                            )
                        )
                    }
                    dialog = AlertDialog.Builder(ctx)
                        .setView(layout)
                        .setCancelable(true)
                        .setOnDismissListener { finish(null) }
                        .create()
                    dialog?.show()
                    dialog?.window?.setLayout(
                        (metrics.widthPixels * 0.95f).toInt(),
                        (metrics.heightPixels * 0.9f).toInt()
                    )

                    val handler = Handler(Looper.getMainLooper())
                    val poll = object : Runnable {
                        override fun run() {
                            if (resolved) return
                            wv.evaluateJavascript("document.documentElement.outerHTML") { raw ->
                                val html = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
                                if (html != null &&
                                    (html.contains("mlbdInlinePlayerData") || html.contains("mlbdHdFrame"))
                                ) {
                                    cookieManager.getCookie(url)?.ifEmpty { null }?.let { webCookies = it }
                                    finish(html)
                                    return@evaluateJavascript
                                }
                                handler.postDelayed(this, 1000L)
                            }
                        }
                    }
                    wv.webViewClient = WebViewClient()
                    wv.loadUrl(url)
                    handler.postDelayed(poll, 1000L)
                    handler.postDelayed({ finish(null) }, 180_000L)
                } catch (_: Exception) {
                    finish(null)
                }
            }
        }
    }

    /** D-pad driven pointer so the Turnstile checkbox can be clicked on TV. */
    private fun addTvCursor(ctx: Context, container: FrameLayout, wv: WebView, dp: Float) {
        val size = (22 * dp).toInt()
        val cursor = View(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(size, size)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(160, 255, 50, 50))
                setStroke((2 * dp).toInt(), Color.WHITE)
            }
            elevation = 999f
        }
        container.addView(cursor)
        var x = 0f
        var y = 0f

        fun place() {
            cursor.translationX = x - size / 2f
            cursor.translationY = y - size / 2f
        }

        container.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                container.viewTreeObserver.removeOnGlobalLayoutListener(this)
                x = container.width / 2f
                y = container.height / 2f
                place()
                container.requestFocus()
            }
        })
        container.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val step = 12f * dp
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> y = (y - step).coerceAtLeast(0f)
                KeyEvent.KEYCODE_DPAD_DOWN -> y = (y + step).coerceAtMost(container.height.toFloat())
                KeyEvent.KEYCODE_DPAD_LEFT -> x = (x - step).coerceAtLeast(0f)
                KeyEvent.KEYCODE_DPAD_RIGHT -> x = (x + step).coerceAtMost(container.width.toFloat())
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    val t = SystemClock.uptimeMillis()
                    val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
                    val up = MotionEvent.obtain(t, t + 100, MotionEvent.ACTION_UP, x, y, 0)
                    wv.dispatchTouchEvent(down)
                    wv.dispatchTouchEvent(up)
                    down.recycle()
                    up.recycle()
                }
                else -> return@setOnKeyListener false
            }
            place()
            true
        }
    }
}
