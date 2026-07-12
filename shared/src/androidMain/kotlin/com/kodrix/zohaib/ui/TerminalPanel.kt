package com.kodrix.zohaib.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kodrix.zohaib.viewmodel.TerminalViewModel
import com.kodrix.zohaib.model.TerminalInstance

@Composable
fun TerminalTabContent(viewModel: TerminalViewModel) {
    val instances by viewModel.instances.collectAsState()
    val activeIndex by viewModel.activeInstanceIndex.collectAsState()
    val uiScale by viewModel.uiScale.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // Tab Bar for Terminals
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height((35 * uiScale).dp)
                .background(Color(0xFF161B22)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(modifier = Modifier.weight(1f)) {
                instances.forEachIndexed { index, instance ->
                    val isSelected = activeIndex == index
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 2.dp)
                            .height((30 * uiScale).dp)
                            .clickable { viewModel.switchTerminal(index) }
                            .background(
                                if (isSelected) Color(0xFF0D1117) else Color.Transparent,
                                androidx.compose.foundation.shape.RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
                            )
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "sh ${instance.id}",
                                color = if (isSelected) Color.White else Color.Gray,
                                fontSize = (10 * uiScale).sp
                            )
                        }
                    }
                }
            }
            IconButton(onClick = { viewModel.copyTerminalLogs(activeIndex) }, modifier = Modifier.size((30 * uiScale).dp)) {
                Icon(Icons.Default.ContentCopy, "Copy Logs", tint = Color.Gray, modifier = Modifier.size((14 * uiScale).dp))
            }
            if (instances.size > 1) {
                IconButton(onClick = { viewModel.removeTerminal(activeIndex) }, modifier = Modifier.size((30 * uiScale).dp)) {
                    Icon(Icons.Default.Delete, "Close Terminal", tint = Color.Gray, modifier = Modifier.size((16 * uiScale).dp))
                }
            }
            IconButton(onClick = { viewModel.addNewTerminal() }, modifier = Modifier.size((30 * uiScale).dp)) {
                Icon(Icons.Default.Add, "New Terminal", tint = Color.Gray, modifier = Modifier.size((16 * uiScale).dp))
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            TerminalComponent(viewModel, instances.getOrNull(activeIndex))
        }
        TerminalExtraKeys(viewModel)
    }
}

@Composable
fun TerminalExtraKeys(viewModel: TerminalViewModel) {
    val uiScale by viewModel.uiScale.collectAsState()
    val isCtrlActive by viewModel.isCtrlActive.collectAsState()
    val isAltActive by viewModel.isAltActive.collectAsState()

    val row1 = listOf("ESC", "/", "-", "HOME", "UP", "END", "PGUP")
    val row2 = listOf("TAB", "CTRL", "ALT", "LEFT", "DOWN", "RIGHT", "PGDN")

    Column(modifier = Modifier.fillMaxWidth().background(Color.Black).padding(4.dp).imePadding()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            row1.forEach { key ->
                TerminalKeyButton(
                    text = key,
                    icon = if (key == "UP") Icons.Default.KeyboardArrowUp else null,
                    onClick = { viewModel.sendSpecialKey(key) },
                    uiScale = uiScale
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            row2.forEach { key ->
                val isActive = when (key) {
                    "CTRL" -> isCtrlActive
                    "ALT" -> isAltActive
                    else -> false
                }
                TerminalKeyButton(
                    text = key,
                    icon = when (key) {
                        "TAB" -> Icons.Default.KeyboardTab
                        "LEFT" -> Icons.Default.KeyboardArrowLeft
                        "DOWN" -> Icons.Default.KeyboardArrowDown
                        "RIGHT" -> Icons.Default.KeyboardArrowRight
                        else -> null
                    },
                    isActive = isActive,
                    onClick = {
                        when (key) {
                            "CTRL" -> viewModel.toggleCtrl()
                            "ALT" -> viewModel.toggleAlt()
                            else -> viewModel.sendSpecialKey(key)
                        }
                    },
                    uiScale = uiScale
                )
            }
        }
    }
}

@Composable
fun TerminalKeyButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    isActive: Boolean = false,
    onClick: () -> Unit,
    uiScale: Float
) {
    Box(
        modifier = Modifier
            .width((48 * uiScale).dp)
            .height((36 * uiScale).dp)
            .clickable { onClick() }
            .background(if (isActive) Color(0xFF58A6FF).copy(alpha = 0.3f) else Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = text,
                tint = if (isActive) Color(0xFF58A6FF) else Color.White,
                modifier = Modifier.size((18 * uiScale).dp)
            )
        } else {
            Text(
                text = text,
                color = if (isActive) Color(0xFF58A6FF) else Color.White,
                fontSize = (11 * uiScale).sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}


@Composable
fun ProblemsView(viewModel: TerminalViewModel) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("No problems have been detected in the workspace.", color = Color.Gray, fontSize = 13.sp)
    }
}

@Composable
fun OutputView(viewModel: TerminalViewModel) {
    val status by viewModel.setupStatus.collectAsState()
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Tasks Output", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(status, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

@Composable
fun TerminalComponent(viewModel: TerminalViewModel, instance: TerminalInstance?) {
    if (instance == null) return
    val fontSize by viewModel.fontSize.collectAsState()

    androidx.compose.ui.viewinterop.AndroidView(
        factory = { context ->
            com.termux.view.TerminalView(context, null).apply {
                setTerminalViewClient(object : com.termux.view.TerminalViewClient {
                    override fun onScale(scale: Float): Float = scale
                    override fun onSingleTapUp(e: android.view.MotionEvent) {
                        requestFocus()
                        val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                        imm.showSoftInput(this@apply, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                    }
                    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
                    override fun shouldEnforceCharBasedInput(): Boolean = false
                    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
                    override fun isTerminalViewSelected(): Boolean = true
                    override fun copyModeChanged(copyMode: Boolean) {}
                    override fun onKeyDown(keyCode: Int, e: android.view.KeyEvent, session: com.termux.terminal.TerminalSession): Boolean = false
                    override fun onKeyUp(keyCode: Int, e: android.view.KeyEvent): Boolean = false
                    override fun onLongPress(event: android.view.MotionEvent): Boolean = false
                    override fun readControlKey(): Boolean = viewModel.isCtrlActive.value
                    override fun readAltKey(): Boolean = viewModel.isAltActive.value
                    override fun readShiftKey(): Boolean = false
                    override fun readFnKey(): Boolean = false
                    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: com.termux.terminal.TerminalSession): Boolean = false
                    override fun onEmulatorSet() {}
                    override fun logError(tag: String, message: String) {}
                    override fun logWarn(tag: String, message: String) {}
                    override fun logInfo(tag: String, message: String) {}
                    override fun logDebug(tag: String, message: String) {}
                    override fun logVerbose(tag: String, message: String) {}
                    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
                    override fun logStackTrace(tag: String, e: Exception) {}
                })
                
                isFocusable = true
                isFocusableInTouchMode = true
                setTextSize(fontSize)
                attachSession(instance.session)
                
                // Bind this view to this instance for redraw updates
                instance.boundView = this
            }
        },
        update = { view ->
            // Rebind on every recompose so the correct instance always has the view
            if (instance.boundView != view) {
                // Clear old binding if any
                instance.boundView?.let { if (it == view) instance.boundView = null }
                instance.boundView = view
                view.attachSession(instance.session)
            }
            view.setTextSize(fontSize)
        },
        modifier = Modifier.fillMaxSize()
    )
}