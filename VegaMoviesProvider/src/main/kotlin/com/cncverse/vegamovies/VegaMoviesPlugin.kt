package com.cncverse.vegamovies

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class VegaMoviesPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(VegaMoviesProvider())
        registerMainAPI(RogmoviesProvider())
        registerExtractorAPI(VCloud())
    }
}
