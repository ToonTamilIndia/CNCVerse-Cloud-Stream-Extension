package com.cncverse

import android.content.res.Resources
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.RequiresApi
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Offline "About this build" sheet.
 *
 * The upstream version of this screen verified licence tokens against a remote
 * API and pushed users to subscription/sign-in web pages. This fork is ad-free
 * by design, so there is nothing to verify, buy or remove. The sheet only
 * reports that no licence checks happen.
 */
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

    @RequiresApi(23)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        findViewByName<TextView>(view, "sm_mode_badge")?.apply {
            text = "✅ Ad-free build"
            setBackgroundResource(android.R.color.holo_green_dark)
        }
        findViewByName<TextView>(view, "sm_status_text")?.text =
            "No subscription, licence or account is required."
        findViewByName<TextView>(view, "sm_plan_text")?.text =
            "This fork strips upstream ads, promo popups and licence gating. Nothing is verified online."
        findViewByName<TextView>(view, "sm_token_field")?.apply {
            text = "No token stored"
        }

        // Hide the upstream commerce/verification controls entirely.
        for (id in listOf("sm_paste_btn", "sm_subscribe_btn", "sm_signin_btn", "sm_remove_btn")) {
            findViewByName<View>(view, id)?.visibility = View.GONE
        }

        findViewByName<View>(view, "sm_close_btn")?.setOnClickListener { dismiss() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : View> findViewByName(view: View, name: String): T? {
        val res = plugin.resources ?: return null
        val id = res.getIdentifier(name, "id", "com.cncverse")
        if (id == 0) return null
        return view.findViewById(id) as? T
    }
}
