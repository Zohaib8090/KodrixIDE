package com.kodrix.zohaib.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

actual class PlatformClipboard(private val context: Context) {
    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    actual fun setText(text: String) {
        val clip = ClipData.newPlainText("kodrix", text)
        clipboard.setPrimaryClip(clip)
    }

    actual fun getText(): String {
        val clip = clipboard.primaryClip
        return clip?.getItemAt(0)?.coerceToText(context)?.toString() ?: ""
    }
}
