package com.cncverse.daddylive

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

internal data class Sniffed(val url: String, val headers: Map<String, String>)

internal var snifferContext: WeakReference<Context>? = null

private val streamRequest = Regex("\\.m3u8(\\?|$)")
private val playlistTokenRequest = Regex("/api/playlist-token\\?.*slug=(premium\\d+)")
private val slots = Semaphore(3)

// Loads the player in a hidden WebView and returns the first .m3u8 request it makes,
// together with the Referer/Origin/User-Agent the player used and the embed's cookies.
@SuppressLint("SetJavaScriptEnabled")
internal suspend fun sniffStream(
    pageUrl: String,
    referer: String?,
    timeoutMs: Long = 25_000,
    hostPage: String? = null,
): Sniffed? = slots.withPermit {
    val ctx = snifferContext?.get() ?: return@withPermit null
    withContext(Dispatchers.Main) {
        suspendCoroutine<Sniffed?> { cont ->
            var done = false
            val main = Handler(Looper.getMainLooper())
            try {
                val wv = WebView(ctx)
                val ua = wv.settings.userAgentString
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                }

                fun finish(result: Sniffed?) {
                    main.post {
                        if (done) return@post
                        done = true
                        try {
                            wv.stopLoading()
                            wv.destroy()
                        } catch (_: Exception) {
                        }
                        cont.resume(result)
                    }
                }

                try {
                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(wv, true)
                    }
                } catch (_: Exception) {
                }

                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val url = request.url.toString()
                        playlistTokenRequest.find(url)?.let { SegmentUnwrap.playlistTokenUrls[it.groupValues[1]] = url }
                        if (!done && streamRequest.containsMatchIn(url)) {
                            val base = request.requestHeaders
                                .filterKeys { it.equals("Referer", true) || it.equals("Origin", true) } +
                                ("User-Agent" to ua)
                            val cookie = try {
                                CookieManager.getInstance().getCookie(url)
                            } catch (_: Exception) {
                                null
                            }
                            val headers = base.toMutableMap()
                            if (headers.keys.none { it.equals("Referer", true) }) {
                                val referer = base.entries.firstOrNull { it.key.equals("Origin", true) }?.value?.let { "$it/" }
                                    ?: runCatching { Uri.parse(url).let { "${it.scheme}://${it.host}/" } }.getOrNull()
                                if (referer != null) headers["Referer"] = referer
                            }
                            if (!cookie.isNullOrBlank()) headers["Cookie"] = cookie
                            finish(Sniffed(url, headers))
                        }
                        return null
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        request.isForMainFrame && !request.isRedirect
                }
                main.postDelayed({ finish(null) }, timeoutMs)

                if (hostPage != null) {
                    val src = pageUrl.replace("&", "&amp;").replace("\"", "&quot;")
                    val html = "<!doctype html><html><body style=\"margin:0;background:#000\">" +
                        "<iframe src=\"$src\" style=\"border:0;width:100vw;height:100vh\" " +
                        "allow=\"autoplay; fullscreen; encrypted-media\" allowfullscreen></iframe></body></html>"
                    wv.loadDataWithBaseURL(hostPage, html, "text/html", "utf-8", null)
                } else if (referer != null) {
                    wv.loadUrl(pageUrl, mapOf("Referer" to referer))
                } else {
                    wv.loadUrl(pageUrl)
                }
            } catch (_: Exception) {
                if (!done) {
                    done = true
                    cont.resume(null)
                }
            }
        }
    }
}
