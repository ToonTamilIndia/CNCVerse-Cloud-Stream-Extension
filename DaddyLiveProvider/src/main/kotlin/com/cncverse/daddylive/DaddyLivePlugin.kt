package com.cncverse.daddylive

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import java.lang.ref.WeakReference

@CloudstreamPlugin
class DaddyLivePlugin : Plugin() {
    override fun load(context: Context) {
        snifferContext = WeakReference(context)
        registerMainAPI(DaddyLiveProvider())
    }
}
