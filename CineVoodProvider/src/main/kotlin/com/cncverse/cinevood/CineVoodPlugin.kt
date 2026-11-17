package com.cncverse.cinevood

import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import java.lang.ref.WeakReference

@CloudstreamPlugin
class CineVoodPlugin : Plugin() {
    private val sites = listOf(
        SiteOption("site_cinevood2", "CineVood (cinevood.net)") { CineVood2Provider() },
        SiteOption("site_cinevood", "CineVood Mirror (1cinevood)") { CineVoodProvider() },
    )

    override fun load(context: Context) {
        dialogContext = WeakReference(context)
        val prefs = context.getSharedPreferences("CineVood", Context.MODE_PRIVATE)
        sites.filter { prefs.getBoolean(it.key, true) }.forEach { registerMainAPI(it.create()) }
        registerExtractorAPI(Filepress())

        val activity = context as? AppCompatActivity ?: return
        openSettings = {
            val checked = sites.map { prefs.getBoolean(it.key, true) }.toBooleanArray()
            AlertDialog.Builder(activity)
                .setTitle("CineVood sites")
                .setMultiChoiceItems(sites.map { it.label }.toTypedArray(), checked) { _, i, on ->
                    checked[i] = on
                }
                .setPositiveButton("Save") { _, _ ->
                    if (checked.none { it }) {
                        showToast("Keep at least one site enabled")
                        return@setPositiveButton
                    }
                    prefs.edit().apply {
                        sites.forEachIndexed { i, s -> putBoolean(s.key, checked[i]) }
                        apply()
                    }
                    AlertDialog.Builder(activity)
                        .setTitle("Restart Required")
                        .setMessage("Changes have been saved. Do you want to restart the app to apply them?")
                        .setPositiveButton("Yes") { _, _ -> restartApp(activity) }
                        .setNegativeButton("No") { dialog, _ ->
                            dialog.dismiss()
                            showToast("Settings saved. Restart app to apply changes.")
                        }
                        .show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun restartApp(context: Context) {
        val app = context.applicationContext
        val component = app.packageManager.getLaunchIntentForPackage(app.packageName)?.component ?: return
        app.startActivity(Intent.makeRestartActivityTask(component))
        Runtime.getRuntime().exit(0)
    }
}

private data class SiteOption(val key: String, val label: String, val create: () -> MainAPI)
