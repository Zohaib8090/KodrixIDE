package com.kodrix.zohaib.platform

import android.content.Context
import android.content.SharedPreferences

actual class PlatformPreferences actual constructor(private val name: String) {
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    }

    actual fun getString(key: String, default: String): String = prefs?.getString(key, default) ?: default
    actual fun setString(key: String, value: String) { prefs?.edit()?.putString(key, value)?.apply() }
    actual fun getInt(key: String, default: Int): Int = prefs?.getInt(key, default) ?: default
    actual fun setInt(key: String, value: Int) { prefs?.edit()?.putInt(key, value)?.apply() }
    actual fun getBoolean(key: String, default: Boolean): Boolean = prefs?.getBoolean(key, default) ?: default
    actual fun setBoolean(key: String, value: Boolean) { prefs?.edit()?.putBoolean(key, value)?.apply() }
}
