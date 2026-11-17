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
import com.lagradost.api.Log
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
import java.util.Base64
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

private fun validSavedCookie(): String? {
    val (savedCookie, savedTimestamp) = NetflixMirrorStorage.getCookie()
    return if (!savedCookie.isNullOrEmpty() && System.currentTimeMillis() - savedTimestamp < 39600000) {
        savedCookie
    } else null
}

private val bypassMutex = Mutex()

@Volatile
private var lastVerifyFailAt: Long = 0L

suspend fun bypass(mainUrl: String): String {
    validSavedCookie()?.let { return it }
    bypassMutex.withLock {
        validSavedCookie()?.let { return it }
        if (System.currentTimeMillis() - lastVerifyFailAt < 60000) {
            throw Exception("Failed to verify cookie")
        }
        try {
            return verifyNewCookie(mainUrl)
        } catch (e: Exception) {
            val wvCookie = solveBypassInWebView()
            if (wvCookie.isNotEmpty()) {
                NetflixMirrorStorage.saveCookie(wvCookie)
                return wvCookie
            }
            lastVerifyFailAt = System.currentTimeMillis()
            throw e
        }
    }
}

private fun adClickUrl(html: String): String {
    fun jsVar(name: String): String? =
        Regex("""var\s+$name\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1)

    val sub = jsVar("Vsite2") ?: "userver"
    val query = jsVar("Qury") ?: "ffr455"
    val site = Regex("""var\s+Msite\s*=\s*([^;]+);""").find(html)?.groupValues?.get(1)
        ?.let { Regex("""\s*\+?\s*Vsite2\s*\+?\s*""").replace(it, sub) }
        ?.replace("\"", "")
        ?.replace("'", "")
        ?.trim()
        ?.takeIf { it.startsWith("http") }
        ?: "https://$sub.net52.cc/"
    return "$site?$query="
}

private suspend fun verifyNewCookie(mainUrl: String): String {
    try {
        val homeRes = app.get(
            "$mainUrl/mobile/home?app=1",
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 12; RMX2117 Build/SP1A.210812.016; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/147.0.7727.55 Mobile Safari/537.36 /OS.Gatu v3.0",
                "X-Requested-With" to "app.netmirror.netmirrornew"
            )
        )
        val homeHtml = homeRes.text
        val addhash = Regex("""data-addhash="([^"]+)"""").find(homeHtml)?.groupValues?.get(1) ?: ""
        Log.d("NF", "addhash: $addhash")
        try {
            app.get(adClickUrl(homeHtml) + addhash + "&a=y&t=" + Math.random())
        } catch (_: Exception) {
        }

        val body = FormBody.Builder()
            .addEncoded("verify", addhash)
            .build()
        var count = 0
        while (true) {
            delay(10000)
            val res = app.post(
                "$mainUrl/mobile/verify2.php",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 12; RMX2117 Build/SP1A.210812.016; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/147.0.7727.55 Mobile Safari/537.36 /OS.Gatu v3.0",
                    "X-Requested-With" to "XMLHttpRequest"
                ),
                requestBody = body
            )
            val text = res.text
            Log.d("NF", "verifyCheck: $text")
            count++
            if (count > 7) throw Exception("Failed to verify cookie")
            if (text.contains("\"statusup\":\"All Done\"")) {
                val newCookie = res.cookies["t_hash_t"] ?: ""
                if (newCookie.isNotEmpty()) {
                    NetflixMirrorStorage.saveCookie(newCookie)
                }
                Log.d("NF", "newCookie: $newCookie")
                return newCookie
            }
        }
    } catch (e: Exception) {
        NetflixMirrorStorage.clearCookie()
        throw e
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
