package com.kodrix.zohaib.platform

import java.io.File
import java.util.prefs.Preferences

actual class PlatformPreferences actual constructor(name: String) {
    private val prefs = Preferences.userRoot().node("com/kodrix/zohaib/$name")

    actual fun getString(key: String, default: String): String = prefs.get(key, default)
    actual fun setString(key: String, value: String) { prefs.put(key, value) }
    actual fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    actual fun setInt(key: String, value: Int) { prefs.putInt(key, value) }
    actual fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    actual fun setBoolean(key: String, value: Boolean) { prefs.putBoolean(key, value) }
}
