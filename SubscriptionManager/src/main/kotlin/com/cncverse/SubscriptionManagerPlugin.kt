package com.cncverse

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * Local-only settings entry point for this ad-free fork.
 *
 * Upstream shipped a licence/subscription client here: it POSTed a token to a
 * remote verification endpoint, stored the result in SharedPreferences and
 * offered "Subscribe"/"Sign in" buttons that opened external promo pages.
 * All of that is gone. Nothing in this plugin performs network I/O, checks a
 * licence, gates a feature or redirects to a browser.
 */
@CloudstreamPlugin
class SubscriptionManagerPlugin : Plugin() {

    companion object {
        const val PREFS_NAME = "CNCVerseSubscription"

        // Retained for on-disk compatibility with preferences written by older
        // builds. They are cleared on load and never used for gating.
        const val KEY_LICENSE_TOKEN = "license_token"
        const val KEY_PLAN = "plan"
        const val KEY_EXPIRES_AT = "expires_at"
        const val KEY_EMAIL = "email"
        const val KEY_MODE = "mode"
    }

    override fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Purge any licence data left behind by an upstream build.
        prefs.edit()
            .remove(KEY_LICENSE_TOKEN)
            .remove(KEY_PLAN)
            .remove(KEY_EXPIRES_AT)
            .remove(KEY_EMAIL)
            .remove(KEY_MODE)
            .apply()

        val activity = context as? AppCompatActivity ?: return
        openSettings = {
            val frag = SubscriptionManagerSettings(this)
            frag.show(activity.supportFragmentManager, "SubscriptionManager")
        }
    }
}
