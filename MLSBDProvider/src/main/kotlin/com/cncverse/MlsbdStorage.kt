package com.cncverse

import android.content.Context
import android.content.SharedPreferences

/**
 * Ported from upstream `MlsbdStorage`: persists the `cf_clearance` cookie so that
 * a captcha solved once in the WebView is reused by later HTTP requests.
 */
object MlsbdStorage {
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences("MlsbdPrefs", 0)
        }
    }

    private fun getPrefs(): SharedPreferences? {
        if (prefs == null) {
            MlsbdProvider.appContext?.let { init(it) }
        }
        return prefs
    }

    fun saveCfCookie(cookie: String) {
        getPrefs()?.edit()?.apply {
            putString("cf_clearance", cookie)
            putLong("cf_clearance_timestamp", System.currentTimeMillis())
            apply()
        }
    }

    fun getCfCookie(): Pair<String?, Long> {
        val p = getPrefs()
        return Pair(
            p?.getString("cf_clearance", null),
            p?.getLong("cf_clearance_timestamp", 0L) ?: 0L
        )
    }

    fun clearCfCookie() {
        getPrefs()?.edit()?.apply {
            remove("cf_clearance")
            remove("cf_clearance_timestamp")
            apply()
        }
    }
}
