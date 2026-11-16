package com.cncverse

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.runBlocking

@CloudstreamPlugin
class PlayFyPlugin : Plugin() {

    private val sharedPref = activity?.getSharedPreferences("PlayFy", Context.MODE_PRIVATE)
    private var iptvProviders: List<Map<String, Any>> = emptyList()

    override fun load(context: Context) {
        PlayFy.context = context
        PlayFyLiveEvents.context = context

        registerMainAPI(PlayFyLiveEvents("⚡ PlayFy Live Events", "live"))
        registerMainAPI(PlayFyLiveEvents("🎬 PlayFy Highlights", "highlights"))

        iptvProviders = runBlocking {
            PlayFyProviderManager.fetchProviders()
        }

        val selected = iptvProviders.filter { provider ->
            val title = provider["title"] as? String
            title != null && (sharedPref?.getBoolean(title, false) ?: false)
        }

        selected.forEach { provider ->
            val title = provider["title"] as String
            val catLink = provider["catLink"] as String
            registerMainAPI(PlayFy("📺 $title", catLink))
        }

        val act = context as? AppCompatActivity ?: return
        openSettings = {
            val frag = PlayFySettings(this, sharedPref, iptvProviders.mapNotNull { it["title"] as? String })
            frag.show(act.supportFragmentManager, "PlayFySettings")
        }
    }
}
