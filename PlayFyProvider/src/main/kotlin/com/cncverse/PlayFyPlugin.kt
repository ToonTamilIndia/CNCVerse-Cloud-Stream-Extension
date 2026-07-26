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

    override fun load(context: Context) {
        PlayFy.context = context
        PlayFyLiveEvents.context = context

        registerMainAPI(PlayFyLiveEvents())

        val categoryProviders: List<Map<String, Any>> = runBlocking {
            PlayFyProviderManager.fetchProviders()
        }

        val selectedProviders = categoryProviders.filter { provider ->
            val title = provider["title"] as? String
            title != null && (sharedPref?.getBoolean(title, false) ?: false)
        }

        selectedProviders.forEach { provider ->
            val title = provider["title"] as String
            val catLink = provider["catLink"] as String
            val type = provider["type"] as? String ?: "m3u"
            val displayTitle = "📺 $title"
            if (type == "custom") {
                registerMainAPI(PlayFyLiveEvents(displayTitle, catLink))
            } else {
                registerMainAPI(PlayFy(displayTitle, catLink))
            }
        }

        val act = context as? AppCompatActivity
        if (act != null) {
            openSettings = {
                val categoryNames = categoryProviders.mapNotNull { it["title"] as? String }
                val frag = PlayFySettings(this, sharedPref, categoryNames)
                frag.show(act.supportFragmentManager, "PlayFySettings")
            }
        }
    }
}
