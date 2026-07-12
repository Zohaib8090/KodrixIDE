package com.kodrix.zohaib.platform

import com.kodrix.zohaib.platform.logInfo
import com.kodrix.zohaib.platform.logError

/**
 * Desktop-specific implementation using libnotify (Linux) or AWT TrayIcon (cross-platform).
 */
actual class PlatformNotifier {

    init {
        // Try to initialize system tray if available
        try {
            if (java.awt.SystemTray.isSupported()) {
                val tray = java.awt.SystemTray.getSystemTray()
                // Tray icon setup is optional — we mainly use notify-send
                logInfo("DesktopNotifier", "System tray available")
            }
        } catch (e: Exception) {
            logInfo("DesktopNotifier", "System tray not available: ${e.message}")
        }
    }

    actual fun showNotification(title: String, message: String, id: Int) {
        try {
            val os = System.getProperty("os.name").lowercase()
            when {
                os.contains("nux") || os.contains("nix") -> notifySendLinux(title, message)
                os.contains("mac") -> notifySendMac(title, message)
                else -> notifySendFallback(title, message)
            }
        } catch (e: Exception) {
            logError("DesktopNotifier", "Failed to send notification", e)
            // Fallback: just print
            println("[$title] $message")
        }
    }

    /**
     * Linux: Use libnotify via notify-send command (GNOME/KDE/XFCE).
     */
    private fun notifySendLinux(title: String, message: String) {
        val totalLen = title.length + message.length
        val truncatedTitle = if (title.length > 50) title.take(47) + "..." else title
        val maxMessageLen = 200 - truncatedTitle.length.coerceAtLeast(0)
        val truncatedMessage = if (message.length > maxMessageLen) message.take(maxMessageLen - 3) + "..." else message

        try {
            val process = ProcessBuilder(
                "notify-send",
                "--app-name=Kodrix IDE",
                "--icon=code-copy",
                "--urgency=normal",
                truncatedTitle,
                truncatedMessage
            ).start()
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            logInfo("DesktopNotifier", "Sent notify-send notification")
        } catch (e: Exception) {
            notifySendFallback(title, message)
        }
    }

    /**
     * macOS: Use osascript to display a notification.
     */
    private fun notifySendMac(title: String, message: String) {
        try {
            val script = "display notification \"$title\" with subtitle \"$message\" sound name \"Submarine\""
            ProcessBuilder("osascript", "-e", script).start()
        } catch (e: Exception) {
            notifySendFallback(title, message)
        }
    }

    /**
     * Fallback: try Java AWT TrayIcon, then just print.
     */
    private fun notifySendFallback(title: String, message: String) {
        try {
            if (java.awt.SystemTray.isSupported()) {
                val tray = java.awt.SystemTray.getSystemTray()
                val image = java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                val g = image.createGraphics()
                g.color = java.awt.Color(0x58A6FF)
                g.fillRect(0, 0, 16, 16)
                g.dispose()
                val icon = java.awt.TrayIcon(image, "Kodrix")
                try { tray.add(icon) } catch (_: Exception) {}
                icon.displayMessage(title, message, java.awt.TrayIcon.MessageType.INFO)
                logInfo("DesktopNotifier", "Sent AWT tray notification")
            } else {
                println("[$title] $message")
            }
        } catch (e: Exception) {
            println("[$title] $message")
        }
    }
}