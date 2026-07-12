package com.kodrix.zohaib.platform

expect class PlatformNotifier {
    fun showNotification(title: String, message: String, id: Int = 0)
}
