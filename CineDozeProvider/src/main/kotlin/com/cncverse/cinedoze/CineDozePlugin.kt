package com.cncverse.cinedoze

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CineDozePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CineDozeProvider())
        registerExtractorAPI(GDFlix())
        registerExtractorAPI(GDxFiles())
        registerExtractorAPI(MultiCloudLinks())
        registerExtractorAPI(Filepress())
        registerExtractorAPI(HubCloud())
        registerExtractorAPI(Gofile())
        registerExtractorAPI(Filesdl())
    }
}
