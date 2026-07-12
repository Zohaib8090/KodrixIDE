package com.kodrix.zohaib.desktop.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.ProcessTtyConnector
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.emulator.ColorPaletteImpl
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.nio.charset.StandardCharsets
import javax.swing.SwingUtilities

private val TERMINAL_BG = java.awt.Color(19, 19, 19)
private val TERMINAL_FG = java.awt.Color(204, 204, 204)
private val COMPOSE_BG = Color(0xFF131313)

private fun applyDarkBackground(c: Component) {
    c.background = TERMINAL_BG
    if (c is Container) c.components.forEach { applyDarkBackground(it) }
}

/**
 * Custom settings provider that dynamically returns font size and scale,
 * and overrides the default style and colors to ensure a dark terminal theme.
 */
class DynamicSettingsProvider : DefaultSettingsProvider() {
    var fontSize: Float = 12f
    var scale: Float = 1f

    override fun getTerminalFontSize(): Float = fontSize * scale
    
    override fun getTerminalColorPalette() = ColorPaletteImpl.XTERM_PALETTE
    
    override fun getDefaultForeground(): TerminalColor {
        return TerminalColor.rgb(TERMINAL_FG.red, TERMINAL_FG.green, TERMINAL_FG.blue)
    }

    override fun getDefaultBackground(): TerminalColor {
        return TerminalColor.rgb(TERMINAL_BG.red, TERMINAL_BG.green, TERMINAL_BG.blue)
    }

    override fun getDefaultStyle(): TextStyle {
        return TextStyle(getDefaultForeground(), getDefaultBackground())
    }

    override fun caretBlinkingMs(): Int = Int.MAX_VALUE
}

/**
 * Bridges pty4j PtyProcess to JediTerm's ProcessTtyConnector.
 */
class PtyTtyConnector(
    private val ptyProcess: PtyProcess,
    commandLine: List<String>
) : ProcessTtyConnector(ptyProcess, StandardCharsets.UTF_8, commandLine) {

    override fun getName(): String = "pty"

    override fun resize(termSize: TermSize) {
        setSize(termSize.columns, termSize.rows)
    }

    override fun resize(pixelSize: Dimension) {
        // pixel-only resize is managed internally by JediTerm
    }

    override fun resize(pixelSize: Dimension, fontSize: Dimension) {
        if (fontSize.width > 0 && fontSize.height > 0) {
            setSize(
                maxOf(1, pixelSize.width / fontSize.width),
                maxOf(1, pixelSize.height / fontSize.height)
            )
        }
    }

    private fun setSize(cols: Int, rows: Int) {
        try {
            ptyProcess.setWinSize(WinSize(cols, rows))
        } catch (_: Exception) { }
    }
}

@Composable
fun TerminalPanel(
    viewModel: com.kodrix.zohaib.viewmodel.DesktopIDEViewModel,
    terminalFontSize: Int = 12,
    uiScale: Float = 1f
) {
    val workingDir = viewModel.getWorkingDirectory()

    val settings = remember { DynamicSettingsProvider() }

    // Dynamically update the settings object on each recomposition
    settings.fontSize = terminalFontSize.toFloat()
    settings.scale = uiScale

    val jediTermWidget = remember {
        // Suppress JediTerm's TimSort comparator bug on background threads
        val parent = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            if (e is IllegalArgumentException &&
                e.message?.contains("Comparison method violates") == true) return@setDefaultUncaughtExceptionHandler
            parent?.uncaughtException(t, e) ?: e.printStackTrace()
        }

        // Subclass JediTermWidget to set dark theme and background
        val widget = object : JediTermWidget(80, 24, settings) {
            override fun createDefaultStyle(): StyleState {
                return StyleState().also { state ->
                    state.setDefaultStyle(
                        TextStyle(
                            TerminalColor.rgb(TERMINAL_FG.red, TERMINAL_FG.green, TERMINAL_FG.blue),
                            TerminalColor.rgb(TERMINAL_BG.red, TERMINAL_BG.green, TERMINAL_BG.blue)
                        )
                    )
                }
            }

            init { applyDarkBackground(this) }

            override fun addNotify() {
                super.addNotify()
                SwingUtilities.invokeLater { applyDarkBackground(this) }
            }
        }

        val env = HashMap(System.getenv())
        env["TERM"] = "xterm-256color"
        env["HOME"] = System.getProperty("user.home")

        val shell = if (System.getProperty("os.name").lowercase().contains("win")) {
            listOf("cmd.exe")
        } else {
            listOf("/bin/bash", "--login")
        }

        try {
            val ptyProcess = PtyProcessBuilder()
                .setCommand(shell.toTypedArray())
                .setEnvironment(env)
                .setDirectory(workingDir)
                .start()

            val connector = PtyTtyConnector(ptyProcess, shell)
            widget.setTtyConnector(connector)
            widget.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        widget
    }

    // Dynamic Font Resize Listener: Trigger reinitialization when font settings update
    LaunchedEffect(terminalFontSize, uiScale) {
        SwingUtilities.invokeLater {
            try {
                val panel = jediTermWidget.terminalPanel
                val method = panel.javaClass.getDeclaredMethod("reinitFontAndResize")
                method.isAccessible = true
                method.invoke(panel)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    DisposableEffect(jediTermWidget) {
        onDispose { jediTermWidget.close() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(COMPOSE_BG)
    ) {
        SwingPanel(
            factory = { jediTermWidget },
            modifier = Modifier.fillMaxSize()
        )
    }
}
