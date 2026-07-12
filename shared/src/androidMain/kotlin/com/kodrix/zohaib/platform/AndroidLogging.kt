package com.kodrix.zohaib.platform

import android.util.Log

actual fun logDebug(tag: String, message: String) { Log.d(tag, message) }
actual fun logError(tag: String, message: String, throwable: Throwable?) {
    if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
}
actual fun logInfo(tag: String, message: String) { Log.i(tag, message) }
