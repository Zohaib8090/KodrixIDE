package com.kodrix.zohaib

import android.app.Application
import com.kodrix.zohaib.bridge.NativeLibLoader

class KodrixApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        android.util.Log.i("KodrixApp", "Application onCreate - initializing NativeLibLoader")
        NativeLibLoader.init(this)
    }
}
