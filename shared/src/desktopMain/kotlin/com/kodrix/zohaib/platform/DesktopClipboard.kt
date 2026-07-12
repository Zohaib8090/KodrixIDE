package com.kodrix.zohaib.platform

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

actual class PlatformClipboard {
    private val clipboard = Toolkit.getDefaultToolkit().systemClipboard

    actual fun setText(text: String) {
        val selection = StringSelection(text)
        clipboard.setContents(selection, null)
    }

    actual fun getText(): String {
        return try {
            clipboard.getData(DataFlavor.stringFlavor)?.toString() ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}
