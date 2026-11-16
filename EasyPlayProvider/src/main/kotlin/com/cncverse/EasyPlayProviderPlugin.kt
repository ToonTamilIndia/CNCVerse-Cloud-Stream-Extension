package com.cncverse

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class EasyPlayProviderPlugin : Plugin() {
    override fun load(context: Context) {
        EasyPlayProvider.context = context
        registerMainAPI(EasyPlayProvider())
    }
}
