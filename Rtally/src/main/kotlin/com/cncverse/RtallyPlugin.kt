package com.cncverse

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class RtallyPlugin: Plugin() {
    override fun load(context: Context) {
        RtallyProvider.context = context
        registerMainAPI(RtallyProvider())
        registerExtractorAPI(GDFlix())
        registerExtractorAPI(GDFlixVip())
        registerExtractorAPI(HubCloud())
        registerExtractorAPI(GDxFiles())
        registerExtractorAPI(MultiCloudLinks())
        registerExtractorAPI(MultiCloudLinksNew())
        registerExtractorAPI(MultiCloudLinksNew1())
        registerExtractorAPI(MultiCloudLinksNew2())
        registerExtractorAPI(Filepress())
        registerExtractorAPI(FilepressNew1())
        registerExtractorAPI(FilepressNew3())
        registerExtractorAPI(FilepressFilebee())
    }
}
