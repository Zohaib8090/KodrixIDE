package com.kodrix.zohaib.platform

import android.os.Build

actual object Platform {
    actual val name: String = "Android ${Build.VERSION.SDK_INT}"
    actual val isAndroid: Boolean = true
    actual val fileSeparator: String = "/"
}
