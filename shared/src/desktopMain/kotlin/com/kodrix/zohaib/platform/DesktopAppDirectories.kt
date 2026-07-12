package com.kodrix.zohaib.platform

import java.io.File

actual class AppDirectories actual constructor(context: Any?) {
    private val homeDir = System.getProperty("user.home") ?: System.getProperty("java.io.tmpdir")
    private val baseDir = File(homeDir, ".kodrix")

    init {
        baseDir.mkdirs()
    }

    actual fun getFilesDir(): String = File(baseDir, "files").apply { mkdirs() }.absolutePath
    actual fun getCacheDir(): String = File(baseDir, "cache").apply { mkdirs() }.absolutePath
    actual fun getNativeLibDir(): String = File(baseDir, "lib").apply { mkdirs() }.absolutePath
    actual fun getDataDir(): String = baseDir.absolutePath
}
