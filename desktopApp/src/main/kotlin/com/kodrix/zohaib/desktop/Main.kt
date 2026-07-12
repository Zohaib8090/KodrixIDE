package com.kodrix.zohaib.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.kodrix.zohaib.viewmodel.DesktopIDEViewModel
import java.io.File

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

fun main(args: Array<String>) {
    // 1. Register the custom protocol handler
    registerProtocolHandler()

    // 2. If launched with a URL, try to forward it to the already running instance
    if (args.isNotEmpty() && args[0].startsWith("kodrix://")) {
        try {
            val socket = Socket("127.0.0.1", 49153)
            val writer = java.io.PrintWriter(socket.getOutputStream(), true)
            writer.println(args[0])
            socket.close()
            return // Exit this instance immediately since the link was forwarded
        } catch (e: Exception) {
            // Failed to connect, meaning no other instance is running.
            // We will let this instance start and handle it directly.
        }
    }

    application {
        val viewModel = remember { DesktopIDEViewModel() }

        // 3. Start a background TCP server to listen for OAuth redirect URLs forwarded by new instances
        LaunchedEffect(Unit) {
            thread(isDaemon = true, name = "SingleInstanceServer") {
                try {
                    val serverSocket = ServerSocket(49153)
                    while (true) {
                        val client = serverSocket.accept()
                        thread(isDaemon = true) {
                            try {
                                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                                val line = reader.readLine()
                                if (line != null && line.startsWith("kodrix://")) {
                                    viewModel.handleOAuthCallback(line)
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            } finally {
                                client.close()
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Port already bound or other issue — safe to ignore
                }
            }

            // 4. If we were started directly with a URL, process it
            if (args.isNotEmpty() && args[0].startsWith("kodrix://")) {
                viewModel.handleOAuthCallback(args[0])
            }
        }

        // Load icon from resources
        val icon = remember {
            try {
                val stream = object {}.javaClass.classLoader.getResourceAsStream("icon.png")
                if (stream != null) {
                    BitmapPainter(loadImageBitmap(stream))
                } else {
                    // Try loading from file
                    val file = File("icon.png")
                    if (file.exists()) {
                        BitmapPainter(loadImageBitmap(file.inputStream()))
                    } else null
                }
            } catch (e: Exception) {
                null
            }
        }

        Window(
            onCloseRequest = {
                viewModel.shutdown()
                exitApplication()
            },
            title = "Kodrix IDE",
            icon = icon,
            state = rememberWindowState(width = 1400.dp, height = 900.dp)
        ) {
            val composeWindow = this.window
            DisposableEffect(composeWindow) {
                val dropListener = object : java.awt.dnd.DropTargetAdapter() {
                    override fun dragEnter(dtde: java.awt.dnd.DropTargetDragEvent) {
                        dtde.acceptDrag(java.awt.dnd.DnDConstants.ACTION_COPY_OR_MOVE)
                    }
                    override fun dragOver(dtde: java.awt.dnd.DropTargetDragEvent) {
                        dtde.acceptDrag(java.awt.dnd.DnDConstants.ACTION_COPY_OR_MOVE)
                    }
                    override fun drop(dtde: java.awt.dnd.DropTargetDropEvent) {
                        dtde.acceptDrop(java.awt.dnd.DnDConstants.ACTION_COPY_OR_MOVE)
                        var folderOpened = false
                        try {
                            val transferable = dtde.transferable

                            // Try standard Java file list first (works on Windows/macOS)
                            if (!folderOpened && transferable.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) {
                                try {
                                    @Suppress("UNCHECKED_CAST")
                                    val files = transferable.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor) as List<java.io.File>
                                    val dir = files.firstOrNull { it.isDirectory }
                                    if (dir != null) {
                                        viewModel.openFolder(dir.absolutePath)
                                        folderOpened = true
                                    }
                                } catch (_: Exception) {}
                            }

                            // Fallback: text/uri-list (used by Linux GNOME/KDE file managers)
                            if (!folderOpened) {
                                try {
                                    val uriListFlavor = java.awt.datatransfer.DataFlavor("text/uri-list;class=java.lang.String")
                                    if (transferable.isDataFlavorSupported(uriListFlavor)) {
                                        val uriList = transferable.getTransferData(uriListFlavor) as String
                                        uriList.lines()
                                            .filter { it.isNotBlank() && !it.startsWith("#") }
                                            .forEach { uriStr ->
                                                if (!folderOpened) {
                                                    try {
                                                        val file = java.io.File(java.net.URI(uriStr.trim()))
                                                        if (file.exists() && file.isDirectory) {
                                                            viewModel.openFolder(file.absolutePath)
                                                            folderOpened = true
                                                        }
                                                    } catch (_: Exception) {
                                                        // Manually strip file:// prefix as last resort
                                                        val path = uriStr.trim().removePrefix("file://")
                                                        val file = java.io.File(path)
                                                        if (file.exists() && file.isDirectory) {
                                                            viewModel.openFolder(file.absolutePath)
                                                            folderOpened = true
                                                        }
                                                    }
                                                }
                                            }
                                    }
                                } catch (_: Exception) {}
                            }

                            dtde.dropComplete(folderOpened)
                        } catch (e: Exception) {
                            e.printStackTrace()
                            dtde.dropComplete(false)
                        }
                    }
                }
                val dropTarget = java.awt.dnd.DropTarget(composeWindow, java.awt.dnd.DnDConstants.ACTION_COPY_OR_MOVE, dropListener, true)
                composeWindow.dropTarget = dropTarget
                onDispose {
                    composeWindow.dropTarget = null
                }
            }

            DesktopIDEApp(viewModel)
        }
    }
}

/**
 * Dynamically registers the `kodrix://` custom URL scheme on Linux systems.
 * Creates a desktop entry under ~/.local/share/applications and configures it with xdg-mime.
 */
fun registerProtocolHandler() {
    try {
        val os = System.getProperty("os.name").lowercase()
        if (os.contains("linux")) {
            val home = System.getProperty("user.home")
            val appDir = File(home, ".local/share/applications")
            if (!appDir.exists()) appDir.mkdirs()

            val desktopFile = File(appDir, "kodrix-scheme-handler.desktop")
            val content = """
                [Desktop Entry]
                Type=Application
                Name=Kodrix Protocol Handler
                Exec=/opt/kodrix-ide/bin/kodrix-ide %u
                Icon=kodrix-ide
                StartupNotify=true
                Terminal=false
                MimeType=x-scheme-handler/kodrix;
            """.trimIndent()
            desktopFile.writeText(content)

            // Register it using xdg-mime
            ProcessBuilder("xdg-mime", "default", "kodrix-scheme-handler.desktop", "x-scheme-handler/kodrix").start()
            ProcessBuilder("update-desktop-database", appDir.absolutePath).start()
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

