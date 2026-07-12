package com.kodrix.zohaib.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kodrix.zohaib.desktop.components.*
import com.kodrix.zohaib.viewmodel.DesktopIDEViewModel
import com.kodrix.zohaib.viewmodel.SidebarMode

@Composable
fun DesktopIDEApp(viewModel: DesktopIDEViewModel) {
    val sidebarMode by viewModel.state.sidebarMode.collectAsState()
    val sidebarOpen by viewModel.state.sidebarOpen.collectAsState()
    val isPanelVisible by viewModel.state.isPanelVisible.collectAsState()
    val panelHeight by viewModel.state.panelHeight.collectAsState()
    val activeProject by viewModel.state.activeProject.collectAsState()
    val terminalFontSize by viewModel.state.terminalFontSize.collectAsState()
    val editorFontSize by viewModel.state.editorFontSize.collectAsState()
    val uiScale by viewModel.state.uiScale.collectAsState()

    // Sidebar width state for resizing
    var sidebarWidth by remember { mutableStateOf(280f) }
    val scaledSidebarWidth = (sidebarWidth * uiScale).dp

    // Scaled dimensions
    val activityBarWidth = (48 * uiScale).dp
    val titleBarHeight = (32 * uiScale).dp
    val dividerSize = (6 * uiScale).dp

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF58A6FF),
            background = Color(0xFF0D1117),
            surface = Color(0xFF161B22),
            surfaceVariant = Color(0xFF21262D),
            onSurface = Color(0xFFC9D1D9),
            onSurfaceVariant = Color(0xFF8B949E),
            error = Color(0xFFF85149),
            outline = Color(0xFF30363D)
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxSize().padding(start = activityBarWidth).background(Color(0xFF161B22))) {
                // Sidebar with resize handle
                if (sidebarOpen) {
                    Row(modifier = Modifier.fillMaxHeight()) {
                        // Sidebar content
                        Box(modifier = Modifier.fillMaxHeight().width(scaledSidebarWidth).background(Color(0xFF161B22))) {
                            SidebarPanel(sidebarMode, viewModel, uiScale)
                        }

                        // Sidebar resize handle (right edge)
                        val sidebarInteraction = remember { MutableInteractionSource() }
                        val isSidebarHovered by sidebarInteraction.collectIsHoveredAsState()

                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(dividerSize)
                                .background(if (isSidebarHovered) Color(0xFF58A6FF) else Color(0xFF30363D))
                                .hoverable(sidebarInteraction)
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        val newWidth = (sidebarWidth + dragAmount.x / uiScale).coerceIn(150f, 500f)
                                        sidebarWidth = newWidth
                                    }
                                }
                        )
                    }
                }

                // Editor + Bottom Panel
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    // Title bar
                    Row(
                        modifier = Modifier.fillMaxWidth().height(titleBarHeight).background(Color(0xFF161B22)).padding(horizontal = (8 * uiScale).dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            activeProject ?: "Kodrix",
                            color = Color(0xFF8B949E), fontSize = (11 * uiScale).sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                    }

                    // Editor area
                    Box(modifier = Modifier.weight(1f).background(Color(0xFF0D1117))) {
                        EditorArea(viewModel, editorFontSize, uiScale)
                    }

                    // Resizable terminal panel
                    if (isPanelVisible) {
                        // Terminal resize handle (top edge)
                        val terminalInteraction = remember { MutableInteractionSource() }
                        val isTerminalHovered by terminalInteraction.collectIsHoveredAsState()

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(dividerSize)
                                .background(if (isTerminalHovered) Color(0xFF58A6FF) else Color(0xFF30363D))
                                .hoverable(terminalInteraction)
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        val newHeight = (panelHeight - dragAmount.y / density).coerceIn(100f, 600f)
                                        viewModel.state.panelHeight.value = newHeight
                                    }
                                }
                        )

                        // Terminal
                        Box(modifier = Modifier.fillMaxWidth().height(panelHeight.dp).background(Color(0xFF0D1117))) {
                            TerminalPanel(viewModel, terminalFontSize, uiScale)
                        }
                    }

                    // Status bar
                    StatusBar(viewModel, uiScale)
                }
            }

            // ActivityBar overlay
            Box(modifier = Modifier.fillMaxHeight().width(activityBarWidth).background(Color(0xFF161B22))) {
                ActivityBar(
                    currentMode = sidebarMode,
                    sidebarOpen = sidebarOpen,
                    isPanelVisible = isPanelVisible,
                    uiScale = uiScale,
                    onModeClick = { mode ->
                        if (mode == sidebarMode && sidebarOpen) {
                            viewModel.state.sidebarOpen.value = false
                        } else {
                            viewModel.state.setSidebarMode(mode)
                            viewModel.state.sidebarOpen.value = true
                        }
                    },
                    onTogglePanel = { viewModel.state.isPanelVisible.value = !viewModel.state.isPanelVisible.value }
                )
            }
        }
    }
}
