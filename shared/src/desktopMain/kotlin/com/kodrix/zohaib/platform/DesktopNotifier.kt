package com.kodrix.zohaib.platform

actual class PlatformNotifier {
    actual fun showNotification(title: String, message: String, id: Int) {
        println("[$title] $message")
    }
}
