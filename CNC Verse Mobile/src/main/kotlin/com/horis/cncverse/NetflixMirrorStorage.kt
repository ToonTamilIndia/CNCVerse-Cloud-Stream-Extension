package com.horis.cncverse

import android.content.Context
import android.content.SharedPreferences

object NetflixMirrorStorage {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        this.context = context.applicationContext
        this.prefs = context.getSharedPreferences("NetflixMirrorPrefsMobile", Context.MODE_PRIVATE)
    }

    fun saveCookie(cookie: String) {
        prefs.edit()
            .putString("nf_cookie", cookie)
            .putLong("nf_cookie_timestamp", System.currentTimeMillis())
            .apply()
    }

    fun getCookie(): Pair<String?, Long> {
        return Pair(
            prefs.getString("nf_cookie", null),
            prefs.getLong("nf_cookie_timestamp", 0L)
        )
    }

    fun clearCookie() {
        prefs.edit()
            .remove("nf_cookie")
            .remove("nf_cookie_timestamp")
            .apply()
    }

    fun saveFullCookie(cookie: String) {
        prefs.edit()
            .putString("nf_cookie_full", cookie)
            .putLong("nf_cookie_full_timestamp", System.currentTimeMillis())
            .apply()
    }

    fun getFullCookie(): Pair<String?, Long> {
        return Pair(
            prefs.getString("nf_cookie_full", null),
            prefs.getLong("nf_cookie_full_timestamp", 0L)
        )
    }

    fun clearFullCookie() {
        prefs.edit()
            .remove("nf_cookie_full")
            .remove("nf_cookie_full_timestamp")
            .apply()
    }
}
