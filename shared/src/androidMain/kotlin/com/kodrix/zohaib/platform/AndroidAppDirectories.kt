package com.kodrix.zohaib.platform

import android.content.Context

actual class AppDirectories actual constructor(context: Any?) {
    private val appContext = context as? Context

    actual fun getFilesDir(): String = appContext?.filesDir?.absolutePath ?: "/data/data/com.kodrix.zohaib/files"
    actual fun getCacheDir(): String = appContext?.cacheDir?.absolutePath ?: "/data/data/com.kodrix.zohaib/cache"
    actual fun getNativeLibDir(): String = appContext?.applicationInfo?.nativeLibraryDir ?: ""
    actual fun getDataDir(): String = appContext?.filesDir?.parentFile?.absolutePath ?: "/data/data/com.kodrix.zohaib"
}
