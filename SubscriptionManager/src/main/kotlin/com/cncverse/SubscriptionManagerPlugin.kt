package com.cncverse

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SubscriptionManagerPlugin : Plugin() {

    override fun load(context: Context) {
        val activity = context as AppCompatActivity
        openSettings = {
            val frag = SubscriptionManagerSettings(this)
            frag.show(activity.supportFragmentManager, "SubscriptionManager")
        }
    }
}
