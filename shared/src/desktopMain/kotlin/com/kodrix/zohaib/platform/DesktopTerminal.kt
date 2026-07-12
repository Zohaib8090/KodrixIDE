package com.kodrix.zohaib.platform

import kotlinx.coroutines.*
import java.io.*

actual class PlatformTerminal {
    private var process: Process? = null
    private var writer: OutputStream? = null
    private var readerThread: Thread? = null

    var onOutput: ((String) -> Unit)? = null

    actual fun startShell(shell: String, homeDir: String, rows: Int, cols: Int, env: Map<String, String>) {
        try {
            val pb = ProcessBuilder(shell)
            pb.directory(File(homeDir))
            pb.environment().putAll(env)
            pb.environment()["TERM"] = "dumb"
            pb.environment()["HOME"] = System.getProperty("user.home")
            pb.environment()["SHELL"] = shell
            pb.redirectErrorStream(true)

            process = pb.start()
            writer = process?.outputStream

            logInfo("PlatformTerminal", "Shell process started")

            // Read stdout in a separate thread
            readerThread = Thread {
                try {
                    val reader = process?.inputStream?.bufferedReader()
                    val buffer = CharArray(4096)
                    while (!Thread.currentThread().isInterrupted) {
                        val n = reader?.read(buffer) ?: -1
                        if (n > 0) {
                            val text = String(buffer, 0, n)
                            onOutput?.invoke(text)
                        } else if (n < 0) {
                            break
                        }
                    }
                } catch (e: Exception) {
                    if (!Thread.currentThread().isInterrupted) {
                        logError("PlatformTerminal", "Reader thread error", e)
                    }
                }
            }
            readerThread?.isDaemon = true
            readerThread?.start()

        } catch (e: Exception) {
            logError("PlatformTerminal", "Failed to start shell", e)
        }
    }

    actual fun write(data: ByteArray) {
        try {
            writer?.write(data)
            writer?.flush()
        } catch (e: Exception) {
            logError("PlatformTerminal", "Write error", e)
        }
    }

    fun writeText(text: String) {
        write(text.toByteArray(Charsets.UTF_8))
    }

    actual fun read(): ByteArray? = null

    actual fun resize(rows: Int, cols: Int) {
        // ProcessBuilder doesn't support resize, but we can ignore
    }

    actual fun close() {
        readerThread?.interrupt()
        readerThread = null
        writer?.close()
        process?.destroy()
        process = null
    }

    actual fun isRunning(): Boolean = process?.isAlive == true
}
