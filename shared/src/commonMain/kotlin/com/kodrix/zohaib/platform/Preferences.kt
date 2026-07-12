package com.kodrix.zohaib.platform

expect class PlatformPreferences(name: String = "kodrix_settings") {
    fun getString(key: String, default: String = ""): String
    fun setString(key: String, value: String)
    fun getInt(key: String, default: Int = 0): Int
    fun setInt(key: String, value: Int)
    fun getBoolean(key: String, default: Boolean = false): Boolean
    fun setBoolean(key: String, value: Boolean)
}
