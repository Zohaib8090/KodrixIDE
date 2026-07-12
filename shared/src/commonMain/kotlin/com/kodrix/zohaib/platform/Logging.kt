package com.kodrix.zohaib.platform

expect fun logDebug(tag: String, message: String)
expect fun logError(tag: String, message: String, throwable: Throwable? = null)
expect fun logInfo(tag: String, message: String)
