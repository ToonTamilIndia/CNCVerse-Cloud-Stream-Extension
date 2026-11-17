package com.cncverse.ppv

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
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
private val slots = Semaphore(3)

/**
 * Loads the embed page in a hidden WebView and returns the first .m3u8 request it makes,
 * together with the Referer/Origin/User-Agent the player needs to replay it.
 */
@SuppressLint("SetJavaScriptEnabled")
internal suspend fun sniffStream(pageUrl: String, referer: String?, timeoutMs: Long = 25_000): Sniffed? =
    slots.withPermit {
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

                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest
                        ): WebResourceResponse? {
                            val url = request.url.toString()
                            if (!done && streamRequest.containsMatchIn(url)) {
                                val headers = request.requestHeaders.filterKeys {
                                    it.equals("Referer", true) || it.equals("Origin", true)
                                } + ("User-Agent" to ua)
                                finish(Sniffed(url, headers))
                            }
                            return null
                        }

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                            request.isForMainFrame && !request.isRedirect
                    }

                    main.postDelayed({ finish(null) }, timeoutMs)
                    if (referer != null) wv.loadUrl(pageUrl, mapOf("Referer" to referer)) else wv.loadUrl(pageUrl)
                } catch (_: Exception) {
                    if (!done) {
                        done = true
                        cont.resume(null)
                    }
                }
            }
        }
    }
