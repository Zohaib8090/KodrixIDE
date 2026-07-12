package com.kodrix.zohaib.platform

import java.io.File

actual object Platform {
    actual val name: String = "Linux ${System.getProperty("os.version") ?: "unknown"}"
    actual val isAndroid: Boolean = false
    actual val fileSeparator: String = File.separator
}
