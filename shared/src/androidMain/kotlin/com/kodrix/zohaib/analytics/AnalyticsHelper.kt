package com.kodrix.zohaib.analytics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics

object AnalyticsHelper {
    private const val PREFS_NAME = "kodrix_analytics"
    private const val KEY_FIRST_LAUNCH = "first_launch_reported"

    fun logDeviceSpecs(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_FIRST_LAUNCH, false)) return

        val firebaseAnalytics = FirebaseAnalytics.getInstance(context)
        val bundle = Bundle().apply {
            putString("device_model", "${Build.MANUFACTURER} ${Build.MODEL}")
            putString("cpu_abi", Build.SUPPORTED_ABIS.joinToString(", "))
            putLong("ram_total_gb", getTotalRAM(context))
            putString("android_version", Build.VERSION.RELEASE)
        }

        firebaseAnalytics.logEvent("app_open_first_launch", bundle)
        
        prefs.edit().putBoolean(KEY_FIRST_LAUNCH, true).apply()
    }

    private fun getTotalRAM(context: Context): Long {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)
        return memInfo.totalMem / (1024 * 1024 * 1024) // Convert to GB
    }
}
