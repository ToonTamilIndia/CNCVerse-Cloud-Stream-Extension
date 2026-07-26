package com.cncverse

import android.content.res.Resources
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class SubscriptionManagerSettings(
    private val plugin: SubscriptionManagerPlugin
) : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val res: Resources? = plugin.resources
        val layoutId = res?.getIdentifier("subscription_settings", "layout", "com.cncverse")
            ?: return null
        return inflater.inflate(res.getLayout(layoutId), container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
    }

    private fun bindViews(view: View) {
        val closeBtn = findViewByName<Button>(view, "sm_close_btn")
        closeBtn?.setOnClickListener { dismiss() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : View> findViewByName(view: View, name: String): T? {
        val res = plugin.resources ?: return null
        val id = res.getIdentifier(name, "id", "com.cncverse") ?: return null
        return view.findViewById(id) as? T
    }
}
