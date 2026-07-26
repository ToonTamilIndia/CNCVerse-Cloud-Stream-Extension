package com.horis.cncverse

import android.annotation.SuppressLint
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity

class CNCVerseSettings(
    private val plugin: CNCVersePlugin,
    private val sharedPref: SharedPreferences?,
    private val studios: List<StudioOption>
) : BottomSheetDialogFragment() {

    private val enabledStudios = studios
        .filter { isStudioEnabled(it) }
        .map { it.key }
        .toMutableSet()

    private fun isStudioEnabled(option: StudioOption): Boolean {
        val prefs = sharedPref
        if (prefs != null && prefs.contains(option.key)) {
            return prefs.getBoolean(option.key, false)
        }
        return true
    }

    private fun View.makeTvCompatible() {
        setPadding(
            paddingLeft + 10, paddingTop + 10,
            paddingRight + 10, paddingBottom + 10
        )
        background = getDrawable("outline")
    }

    @SuppressLint("DiscouragedApi")
    private fun getDrawable(name: String): Drawable? {
        val resources = plugin.resources ?: return null
        val id = resources.getIdentifier(name, "drawable", "com.cncverse")
        if (id == 0) return null
        return ResourcesCompat.getDrawable(resources, id, null)
    }

    @SuppressLint("DiscouragedApi")
    private fun getString(name: String): String? {
        val resources = plugin.resources ?: return null
        val id = resources.getIdentifier(name, "string", "com.cncverse")
        if (id == 0) return null
        return resources.getString(id)
    }

    @SuppressLint("DiscouragedApi")
    private fun <T : View> findViewByName(view: View, name: String): T? {
        val resources = plugin.resources ?: return null
        val id = resources.getIdentifier(name, "id", "com.cncverse")
        if (id == 0) return null
        return view.findViewById(id)
    }

    @SuppressLint("DiscouragedApi")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val resources = plugin.resources ?: return null
        val layoutId = resources.getIdentifier("settings", "layout", "com.cncverse")
        if (layoutId == 0) return null
        val layout = resources.getLayout(layoutId)
        return inflater.inflate(layout, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val headerTw: TextView? = findViewByName(view, "header_tw")
        headerTw?.text = getString("header_tw")

        val header2Tw: TextView? = findViewByName(view, "header2_tw")
        header2Tw?.text = getString("header2_tw")

        val saveBtn: ImageButton? = findViewByName(view, "save_btn")
        saveBtn?.makeTvCompatible()
        saveBtn?.setImageDrawable(getDrawable("save_icon"))

        val scrollView: LinearLayout? = findViewByName(view, "list")
        for (option in studios) {
            scrollView?.addView(getStudioRow(option))
        }

        saveBtn?.setOnClickListener {
            val editor = sharedPref?.edit()
            editor?.clear()
            for (option in studios) {
                editor?.putBoolean(option.key, enabledStudios.contains(option.key))
            }
            editor?.apply()

            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Restart Required")
                .setMessage("Changes have been saved. Do you want to restart the app to apply them?")
                .setPositiveButton("Yes") { _, _ ->
                    dismiss()
                    restartApp()
                }
                .setNegativeButton("No") { dialog, _ ->
                    dialog.dismiss()
                    CommonActivity.showToast(
                        "Settings saved. Restart app to apply changes.",
                    )
                }
                .show()
        }
    }

    private fun restartApp() {
        val context = requireContext().applicationContext
        val packageManager = context.packageManager
        val intent = packageManager.getLaunchIntentForPackage(context.packageName)
        val componentName = intent?.component
        if (componentName != null) {
            val restartIntent = Intent.makeRestartActivityTask(componentName)
            context.startActivity(restartIntent)
            Runtime.getRuntime().exit(0)
        }
    }

    private fun getStudioRow(option: StudioOption): RelativeLayout {
        val relativeLayout = RelativeLayout(requireContext())
        relativeLayout.layoutParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        )
        relativeLayout.setPadding(0, 0, 0, 8)

        val checkBox = CheckBox(requireContext())
        checkBox.id = View.generateViewId()
        val checkBoxParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        )
        checkBoxParams.addRule(RelativeLayout.ALIGN_PARENT_START)
        checkBoxParams.addRule(RelativeLayout.CENTER_VERTICAL)
        checkBox.layoutParams = checkBoxParams

        val textView = TextView(requireContext())
        textView.id = View.generateViewId()
        textView.text = option.label
        textView.textSize = 16.0f
        val textParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        )
        textParams.addRule(RelativeLayout.END_OF, checkBox.id)
        textParams.addRule(RelativeLayout.CENTER_VERTICAL)
        textParams.marginStart = 16
        textView.layoutParams = textParams

        checkBox.isChecked = enabledStudios.contains(option.key)
        checkBox.setOnCheckedChangeListener { _, checked ->
            if (checked) enabledStudios.add(option.key)
            else enabledStudios.remove(option.key)
        }

        textView.setOnClickListener {
            checkBox.isChecked = !checkBox.isChecked
        }

        relativeLayout.addView(checkBox)
        relativeLayout.addView(textView)
        return relativeLayout
    }
}
