package com.cncverse

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SubscriptionManagerSettings(
    private val plugin: SubscriptionManagerPlugin,
    private val prefs: SharedPreferences
) : BottomSheetDialogFragment() {

    data class VerifyResult(
        val valid: Boolean,
        val plan: String?,
        val expiresAt: Long,
        val email: String?,
        val errorMsg: String?
    )

    companion object {
        private const val API_URL = "https://cncverse-subscription-api.cncverse.workers.dev"
        private const val SIGNIN_URL = "https://cncverse-sub.pages.dev/signin.html"
        private const val SUBSCRIBE_URL = "https://cncverse-sub.pages.dev"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val res: Resources? = plugin.resources
        val layoutId = res?.getIdentifier("subscription_settings", "layout", "com.cncverse")
            ?: return null
        return inflater.inflate(res.getLayout(layoutId), container, false)
    }

    @RequiresApi(23)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
    }

    private fun getDrawable(name: String): Drawable? {
        val res = plugin.resources ?: return null
        val id = res.getIdentifier(name, "drawable", "com.cncverse")
        if (id == 0) return null
        return ResourcesCompat.getDrawable(res, id, null)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : View> findViewByName(view: View, name: String): T? {
        val res = plugin.resources ?: return null
        val id = res.getIdentifier(name, "id", "com.cncverse")
        if (id == 0) return null
        return view.findViewById(id) as? T
    }

    private fun bindViews(view: View) {
        val modeBadge = findViewByName<TextView>(view, "sm_mode_badge")
        val statusText = findViewByName<TextView>(view, "sm_status_text")
        val planText = findViewByName<TextView>(view, "sm_plan_text")
        val tokenField = findViewByName<TextView>(view, "sm_token_field")
        val pasteBtn = findViewByName<Button>(view, "sm_paste_btn")
        val subscribeBtn = findViewByName<Button>(view, "sm_subscribe_btn")
        val signinBtn = findViewByName<Button>(view, "sm_signin_btn")
        val removeBtn = findViewByName<Button>(view, "sm_remove_btn")
        val closeBtn = findViewByName<Button>(view, "sm_close_btn")

        refreshStatus(modeBadge, statusText, planText, tokenField)

        pasteBtn?.setOnClickListener {
            val token = getClipboardText()
            if (token.isNullOrBlank()) {
                CommonActivity.showToast("Clipboard is empty — copy your token from the CNCVerse website first")
                return@setOnClickListener
            }
            pasteBtn.isEnabled = false
            pasteBtn.text = "Verifying…"
            scope.launch {
                val result = verifyToken(token)
                withContext(Dispatchers.Main) {
                    pasteBtn.isEnabled = true
                    pasteBtn.text = "Paste & Verify"
                    if (result.valid) {
                        prefs.edit()
                            .putString(SubscriptionManagerPlugin.KEY_LICENSE_TOKEN, token)
                            .putString(SubscriptionManagerPlugin.KEY_PLAN, result.plan ?: "")
                            .putLong(SubscriptionManagerPlugin.KEY_EXPIRES_AT, result.expiresAt)
                            .putString(SubscriptionManagerPlugin.KEY_EMAIL, result.email ?: "")
                            .putString(SubscriptionManagerPlugin.KEY_MODE, "subscription")
                            .apply()
                        refreshStatus(modeBadge, statusText, planText, tokenField)
                        CommonActivity.showToast("✅ Subscription active! Ads removed.")
                    } else {
                        CommonActivity.showToast("❌ ${result.errorMsg ?: "Token verification failed"}")
                    }
                }
            }
        }

        subscribeBtn?.setOnClickListener {
            openBrowser(SUBSCRIBE_URL)
        }

        signinBtn?.setOnClickListener {
            openBrowser(SIGNIN_URL)
            CommonActivity.showToast("Sign in, copy your token, then come back and tap Paste & Verify")
        }

        removeBtn?.setOnClickListener {
            prefs.edit()
                .remove(SubscriptionManagerPlugin.KEY_LICENSE_TOKEN)
                .remove(SubscriptionManagerPlugin.KEY_PLAN)
                .remove(SubscriptionManagerPlugin.KEY_EXPIRES_AT)
                .remove(SubscriptionManagerPlugin.KEY_EMAIL)
                .putString(SubscriptionManagerPlugin.KEY_MODE, "ads")
                .apply()
            refreshStatus(modeBadge, statusText, planText, tokenField)
            CommonActivity.showToast("Account removed — switched to Ads mode")
        }

        closeBtn?.setOnClickListener { dismiss() }
    }

    private fun refreshStatus(
        modeBadge: TextView?,
        statusText: TextView?,
        planText: TextView?,
        tokenField: TextView?
    ) {
        val mode = prefs.getString(SubscriptionManagerPlugin.KEY_MODE, "ads") ?: "ads"
        val token = prefs.getString(SubscriptionManagerPlugin.KEY_LICENSE_TOKEN, null)
        val plan = prefs.getString(SubscriptionManagerPlugin.KEY_PLAN, null)
        val expiresAt = prefs.getLong(SubscriptionManagerPlugin.KEY_EXPIRES_AT, 0L)
        val email = prefs.getString(SubscriptionManagerPlugin.KEY_EMAIL, null)

        if (mode == "subscription" && token != null) {
            val nowSeconds = System.currentTimeMillis() / 1000
            val isExpired = expiresAt > 0 && nowSeconds >= expiresAt
            val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

            if (isExpired) {
                modeBadge?.text = "❌ Subscription Expired"
                modeBadge?.setBackgroundResource(android.R.color.holo_red_dark)
                val expiryStr = sdf.format(Date(expiresAt * 1000))
                statusText?.text = if (email != null) "Signed in as $email" else "Account linked"
                planText?.text = "${capitalise(plan ?: "Active")} Plan · Expired on $expiryStr"
            } else {
                modeBadge?.text = "✅ Subscription Mode"
                modeBadge?.setBackgroundResource(android.R.color.holo_green_dark)
                val expiryStr = if (expiresAt > 0) sdf.format(Date(expiresAt * 1000)) else "Unknown"
                statusText?.text = if (email != null) "Signed in as $email" else "Account linked"
                planText?.text = "${capitalise(plan ?: "Active")} Plan · Expires $expiryStr"
            }

            tokenField?.text = if (token.length > 8) {
                "••••••••" + token.takeLast(8)
            } else {
                token
            }
        } else {
            modeBadge?.text = "📺 Ads Mode"
            modeBadge?.setBackgroundResource(android.R.color.holo_orange_dark)
            statusText?.text = "No subscription linked"
            planText?.text = "Subscribe to remove ads — from ₹30/month"
            tokenField?.text = "No token"
        }
    }

    private fun capitalise(str: String): String {
        if (str.isEmpty()) return str
        return str.first().uppercase() + str.substring(1)
    }

    private fun getClipboardText(): String? {
        val cm = requireContext().getSystemService("clipboard") as? ClipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        val item = clip.getItemAt(0) ?: return null
        val text = item.text?.toString()?.trim() ?: return null
        return text.ifBlank { null }
    }

    private fun openBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            CommonActivity.showToast("Could not open browser")
        }
    }

    private suspend fun verifyToken(token: String): VerifyResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("$API_URL/license/verify")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            val body = """{"license_token":"$token"}"""
            conn.outputStream.use { os: OutputStream ->
                os.write(body.toByteArray(Charsets.UTF_8))
            }

            val responseCode = conn.responseCode
            val responseBody = try {
                conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            } catch (_: Exception) {
                conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
            }

            val json = JSONObject(responseBody)
            if (responseCode == 200 && json.optBoolean("valid", false)) {
                val p = json.optString(SubscriptionManagerPlugin.KEY_PLAN).let {
                    if (it.isBlank()) null else it
                }
                val expires = json.optLong(SubscriptionManagerPlugin.KEY_EXPIRES_AT, 0L)
                val e = json.optString(SubscriptionManagerPlugin.KEY_EMAIL).let {
                    if (it.isBlank()) null else it
                }
                VerifyResult(valid = true, plan = p, expiresAt = expires, email = e, errorMsg = null)
            } else {
                val err = json.optString("error").let {
                    if (it.isBlank()) null else it
                } ?: "Invalid or expired token"
                VerifyResult(valid = false, plan = null, expiresAt = 0L, email = null, errorMsg = err)
            }
        } catch (e: Exception) {
            VerifyResult(valid = false, plan = null, expiresAt = 0L, email = null, errorMsg = "Network error: ${e.message}")
        }
    }
}
