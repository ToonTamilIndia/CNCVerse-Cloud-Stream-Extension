package com.cncverse.ppv

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import java.lang.ref.WeakReference

@CloudstreamPlugin
class PPVPlugin : Plugin() {
    override fun load(context: Context) {
        snifferContext = WeakReference(context)
        registerMainAPI(PPVProvider())
    }
}
