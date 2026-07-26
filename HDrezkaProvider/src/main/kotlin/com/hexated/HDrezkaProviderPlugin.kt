
package com.hexated

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class HDrezkaProviderPlugin: Plugin() {
    override fun load(context: Context) {
        HDrezkaProvider.context = context
        registerMainAPI(HDrezkaProvider())
    }
}