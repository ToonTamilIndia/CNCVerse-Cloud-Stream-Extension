package com.cncverse

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SubscriptionManagerPlugin : Plugin() {

    companion object {
        const val PREFS_NAME = "CNCVerseSubscription"
        const val KEY_LICENSE_TOKEN = "license_token"
        const val KEY_PLAN = "plan"
        const val KEY_EXPIRES_AT = "expires_at"
        const val KEY_EMAIL = "email"
        const val KEY_MODE = "mode"
    }

    override fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val activity = context as AppCompatActivity
        openSettings = {
            val frag = SubscriptionManagerSettings(this, prefs)
            frag.show(activity.supportFragmentManager, "SubscriptionManager")
        }
    }
}
