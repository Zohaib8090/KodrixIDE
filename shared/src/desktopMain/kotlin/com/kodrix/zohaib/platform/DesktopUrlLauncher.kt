package com.kodrix.zohaib.platform

import java.awt.Desktop
import java.io.File

actual fun openUrl(url: String) {
    if (Desktop.isDesktopSupported()) {
        Desktop.getDesktop().browse(java.net.URI(url))
    }
}

actual fun openFileInSystem(path: String) {
    if (Desktop.isDesktopSupported()) {
        Desktop.getDesktop().open(File(path))
    }
}
