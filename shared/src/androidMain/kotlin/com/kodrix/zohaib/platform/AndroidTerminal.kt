package com.kodrix.zohaib.platform

actual class PlatformTerminal {
    private var ptyBridge: com.kodrix.zohaib.bridge.PtyBridge? = null

    actual fun startShell(shell: String, homeDir: String, rows: Int, cols: Int, env: Map<String, String>) {
        ptyBridge = com.kodrix.zohaib.bridge.PtyBridge()
    }

    actual fun write(data: ByteArray) {
        ptyBridge?.let { bridge ->
            try {
                val outputStream = bridge.javaClass.getDeclaredMethod("getOutputStream").invoke(bridge) as? java.io.OutputStream
                outputStream?.write(data)
                outputStream?.flush()
            } catch (e: Exception) {
                logError("AndroidTerminal", "Failed to write", e)
            }
        }
    }

    actual fun read(): ByteArray? {
        return try {
            ptyBridge?.let { bridge ->
                val inputStream = bridge.javaClass.getDeclaredMethod("getInputStream").invoke(bridge) as? java.io.InputStream
                if (inputStream != null && inputStream.available() > 0) {
                    val buffer = ByteArray(inputStream.available())
                    inputStream.read(buffer)
                    buffer
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    actual fun resize(rows: Int, cols: Int) {
        ptyBridge?.let { bridge ->
            try {
                val fd = bridge.javaClass.getDeclaredMethod("getFd").invoke(bridge) as Int
                bridge.javaClass.getDeclaredMethod("setWindowSize", Int::class.java, Int::class.java, Int::class.java)
                    .invoke(bridge, fd, rows, cols)
            } catch (e: Exception) {
                logError("AndroidTerminal", "Failed to resize", e)
            }
        }
    }

    actual fun close() {
        ptyBridge = null
    }

    actual fun isRunning(): Boolean = ptyBridge != null
}
