package com.kodrix.zohaib.desktop.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kodrix.zohaib.viewmodel.SidebarMode

@Composable
fun ActivityBar(
    currentMode: SidebarMode,
    sidebarOpen: Boolean,
    isPanelVisible: Boolean,
    uiScale: Float = 1f,
    onModeClick: (SidebarMode) -> Unit,
    onTogglePanel: () -> Unit
) {
    val barWidth = (48 * uiScale).dp
    val iconSize = (24 * uiScale).dp

    val infiniteTransition = rememberInfiniteTransition(label = "iconPulse")

    val aiPulse by infiniteTransition.animateFloat(
        initialValue = 0.7f, targetValue = 1.2f,
        animationSpec = infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "aiPulse"
    )
    val terminalBlink by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse), label = "terminalBlink"
    )
    val settingsRotation by animateFloatAsState(
        targetValue = if (sidebarOpen && currentMode == SidebarMode.SETTINGS) 360f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessLow), label = "settingsRotation"
    )
    val browserGlow by animateFloatAsState(
        targetValue = if (sidebarOpen && currentMode == SidebarMode.BROWSER) 1f else 0f,
        animationSpec = tween(500), label = "browserGlow"
    )

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(barWidth)
            .background(Color(0xFF161B22)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .size(barWidth)
                .clickable { onModeClick(SidebarMode.PROJECTS) },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Menu,
                contentDescription = "Projects",
                tint = if (sidebarOpen && currentMode == SidebarMode.PROJECTS) Color.White else Color.Gray,
                modifier = Modifier.size(iconSize)
            )
        }

        Box(
            modifier = Modifier
                .size(barWidth)
                .clickable { onModeClick(SidebarMode.EXPLORER) },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Folder,
                contentDescription = "Explorer",
                tint = if (sidebarOpen && currentMode == SidebarMode.EXPLORER) Color.White else Color.Gray,
                modifier = Modifier.size(iconSize)
            )
        }

        Box(
            modifier = Modifier
                .size(barWidth)
                .clickable { onModeClick(SidebarMode.GIT) },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.AccountTree,
                contentDescription = "Git",
                tint = if (sidebarOpen && currentMode == SidebarMode.GIT) Color.White else Color.Gray,
                modifier = Modifier.size(iconSize)
            )
        }

        Box(
            modifier = Modifier
                .size(barWidth)
                .clickable { onModeClick(SidebarMode.SEARCH) },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = "Search",
                tint = if (sidebarOpen && currentMode == SidebarMode.SEARCH) Color.White else Color.Gray,
                modifier = Modifier.size(iconSize)
            )
        }

        Box(
            modifier = Modifier
                .size(barWidth)
                .clickable { onModeClick(SidebarMode.EXTENSIONS) },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Extension,
                contentDescription = "Extensions",
                tint = if (sidebarOpen && currentMode == SidebarMode.EXTENSIONS) Color.White else Color.Gray,
                modifier = Modifier.size(iconSize)
            )
        }

        Box(
            modifier = Modifier
                .size(barWidth)
                .clickable { onModeClick(SidebarMode.MARKETPLACE) },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Storefront,
                contentDescription = "Marketplace",
                tint = if (sidebarOpen && currentMode == SidebarMode.MARKETPLACE) Color.White else Color.Gray,
                modifier = Modifier.size(iconSize)
            )
        }

        Box(
            modifier = Modifier
                .size(barWidth)
                .clickable { onModeClick(SidebarMode.AI) },
            contentAlignment = Alignment.Center
        ) {
            val isActive = sidebarOpen && currentMode == SidebarMode.AI
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = "AI Assistant",
                tint = if (isActive) Color(0xFF58A6FF) else Color.Gray,
                modifier = Modifier
                    .size(iconSize)
                    .graphicsLayer(
                        scaleX = if (isActive) aiPulse else 1f,
                        scaleY = if (isActive) aiPulse else 1f,
                        alpha = if (isActive) 1f else 0.7f
                    )
            )
        }

        Box(
            modifier = Modifier
                .size(barWidth)
                .clickable { onTogglePanel() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Terminal,
                contentDescription = "Terminal",
                tint = if (isPanelVisible) Color.White else Color.Gray,
                modifier = Modifier
                    .size(iconSize)
                    .graphicsLayer(alpha = if (isPanelVisible) terminalBlink else 1f)
            )
        }

        Box(
            modifier = Modifier
                .size(barWidth)
                .clickable { onModeClick(SidebarMode.DEBUG) },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.BugReport,
                contentDescription = "Debug",
                tint = if (sidebarOpen && currentMode == SidebarMode.DEBUG) Color.White else Color.Gray,
                modifier = Modifier.size(iconSize)
            )
        }

        Box(
            modifier = Modifier
                .size(barWidth)
                .clickable { onModeClick(SidebarMode.BROWSER) },
            contentAlignment = Alignment.Center
        ) {
            val isActive = sidebarOpen && currentMode == SidebarMode.BROWSER
            Icon(
                Icons.Default.Language,
                contentDescription = "Browser",
                tint = if (isActive) Color(0xFF58A6FF).copy(alpha = 0.8f + (0.2f * browserGlow)) else Color.Gray,
                modifier = Modifier
                    .size(iconSize)
                    .graphicsLayer(
                        shadowElevation = if (isActive) (10f * browserGlow) else 0f,
                        scaleX = 1f + (0.1f * browserGlow),
                        scaleY = 1f + (0.1f * browserGlow)
                    )
            )
        }

        Spacer(Modifier.weight(1f))

        Box(
            modifier = Modifier
                .size(barWidth)
                .clickable { onModeClick(SidebarMode.SETTINGS) },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Settings,
                contentDescription = "Settings",
                tint = if (sidebarOpen && currentMode == SidebarMode.SETTINGS) Color.White else Color.Gray,
                modifier = Modifier
                    .size(iconSize)
                    .graphicsLayer(rotationZ = settingsRotation)
            )
        }
    }
}
