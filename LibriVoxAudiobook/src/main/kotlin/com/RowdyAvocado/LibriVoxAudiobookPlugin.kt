package com.RowdyAvocado

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class LibriVoxAudiobookPlugin : Plugin() {
    override fun load(context: Context) {
        LibriVoxAudiobook.context = context
        registerMainAPI(LibriVoxAudiobook())
    }
}
