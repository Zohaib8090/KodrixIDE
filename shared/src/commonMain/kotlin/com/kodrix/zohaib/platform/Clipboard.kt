package com.kodrix.zohaib.platform

expect class PlatformClipboard {
    fun setText(text: String)
    fun getText(): String
}
