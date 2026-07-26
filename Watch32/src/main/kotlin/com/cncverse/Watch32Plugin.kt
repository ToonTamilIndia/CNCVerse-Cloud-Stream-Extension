package com.cncverse

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Watch32Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Watch32Provider())
        registerExtractorAPI(Videostr())
    }
}
