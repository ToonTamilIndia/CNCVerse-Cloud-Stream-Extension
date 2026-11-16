package com.cncverse.ntv

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

internal data class Sniffed(val url: String, val headers: Map<String, String>)

internal var snifferContext: WeakReference<Context>? = null

private val streamRequest = Regex("""(\.m3u8|/manifest\.ts)(\?|$)""")

// At most three hidden WebViews at once
private val slots = Semaphore(3)

// Service worker requests never reach WebViewClient, so they are routed through a global client
private val swListeners = CopyOnWriteArraySet<(WebResourceRequest) -> Unit>()
private val swTurn = Mutex()
private val swHooked = AtomicBoolean(false)

private fun hookServiceWorkers() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !swHooked.compareAndSet(false, true)) return
    runCatching {
        ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
            override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                swListeners.forEach { runCatching { it(request) } }
                return null
            }
        })
    }.onFailure { swHooked.set(false) }
}

@SuppressLint("SetJavaScriptEnabled")
internal suspend fun sniffStream(
    pageUrl: String,
    referer: String?,
    timeoutMs: Long = 25_000L,
    hostPage: String? = null,
    html: String? = null,
    rewrite: ((String) -> ((String) -> String)?)? = null,
    serviceWorker: Boolean = false,
): Sniffed? {
    if (!serviceWorker) return sniffOnce(pageUrl, referer, timeoutMs, hostPage, html, rewrite, false)
    return swTurn.withLock { sniffOnce(pageUrl, referer, timeoutMs, hostPage, html, rewrite, true) }
}

@SuppressLint("SetJavaScriptEnabled")
private suspend fun sniffOnce(
    pageUrl: String,
    referer: String?,
    timeoutMs: Long,
    hostPage: String?,
    html: String?,
    rewrite: ((String) -> ((String) -> String)?)?,
    serviceWorker: Boolean,
): Sniffed? = slots.withPermit {
    val ctx = snifferContext?.get() ?: return@withPermit null
    withContext(Dispatchers.Main) {
        suspendCoroutine<Sniffed?> { cont ->
            var done = false
            val main = Handler(Looper.getMainLooper())
            val wv = try {
                WebView(ctx)
            } catch (e: Exception) {
                if (!done) {
                    done = true
                    cont.resume(null)
                }
                return@suspendCoroutine
            }
            var swListener: ((WebResourceRequest) -> Unit)? = null

            // Every resume goes through the done flag so a late timeout or capture cannot resume twice
            fun release(result: Sniffed?) {
                if (done) return
                done = true
                swListener?.let { swListeners.remove(it) }
                try {
                    wv.stopLoading()
                    wv.destroy()
                } catch (_: Exception) {
                }
                cont.resume(result)
            }

            fun finish(result: Sniffed?) {
                main.post { release(result) }
            }

            try {
                val ua = wv.settings.userAgentString

                fun capture(request: WebResourceRequest) {
                    val url = request.url.toString()
                    if (!done && streamRequest.containsMatchIn(url)) {
                        val headers = request.requestHeaders.filterKeys {
                            it.equals("Referer", true) || it.equals("Origin", true)
                        } + ("User-Agent" to ua)
                        finish(Sniffed(url, headers))
                    }
                }

                if (serviceWorker) hookServiceWorkers()
                if (serviceWorker) {
                    val listener: (WebResourceRequest) -> Unit = { capture(it) }
                    swListeners.add(listener)
                    swListener = listener
                }

                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                }
                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        val url = request.url.toString()
                        if (html != null && url == pageUrl) {
                            return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html.toByteArray()))
                        }
                        if (request.method == "GET") {
                            rewrite?.invoke(url)?.let { edit ->
                                fetchEdited(request, ua, edit)?.let { return it }
                            }
                        }
                        capture(request)
                        return null
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        return request.isForMainFrame && !request.isRedirect
                    }
                }
                try {
                    wv.layout(0, 0, 1280, 720)
                } catch (_: Exception) {
                }
                main.postDelayed({ finish(null) }, timeoutMs)

                when {
                    html != null && hostPage == null ->
                        wv.loadDataWithBaseURL(pageUrl, html, "text/html", "utf-8", null)
                    hostPage != null -> {
                        val src = pageUrl.replace("&", "&amp;").replace("\"", "&quot;")
                        val page = "<!doctype html><html><body style=\"margin:0;background:#000\">" +
                            "<iframe src=\"$src\" style=\"border:0;width:100vw;height:100vh\" " +
                            "allow=\"autoplay; fullscreen; encrypted-media\" allowfullscreen></iframe></body></html>"
                        wv.loadDataWithBaseURL(hostPage, page, "text/html", "utf-8", null)
                    }
                    referer != null -> wv.loadUrl(pageUrl, mapOf("Referer" to referer))
                    else -> wv.loadUrl(pageUrl)
                }
            } catch (e: Exception) {
                release(null)
            }
        }
    }
}

// Re-fetches a page outside the WebView so its markup can be edited before the WebView renders it
private fun fetchEdited(
    request: WebResourceRequest,
    ua: String,
    edit: (String) -> String
): WebResourceResponse? = runCatching {
    val url = request.url.toString()
    val cookies = CookieManager.getInstance()
    val builder = Request.Builder().url(url).get()
    request.requestHeaders.forEach { (k, v) ->
        if (!k.equals("Accept-Encoding", true)) builder.header(k, v)
    }
    builder.header("User-Agent", ua)
    cookies.getCookie(url)?.let { builder.header("Cookie", it) }
    app.baseClient.newCall(builder.build()).execute().use { res ->
        if (!res.isSuccessful) return@use null
        res.headers("Set-Cookie").forEach { cookies.setCookie(url, it) }
        val body = res.body?.string() ?: return@use null
        WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(edit(body).toByteArray()))
    }
}.getOrNull()
