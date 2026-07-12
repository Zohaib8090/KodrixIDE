package com.kodrix.zohaib.platform

actual fun logDebug(tag: String, message: String) = println("D/$tag: $message")
actual fun logError(tag: String, message: String, throwable: Throwable?) {
    if (throwable != null) println("E/$tag: $message - ${throwable.message}") else println("E/$tag: $message")
}
actual fun logInfo(tag: String, message: String) = println("I/$tag: $message")
