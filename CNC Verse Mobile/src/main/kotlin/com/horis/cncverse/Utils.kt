package com.horis.cncverse

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ResponseParser
import kotlin.reflect.KClass
import okhttp3.FormBody
import kotlinx.coroutines.delay
import android.content.Context
import com.lagradost.api.Log
import org.json.JSONObject
import java.util.UUID
import okhttp3.Request
import java.util.Base64
import android.app.AlertDialog
import android.content.DialogInterface
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
import com.lagradost.cloudstream3.ui.settings.Globals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

val JSONParser = object : ResponseParser {
    val mapper: ObjectMapper = jacksonObjectMapper().configure(
        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false
    ).configure(
        JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature(), true
    )

    override fun <T : Any> parse(text: String, kClass: KClass<T>): T {
        return mapper.readValue(text, kClass.java)
    }

    override fun <T : Any> parseSafe(text: String, kClass: KClass<T>): T? {
        return try {
            mapper.readValue(text, kClass.java)
        } catch (e: Exception) {
            null
        }
    }

    override fun writeValueAsString(obj: Any): String {
        return mapper.writeValueAsString(obj)
    }
}

const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

val app = Requests(responseParser = JSONParser).apply {
    defaultHeaders = mapOf("User-Agent" to USER_AGENT)
}

inline fun <reified T : Any> parseJson(text: String): T {
    return JSONParser.parse(text, T::class)
}

inline fun <reified T : Any> tryParseJson(text: String): T? {
    return try {
        return JSONParser.parseSafe(text, T::class)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

fun convertRuntimeToMinutes(runtime: String): Int {
    var totalMinutes = 0
    val parts = runtime.split(" ")
    for (part in parts) {
        when {
            part.endsWith("h") -> {
                val hours = part.removeSuffix("h").trim().toIntOrNull() ?: 0
                totalMinutes += hours * 60
            }
            part.endsWith("m") -> {
                val minutes = part.removeSuffix("m").trim().toIntOrNull() ?: 0
                totalMinutes += minutes
            }
        }
    }
    return totalMinutes
}

const val NETMIRROR_MOBILE_UA = "Mozilla/5.0 (Linux; Android 12; RMX2117 Build/SP1A.210812.016; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/147.0.7727.55 Mobile Safari/537.36 /OS.Gatu v3.0"

// ── NetMirror mobile playlist models ────────────────────────────────────────

data class NetMirrorPlaylistSource(
    val file: String? = null,
    val label: String? = null
)

data class NetMirrorPlaylistTrack(
    val kind: String? = null,
    val file: String? = null,
    val label: String? = null
)

data class NetMirrorPlaylistItem(
    val sources: List<NetMirrorPlaylistSource>? = null,
    val tracks: List<NetMirrorPlaylistTrack>? = null
)

/**
 * Resolves streams via the NetMirror mobile web API (playlist.php).
 * The t_hash_t session cookie (from bypass()) is bound server-side into a
 * per-request `in=` token; expired sessions yield `in=unknown::ni` URLs that
 * 404, so we refresh the bypass cookie once and retry.
 * Full session cookies are attached to every link so Cloudflare does not
 * rate-limit segment requests mid-playback ("too many requests").
 */
suspend fun netMirrorMobileLoadLinks(
    mainUrl: String,
    id: String,
    title: String,
    playlistPath: String,
    baseCookies: Map<String, String>,
    name: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    var cookieValue = bypass(mainUrl)
    if (cookieValue.isEmpty()) return false

    val titleEnc = java.net.URLEncoder.encode(title, "UTF-8")
    val referer = "$mainUrl/mobile/home?app=1"
    val reqHeaders = mapOf(
        "User-Agent" to NETMIRROR_MOBILE_UA,
        "X-Requested-With" to "XMLHttpRequest",
        "Accept" to "*/*",
        "Referer" to referer
    )
    fun sessionCookies() = baseCookies + mapOf("t_hash_t" to cookieValue)
    fun cookieHeader() = sessionCookies().entries.joinToString("; ") { "${it.key}=${it.value}" }

    suspend fun fetchPlaylist(): NetMirrorPlaylistItem? {
        return try {
            val res = app.get(
                "$mainUrl$playlistPath?id=$id&t=$titleEnc&tm=${APIHolder.unixTime}",
                headers = reqHeaders,
                cookies = sessionCookies(),
                referer = referer,
                timeout = 30
            )
            tryParseJson<List<NetMirrorPlaylistItem>>(res.text)?.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    var item = fetchPlaylist()
    var sources = item?.sources ?: emptyList()

    if (sources.isEmpty() || sources.any { it.file?.contains("in=unknown") == true }) {
        NetflixMirrorStorage.clearCookie()
        cookieValue = bypass(mainUrl)
        if (cookieValue.isEmpty()) return false
        item = fetchPlaylist()
        sources = item?.sources ?: emptyList()
    }

    if (sources.isEmpty()) return false

    // Subtitles (protocol-relative URLs like //subscdn.top/...)
    item?.tracks?.forEach { track ->
        if (track.kind == "captions" && !track.file.isNullOrEmpty()) {
            val subUrl = if (track.file!!.startsWith("http")) track.file else "https:${track.file}"
            try {
                subtitleCallback(SubtitleFile(track.label?.takeIf { it.isNotBlank() } ?: "English", subUrl))
            } catch (_: Exception) { }
        }
    }

    var emitted = false
    for (src in sources) {
        val file = src.file ?: continue
        if (file.contains("in=unknown")) continue
        val url = if (file.startsWith("http")) file else "$mainUrl$file"
        val label = src.label?.takeIf { it.isNotBlank() } ?: "Auto"
        callback.invoke(
            newExtractorLink(name, "$label - $title", url, ExtractorLinkType.M3U8) {
                this.referer = referer
                this.headers = mapOf(
                    "Referer" to referer,
                    "User-Agent" to NETMIRROR_MOBILE_UA,
                    "Cookie" to cookieHeader()
                )
            }
        )
        emitted = true
    }
    return emitted
}

suspend fun bypass(mainUrl: String): String {
    val (savedCookie, savedTimestamp) = NetflixMirrorStorage.getCookie()

    // Cookie Max-Age is 12h server-side
    if (!savedCookie.isNullOrEmpty() && System.currentTimeMillis() - savedTimestamp < 43_200_000) {
        return savedCookie
    }

    // Primary path: mobile-API verification flow (no captcha needed).
    // 1. /mobile/home?app=1 -> data-addhash attribute
    // 2. userver.<host>/?hee5=<addhash>&a=y&t=<rand> (registers the ad click)
    // 3. poll POST /mobile/verify2.php (verify=<addhash>) until "All Done";
    //    the successful response sets t_hash_t via Set-Cookie.
    val newCookie = netMirrorMobileBypass(mainUrl)

    if (newCookie.isNotEmpty()) {
        NetflixMirrorStorage.saveCookie(newCookie)
        return newCookie
    }

    // Last resort: WebView captcha solve (Cloudflare challenge UI)
    val wvCookie = solveBypassInWebView()
    if (wvCookie.isNotEmpty()) {
        NetflixMirrorStorage.saveCookie(wvCookie)
        return wvCookie
    }
    return ""
}

private suspend fun netMirrorMobileBypass(mainUrl: String): String {
    return try {
        val host = mainUrl
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')
        val homeHeaders = mapOf(
            "User-Agent" to NETMIRROR_MOBILE_UA,
            "X-Requested-With" to "app.netmirror.netmirrornew"
        )

        // 1. Home page -> data-addhash
        val homeRes = app.get("$mainUrl/mobile/home?app=1", headers = homeHeaders, timeout = 30)
        if (homeRes.code != 200) return ""
        val addhash = Regex("""data-addhash="([^"]+)"""").find(homeRes.text)?.groupValues?.get(1)
            ?: return ""

        // 2. Trigger verification helper; it redirects through an ad chain whose
        // completion registers the click against our addhash.
        try {
            app.get(
                "https://userver.$host/?hee5=${java.net.URLEncoder.encode(addhash, "UTF-8")}&a=y&t=${System.currentTimeMillis()}",
                headers = mapOf(
                    "User-Agent" to NETMIRROR_MOBILE_UA,
                    "Referer" to "$mainUrl/mobile/home?app=1"
                ),
                timeout = 60
            )
        } catch (_: Exception) { }

        // 3. Poll verify2.php until "All Done" (~30-45s in practice, cap ~80s)
        repeat(20) {
            delay(4000L)
            try {
                val res = app.post(
                    "$mainUrl/mobile/verify2.php",
                    headers = mapOf(
                        "User-Agent" to NETMIRROR_MOBILE_UA,
                        "X-Requested-With" to "XMLHttpRequest",
                        "Content-Type" to "application/x-www-form-urlencoded"
                    ),
                    data = mapOf("verify" to addhash),
                    timeout = 30
                )
                val setCookies = res.headers.values("Set-Cookie")
                val cookie = setCookies.firstOrNull { it.startsWith("t_hash_t=") }
                    ?.substringAfter("t_hash_t=")
                    ?.substringBefore(";")
                if (res.text.contains("\"statusup\":\"All Done\"") && !cookie.isNullOrEmpty()) {
                    return cookie
                }
            } catch (_: Exception) { }
        }
        ""
    } catch (_: Exception) {
        ""
    }
}

val newTvBaseHeaders = mapOf(
    "Cache-Control" to "no-cache, no-store, must-revalidate",
    "Pragma" to "no-cache",
    "Expires" to "0",
    "X-Requested-With" to "NetmirrorNewTV v1.0",
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0 /OS.GatuNewTV v1.0",
    "Accept" to "application/json, text/plain, */*"
)

val newTvDomains = listOf(
    "aHR0cHM6Ly9tb2JpbGVkZXRlY3RzLmNvbQ==",
    "aHR0cHM6Ly9tb2JpbGVkZXRlY3QuYXBw",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LmFydA==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LmNj",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LmNsaWNr",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0Lmluaw==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LmxpdmU=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LnBybw==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LnNob3A=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LnNpdGU=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LnNwYWNl",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LnN0b3Jl",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LnZpcA==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0Lndpa2k=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0Lnh5eg==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5hcnQ=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5jYw==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5pbmZv",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5pbms=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5saXZl",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5wcm8=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5zdG9yZQ==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy50b3A=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy54eXo="
)

fun decodeBase64(value: String): String {
    return String(Base64.getDecoder().decode(value))
}

private var resolvedApiUrl: String = ""

suspend fun resolveApiUrl(): String {
    if (resolvedApiUrl.isNotBlank()) return resolvedApiUrl
    for (encoded in newTvDomains) {
        val base = decodeBase64(encoded).trimEnd('/')
        try {
            val response = app.get("$base/checknewtv.php", headers = newTvBaseHeaders)
                .parsed<NewTvTokenResponse>()
            val tokenHash = response.token_hash
            if (!tokenHash.isNullOrBlank()) {
                resolvedApiUrl = decodeBase64(tokenHash).trimEnd('/')
                return resolvedApiUrl
            }
        } catch (_: Exception) {
        }
    }
    throw Exception("Failed to resolve NewTV API base URL")
}

fun buildNewTvHeaders(ott: String, extra: Map<String, String> = emptyMap()): Map<String, String> {
    val result = newTvBaseHeaders.toMutableMap()
    result["Ott"] = ott
    extra.forEach { (key, value) ->
        result[key] = value
    }
    return result
}

data class NewTvOtpResponse(
    val otp: String? = null,
    val status: String? = null,
    val usertoken: String? = null,
    val pub_msg: String? = null,
    val pub_msg_f_size: Int? = null,
    val pub_msg_color: String? = null,
    val error_msg: String? = null
)

const val NETMIRROR_TV_URL = "https://netmirror.gg/tv"

private fun fetchNetmirrorTvHtmlBuildHeaders(cfClearance: String?): Map<String, String> {
    val headers = mutableMapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )
    if (!cfClearance.isNullOrEmpty()) {
        headers["Cookie"] = "cf_clearance=$cfClearance"
    }
    return headers
}

private fun fetchNetmirrorTvHtmlIsCloudflare(html: String, statusCode: Int): Boolean {
    if (statusCode == 403 || statusCode == 503) return true
    return html.contains("netmirror.gg/tv", ignoreCase = true) &&
        (html.contains("cf-browser-verification", ignoreCase = true) ||
         html.contains("Checking if the site connection is secure", ignoreCase = true) ||
         html.contains("Just a moment", ignoreCase = true) ||
         html.contains("cloudflare", ignoreCase = true))
}

suspend fun fetchNetmirrorTvHtml(): String {
    val (savedCf, savedCfTs) = NetflixMirrorStorage.getCfCookie()
    val cfCookieToUse = if (!savedCf.isNullOrEmpty() && System.currentTimeMillis() - savedCfTs < 82800000) savedCf else null
    try {
        val firstResponse = app.get(NETMIRROR_TV_URL, fetchNetmirrorTvHtmlBuildHeaders(cfCookieToUse))
        if (!fetchNetmirrorTvHtmlIsCloudflare(firstResponse.text, firstResponse.code)) {
            return firstResponse.text
        }
        val cfClearance = solveCloudflareInWebView(NETMIRROR_TV_URL)
        if (cfClearance.isNullOrEmpty()) return firstResponse.text
        NetflixMirrorStorage.saveCfCookie(cfClearance)
        return try {
            app.get(NETMIRROR_TV_URL, fetchNetmirrorTvHtmlBuildHeaders(cfClearance)).text
        } catch (e: Exception) {
            firstResponse.text
        }
    } catch (e: Exception) {
        return ""
    }
}

suspend fun getNewTvUserToken(apiBase: String, ott: String, forceRefresh: Boolean = false): String {
    val (savedToken, savedTimestamp) = NetflixMirrorStorage.getUserToken(ott)
    if (!forceRefresh && !savedToken.isNullOrEmpty()) return savedToken

    var currentOtp = NetflixMirrorStorage.getOtp() ?: "109400"
    val otpHeaders = mutableMapOf(
        "accept" to "application/json, text/plain, */*",
        "cache-control" to "no-cache, no-store, must-revalidate",
        "Connection" to "Keep-Alive",
        "expires" to "0",
        "otp" to currentOtp,
        "pragma" to "no-cache",
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0 /OS.Gatu v1.0"
    )

    var otpResponse = try {
        app.get("$apiBase/newtv/otp.php", otpHeaders).parsedSafe<NewTvOtpResponse>()
    } catch (e: Exception) {
        null
    }

    if (otpResponse?.status == "error" && otpResponse.error_msg == "Invalid OTP, Please Enter Valid OTP") {
        val maxCaptchaAttempts = 3
        var attempt = 1
        while (true) {
            val tvHtml = fetchNetmirrorTvHtml()
            val otpMatch = Regex("""(?m)^\s*const\s+otp\s*=\s*\[(.*?)]""").find(tvHtml)
            if (otpMatch != null) {
                val newOtp = Regex("""\s*,\s*""").replace(otpMatch.groupValues[1], "").replace(" ", "")
                if (newOtp.isNotEmpty()) {
                    currentOtp = newOtp
                    NetflixMirrorStorage.saveOtp(currentOtp)
                    otpHeaders["otp"] = currentOtp
                    otpResponse = try {
                        app.get("$apiBase/newtv/otp.php", otpHeaders).parsedSafe<NewTvOtpResponse>()
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            NetflixMirrorStorage.clearCfCookie()
            if (attempt >= maxCaptchaAttempts) break
            delay(500L)
            attempt++
        }
        val newToken = otpResponse?.usertoken.orEmpty()
        if (newToken.isNotEmpty()) {
            NetflixMirrorStorage.saveUserToken(ott, newToken)
        }
        return newToken
    }
    val newToken = otpResponse?.usertoken.orEmpty()
    if (newToken.isNotEmpty()) {
        NetflixMirrorStorage.saveUserToken(ott, newToken)
    }
    return newToken
}

suspend fun solveBypassInWebView(): String {
    val ctx = NetflixMirrorProvider.context ?: return ""
    val verifyUrl = "https://net77.cc/verify2"
    return withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            try {
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                val wv = WebView(ctx)
                try { cookieManager.setAcceptThirdPartyCookies(wv, true) } catch (_: NoSuchMethodError) {} catch (_: Exception) {}
                val ws = wv.settings
                ws.javaScriptEnabled = true
                ws.domStorageEnabled = true
                ws.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                ws.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"
                ws.mediaPlaybackRequiresUserGesture = false
                wv.webChromeClient = WebChromeClient()

                var resolved = false
                fun extractAndFinish() {
                    if (resolved) return
                    val cookies = cookieManager.getCookie(verifyUrl)
                        ?: cookieManager.getCookie("https://net77.cc")
                        ?: ""
                    val match = Regex("""t_hash_t=([^;]+)""").find(cookies)
                    val tHash = match?.groupValues?.get(1)
                    if (!tHash.isNullOrEmpty()) {
                        resolved = true
                        try { wv.destroy() } catch (_: Exception) {}
                        try {
                            val tag = wv.tag
                            if (tag is android.app.Dialog) tag.dismiss()
                        } catch (_: Exception) {}
                        cont.resume(tHash)
                    }
                }

                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        extractAndFinish()
                        if (!resolved) {
                            val handler = Handler(Looper.getMainLooper())
                            handler.postDelayed(object : Runnable {
                                override fun run() {
                                    if (!resolved) {
                                        extractAndFinish()
                                        if (!resolved) {
                                            handler.postDelayed(this, 1000L)
                                        }
                                    }
                                }
                            }, 1000L)
                        }
                    }
                }

                val dp = ctx.resources.displayMetrics.density
                val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val metrics = Point()
                wm.defaultDisplay.getSize(metrics)
                val params = WindowManager.LayoutParams(
                    (metrics.x * 0.95f).toInt(),
                    (metrics.y * 0.9f).toInt()
                )
                val wrapper = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                val infoBar = TextView(ctx).apply {
                    text = "Solve the Cloudflare captcha"
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
                val container = FrameLayout(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                    )
                    isFocusable = true
                    isFocusableInTouchMode = true
                }
                wv.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                container.addView(wv)

                val isTv = Globals.isLayout(2)
                val cursorSize = (22 * dp).toInt()
                if (isTv) {
                    val cursorView = View(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(cursorSize, cursorSize)
                        val bg = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.argb(160, 255, 50, 50))
                            setStroke((2 * dp).toInt(), Color.WHITE)
                        }
                        setBackgroundDrawable(bg)
                        elevation = 999f
                        isFocusable = false
                    }
                    container.addView(cursorView)

                    val cursorX = (metrics.x * 0.95f) / 2f
                    val cursorY = (metrics.y * 0.9f) / 2f

                    container.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                        override fun onGlobalLayout() {
                            container.viewTreeObserver.removeOnGlobalLayoutListener(this)
                            cursorView.translationX = container.width / 2f - cursorSize / 2f
                            cursorView.translationY = container.height / 2f - cursorSize / 2f
                        }
                    })

                    var cx = cursorX
                    var cy = cursorY
                    container.setOnKeyListener { _, keyCode, event ->
                        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                        val step = dp * 10f
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP -> cy = (cy - step).coerceIn(0f, container.height.toFloat())
                            KeyEvent.KEYCODE_DPAD_DOWN -> cy = (cy + step).coerceIn(0f, container.height.toFloat())
                            KeyEvent.KEYCODE_DPAD_LEFT -> cx = (cx - step).coerceIn(0f, container.width.toFloat())
                            KeyEvent.KEYCODE_DPAD_RIGHT -> cx = (cx + step).coerceIn(0f, container.width.toFloat())
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                                val t = SystemClock.uptimeMillis()
                                val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, cx, cy, 0)
                                val up = MotionEvent.obtain(t, t + 120, MotionEvent.ACTION_UP, cx, cy, 0)
                                wv.dispatchTouchEvent(down)
                                wv.dispatchTouchEvent(up)
                                down.recycle()
                                up.recycle()
                                return@setOnKeyListener true
                            }
                            else -> return@setOnKeyListener false
                        }
                        cursorView.translationX = cx - cursorSize / 2f
                        cursorView.translationY = cy - cursorSize / 2f
                        true
                    }
                }

                val dialog = AlertDialog.Builder(ctx)
                    .setView(wrapper)
                    .setCancelable(false)
                    .create()
                dialog.window?.let { win ->
                    win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    win.setLayout(params.width, params.height)
                }
                wv.tag = dialog
                dialog.setOnDismissListener {
                    if (!resolved) {
                        resolved = true
                        try { wv.destroy() } catch (_: Exception) {}
                        cont.resume("")
                    }
                }
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!resolved) {
                        resolved = true
                        try { wv.destroy() } catch (_: Exception) {}
                        try { dialog.dismiss() } catch (_: Exception) {}
                        try { cont.resume("") } catch (_: Exception) {}
                    }
                }, 120000L)

                wrapper.addView(infoBar)
                wrapper.addView(container)
                dialog.show()
                wv.loadUrl(verifyUrl)
            } catch (e: Exception) {
                cont.resume("")
            }
        }
    }
}

suspend fun solveCloudflareInWebView(url: String): String? {
    val ctx = NetflixMirrorProvider.context ?: return null
    return withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            try {
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                val wv = WebView(ctx)
                try { cookieManager.setAcceptThirdPartyCookies(wv, true) } catch (_: NoSuchMethodError) {} catch (_: Exception) {}
                val ws = wv.settings
                ws.javaScriptEnabled = true
                ws.domStorageEnabled = true
                ws.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                ws.userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                ws.mediaPlaybackRequiresUserGesture = false
                wv.webChromeClient = WebChromeClient()

                var resolved = false
                fun extractAndFinish() {
                    if (resolved) return
                    val cookies = cookieManager.getCookie(url) ?: ""
                    val match = Regex("""cf_clearance=([^;]+)""").find(cookies)
                    val cf = match?.groupValues?.get(1)
                    if (!cf.isNullOrEmpty()) {
                        resolved = true
                        try { wv.destroy() } catch (_: Exception) {}
                        try {
                            val tag = wv.tag
                            if (tag is android.app.Dialog) tag.dismiss()
                        } catch (_: Exception) {}
                        cont.resume(cf)
                    }
                }

                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        extractAndFinish()
                        if (!resolved) {
                            val handler = Handler(Looper.getMainLooper())
                            handler.postDelayed(object : Runnable {
                                override fun run() {
                                    if (!resolved) {
                                        extractAndFinish()
                                        if (!resolved) {
                                            handler.postDelayed(this, 1000L)
                                        }
                                    }
                                }
                            }, 1000L)
                        }
                    }
                }

                val dp = ctx.resources.displayMetrics.density
                val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val metrics = Point()
                wm.defaultDisplay.getSize(metrics)
                val params = WindowManager.LayoutParams(
                    (metrics.x * 0.95f).toInt(),
                    (metrics.y * 0.9f).toInt()
                )
                val wrapper = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                val infoBar = TextView(ctx).apply {
                    text = "Solve the Cloudflare captcha"
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
                val container = FrameLayout(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                    )
                    isFocusable = true
                    isFocusableInTouchMode = true
                }
                wv.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                container.addView(wv)

                val dialog = AlertDialog.Builder(ctx)
                    .setView(wrapper)
                    .setCancelable(false)
                    .create()
                dialog.window?.let { win ->
                    win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    win.setLayout(params.width, params.height)
                }
                wv.tag = dialog
                dialog.setOnDismissListener {
                    if (!resolved) {
                        resolved = true
                        try { wv.destroy() } catch (_: Exception) {}
                        cont.resume(null)
                    }
                }
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!resolved) {
                        resolved = true
                        try { wv.destroy() } catch (_: Exception) {}
                        try { dialog.dismiss() } catch (_: Exception) {}
                        try { cont.resume(null) } catch (_: Exception) {}
                    }
                }, 120000L)

                wrapper.addView(infoBar)
                wrapper.addView(container)
                dialog.show()
                wv.loadUrl(url)
            } catch (e: Exception) {
                cont.resume(null)
            }
        }
    }
}
