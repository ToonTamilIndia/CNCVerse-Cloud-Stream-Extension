package com.cncverse

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class MlsbdPlugin : Plugin() {
    override fun load(context: Context) {
        MlsbdProvider.appContext = context
        MlsbdStorage.init(context)
        registerMainAPI(MlsbdProvider())
        registerExtractorAPI(GDFlix())
        registerExtractorAPI(GDxFiles())
        registerExtractorAPI(MultiCloudLinks())
        registerExtractorAPI(Filepress())
        registerExtractorAPI(HubCloud())
        registerExtractorAPI(Gofile())
        registerExtractorAPI(Filesdl())
        registerExtractorAPI(Shikshakdaak())
        registerExtractorAPI(M4ulinks())
    }
}
