package com.cncverse.cinevood

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

internal var dialogContext: WeakReference<Context>? = null

private val clearances = mutableMapOf<String, String>()
private val solveLock = Mutex()

internal fun clearanceFor(url: String): String? = Uri.parse(url).host?.let { clearances[it] }

internal fun dropClearance(url: String) {
    Uri.parse(url).host?.let { clearances.remove(it) }
}

// Shows the challenge to the user (touch, or a D-pad driven cursor on TV) and returns cf_clearance.
internal suspend fun solveCloudflareInWebView(url: String): String? = solveLock.withLock {
    clearanceFor(url)?.let { return@withLock it }
    val ctx = dialogContext?.get() ?: return@withLock null
    val host = Uri.parse(url).host ?: return@withLock null
    val cf = withContext(Dispatchers.Main) {
        suspendCoroutine<String?> { cont ->
            var finished = false
            var dialog: AlertDialog? = null
            val wv = WebView(ctx)
            val cookies = CookieManager.getInstance()

            fun finish(result: String?) {
                if (finished) return
                finished = true
                try {
                    dialog?.dismiss()
                } catch (_: Exception) {
                }
                try {
                    wv.destroy()
                } catch (_: Exception) {
                }
                cont.resume(result)
            }

            fun checkCookie() {
                Regex("cf_clearance=([^;]+)").find(cookies.getCookie(url) ?: "")
                    ?.groupValues?.get(1)?.let { finish(it) }
            }

            try {
                cookies.setAcceptCookie(true)
                cookies.setAcceptThirdPartyCookies(wv, true)
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = browserHeaders.getValue("User-Agent")
                }
                wv.webChromeClient = WebChromeClient()
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, pageUrl: String?) {
                        checkCookie()
                    }
                }
                val poll = object : Runnable {
                    override fun run() {
                        if (finished) return
                        checkCookie()
                        Handler(Looper.getMainLooper()).postDelayed(this, 1000L)
                    }
                }

                val metrics = ctx.resources.displayMetrics
                val dp = metrics.density
                val width = (metrics.widthPixels * 0.95f).toInt()
                val height = (metrics.heightPixels * 0.9f).toInt()
                val tv = isLayout(TV)

                val bar = TextView(ctx).apply {
                    text = if (tv) "CineVood: pass the Cloudflare check on $host. D-pad moves the cursor, OK clicks."
                    else "CineVood: pass the Cloudflare check on $host to continue."
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#1A1A2E"))
                    textSize = 13f
                    val p = (10 * dp).toInt()
                    setPadding(p, p, p, p)
                }
                val container = FrameLayout(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    ).apply { weight = 1f }
                    isFocusable = true
                    isFocusableInTouchMode = true
                }
                wv.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                container.addView(wv)

                if (tv) {
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
                    var cx = width / 2f
                    var cy = height / 2f

                    fun place() {
                        cursor.translationX = cx - size / 2f
                        cursor.translationY = cy - size / 2f
                    }

                    container.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                        override fun onGlobalLayout() {
                            container.viewTreeObserver.removeOnGlobalLayoutListener(this)
                            cx = container.width / 2f
                            cy = container.height / 2f
                            place()
                        }
                    })
                    container.setOnKeyListener { _, keyCode, event ->
                        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                        val step = dp * 10f
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP -> cy = (cy - step).coerceAtLeast(0f)
                            KeyEvent.KEYCODE_DPAD_DOWN -> cy = (cy + step).coerceAtMost(container.height.toFloat())
                            KeyEvent.KEYCODE_DPAD_LEFT -> cx = (cx - step).coerceAtLeast(0f)
                            KeyEvent.KEYCODE_DPAD_RIGHT -> cx = (cx + step).coerceAtMost(container.width.toFloat())
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                                val t = SystemClock.uptimeMillis()
                                val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, cx, cy, 0)
                                val up = MotionEvent.obtain(t, t + 120L, MotionEvent.ACTION_UP, cx, cy, 0)
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

                val root = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    minimumWidth = width
                    minimumHeight = height
                    addView(bar, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ))
                    addView(container)
                }
                dialog = AlertDialog.Builder(ctx).setView(root).create().apply {
                    window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    setOnDismissListener { finish(null) }
                    show()
                    window?.setLayout(width, height)
                }
                Handler(Looper.getMainLooper()).postDelayed({ finish(null) }, 120_000L)
                Handler(Looper.getMainLooper()).postDelayed(poll, 1000L)
                wv.loadUrl(url)
            } catch (_: Exception) {
                finish(null)
            }
        }
    }
    cf?.also { clearances[host] = it }
}
