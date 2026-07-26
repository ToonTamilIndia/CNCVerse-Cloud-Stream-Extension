package com.cncverse

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.runBlocking

@CloudstreamPlugin
class LivXowPlugin : Plugin() {

    private val sharedPref = activity?.getSharedPreferences("LivXow", Context.MODE_PRIVATE)

    override fun load(context: Context) {
        LivXowProvider.context = context
        LivXowLiveEventsProvider.context = context

        registerMainAPI(LivXowLiveEventsProvider())
        registerMainAPI(LivXowLiveEventsProvider("🎬LivXow Highlights", "highlights.txt"))

        val categoryProviders: List<Map<String, Any>> = runBlocking {
            LivXowProviderManager.fetchProviders()
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
                registerMainAPI(LivXowLiveEventsProvider(displayTitle, catLink))
            } else {
                registerMainAPI(LivXowProvider(displayTitle, catLink))
            }
        }

        val act = context as? AppCompatActivity
        if (act != null) {
            openSettings = {
                val categoryNames = categoryProviders.mapNotNull { it["title"] as? String }
                val frag = LivXowSettings(this, sharedPref, categoryNames)
                frag.show(act.supportFragmentManager, "LivXowSettings")
            }
        }
    }
}
