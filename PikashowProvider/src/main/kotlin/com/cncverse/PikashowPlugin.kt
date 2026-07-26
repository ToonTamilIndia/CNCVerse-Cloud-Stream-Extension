package com.cncverse

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PikashowPlugin : Plugin() {
    override fun load(context: Context) {
        PikashowProvider.context = context
        registerMainAPI(PikashowProvider())
    }
}
