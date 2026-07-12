package com.kodrix.zohaib.platform

expect class AppDirectories(context: Any? = null) {
    fun getFilesDir(): String
    fun getCacheDir(): String
    fun getNativeLibDir(): String
    fun getDataDir(): String
}
