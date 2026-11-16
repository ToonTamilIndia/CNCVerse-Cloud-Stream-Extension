package com.horis.cncverse

import android.app.AlertDialog
import android.graphics.Color
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
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.ui.settings.Globals
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ResponseParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import java.util.Base64
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.reflect.KClass

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

val app = Requests(responseParser = JSONParser).apply {
    defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"
    )
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

private val bypassMutex = Mutex()

private fun savedCookie(): String? {
    val (savedCookie, savedTimestamp) = NetflixMirrorStorage.getCookie()
    return savedCookie?.takeIf {
        it.isNotEmpty() && System.currentTimeMillis() - savedTimestamp < 54000000
    }
}

suspend fun bypass(mainUrl: String): String {
    savedCookie()?.let { return it }
    return bypassMutex.withLock {
        savedCookie()?.let { return@withLock it }
        val newCookie = verifyBypass()
            .ifEmpty { netMirrorMobileBypass(mainUrl) }
            .ifEmpty { solveBypassInWebView() }
        if (newCookie.isNotEmpty()) {
            NetflixMirrorStorage.saveCookie(newCookie)
        } else {
            NetflixMirrorStorage.clearCookie()
        }
        newCookie
    }
}

private fun verifyBypass(): String {
    val headers = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Encoding" to "gzip, deflate, br, zstd",
        "Accept-Language" to "en-US,en;q=0.9",
        "Cache-Control" to "max-age=0",
        "Connection" to "keep-alive",
        "Content-Type" to "application/x-www-form-urlencoded",
        "Origin" to "https://net22.cc",
        "Referer" to "https://net22.cc/verify2",
        "sec-ch-ua" to "\"Google Chrome\";v=\"147\", \"Not.A/Brand\";v=\"8\", \"Chromium\";v=\"147\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Windows\"",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"
    )
    return try {
        val body = FormBody.Builder()
            .add("g-recaptcha-response", UUID.randomUUID().toString())
            .build()
        val client = app.baseClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val request = Request.Builder()
            .url("https://net52.cc/verify.php")
            .post(body)
            .apply { headers.forEach { (key, value) -> addHeader(key, value) } }
            .build()
        client.newCall(request).execute().use { response ->
            response.headers("Set-Cookie")
                .firstOrNull { it.startsWith("t_hash_t=") }
                ?.substringAfter("t_hash_t=")
                ?.substringBefore(";")
                .orEmpty()
        }
    } catch (_: Exception) {
        ""
    }
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

        // 1. Home page -> data-addhash. Some mirrors redirect /mobile/home to
        // dead domains (net77 -> expired net50); net52 serves it directly.
        var addhash = ""
        for (homeBase in listOf(mainUrl.trimEnd('/'), "https://net52.cc")) {
            try {
                val homeRes = app.get("$homeBase/mobile/home?app=1", headers = homeHeaders, timeout = 30)
                if (homeRes.code != 200) continue
                addhash = Regex("""data-addhash="([^"]+)"""").find(homeRes.text)?.groupValues?.get(1).orEmpty()
                if (addhash.isNotEmpty()) break
            } catch (_: Exception) { continue }
        }
        if (addhash.isEmpty()) return ""

        // 2. Trigger verification helper; it redirects through an ad chain whose
        // completion registers the click against our addhash.
        // NOTE: the userver subdomain only exists on net52 (userver.net77.cc has
        // no DNS record); the backend session store is shared across mirrors,
        // so triggering via net52 works regardless of which mirror served home.
        for (triggerHost in listOf("userver.net52.cc", "userver.$host")) {
            try {
                app.get(
                    "https://$triggerHost/?hee5=${java.net.URLEncoder.encode(addhash, "UTF-8")}&a=y&t=${System.currentTimeMillis()}",
                    headers = mapOf(
                        "User-Agent" to NETMIRROR_MOBILE_UA,
                        "Referer" to "$mainUrl/mobile/home?app=1"
                    ),
                    timeout = 60
                )
                break
            } catch (_: Exception) {
                continue
            }
        }

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
    val (savedApiBase, savedTimestamp) = NetflixMirrorStorage.getApiBase()
    if (!savedApiBase.isNullOrEmpty() && System.currentTimeMillis() - savedTimestamp < 86400000) {
        resolvedApiUrl = savedApiBase
        return resolvedApiUrl
    }
    for (encoded in newTvDomains) {
        val base = decodeBase64(encoded).trimEnd('/')
        try {
            val response = app.get("$base/checknewtv.php", headers = newTvBaseHeaders)
                .parsed<NewTvTokenResponse>()
            val tokenHash = response.token_hash
            if (!tokenHash.isNullOrBlank()) {
                resolvedApiUrl = decodeBase64(tokenHash).trimEnd('/')
                NetflixMirrorStorage.saveApiBase(resolvedApiUrl)
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

suspend fun getNewTvUserToken(apiBase: String, ott: String, forceRefresh: Boolean = false): String {
    val otpHeaders = mapOf(
        "accept" to "application/json, text/plain, */*",
        "cache-control" to "no-cache, no-store, must-revalidate",
        "Connection" to "Keep-Alive",
        "expires" to "0",
        "otp" to "111111",
        "pragma" to "no-cache",
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0 /OS.Gatu v1.0"
    )
    return app.get("$apiBase/newtv/otp.php", otpHeaders)
        .parsedSafe<NewTvOtpResponse>()?.usertoken.orEmpty()
}

suspend fun solveBypassInWebView(): String {
    val ctx = NetflixMirrorProvider.context ?: return ""
    // Mirror set: the page may redirect between mirrors; t_hash_t lands on
    // whichever domain finally served the challenge, so we scan them all.
    val cookieUrls = mutableListOf("https://net77.cc", "https://net52.cc", "https://net50.cc")
    var lastPageHost = ""
    fun addCookieUrl(url: String?) {
        val root = url?.let {
            Regex("""https?://([^/]+)""").find(it)?.groupValues?.get(1)
        }?.lowercase() ?: return
        if (root.isNotEmpty() && !root.contains("cloudflare") && "https://$root" !in cookieUrls &&
            (root.contains("net") || root.contains("mirror"))
        ) {
            lastPageHost = root
            cookieUrls.add("https://$root")
        }
    }
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
                    // t_hash_t lands on whichever mirror domain finally served the
                    // challenge (net77 redirects to net50/net52), so scan them all.
                    for (cu in cookieUrls) {
                        val cookies = try { cookieManager.getCookie(cu) } catch (_: Exception) { null } ?: ""
                        val tHash = Regex("""t_hash_t=([^;]+)""").find(cookies)?.groupValues?.get(1)
                        if (!tHash.isNullOrEmpty()) {
                            resolved = true
                            try { wv.destroy() } catch (_: Exception) {}
                            try {
                                val tag = wv.tag
                                if (tag is android.app.Dialog) tag.dismiss()
                            } catch (_: Exception) {}
                            cont.resume(tHash)
                            return
                        }
                    }
                }

                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        addCookieUrl(url)
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
                // Split-view/multi-window safe sizing: defaultDisplay returns the
                // FULL screen while the app window may be half; displayMetrics of
                // the context reflect the actual window configuration.
                val winW = ctx.resources.displayMetrics.widthPixels
                val winH = ctx.resources.displayMetrics.heightPixels
                if (winW <= 0 || winH <= 0) {
                    try { cont.resume("") } catch (_: Exception) {}
                    return@suspendCancellableCoroutine
                }
                val params = WindowManager.LayoutParams(
                    (winW * 0.95f).toInt(),
                    (winH * 0.9f).toInt()
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

                    val cursorX = (winW * 0.95f) / 2f
                    val cursorY = (winH * 0.9f) / 2f

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
                // Turnstile needs a focused view; in split-view the dialog window
                // does not get focus automatically, causing endless challenge loops.
                wv.requestFocus()
                Handler(Looper.getMainLooper()).postDelayed({ try { wv.requestFocus() } catch (_: Exception) {} }, 500L)
                Handler(Looper.getMainLooper()).postDelayed({ try { wv.requestFocus() } catch (_: Exception) {} }, 2500L)
                wv.loadUrl("https://net77.cc/verify2")
            } catch (e: Exception) {
                cont.resume("")
            }
        }
    }
}
