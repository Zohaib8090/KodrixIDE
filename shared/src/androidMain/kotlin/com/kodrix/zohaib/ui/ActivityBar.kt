package com.kodrix.zohaib.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kodrix.zohaib.viewmodel.TerminalViewModel

@Composable
fun ActivityBar(viewModel: TerminalViewModel) {
    val fontSize by viewModel.fontSize.collectAsState()
    val uiScale by viewModel.uiScale.collectAsState()
    val isPanelVisible by viewModel.isPanelVisible.collectAsState()
    val sidebarOpen by viewModel.sidebarOpen.collectAsState()
    val sidebarMode by viewModel.sidebarMode.collectAsState()
    val barWidth = (48 * uiScale).dp
    val iconSize = (24 * uiScale).dp

    // Micro-animations
    val infiniteTransition = rememberInfiniteTransition(label = "iconPulse")
    
    val aiPulse by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "aiPulse"
    )

    val terminalBlink by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "terminalBlink"
    )

    val settingsRotation by animateFloatAsState(
        targetValue = if (sidebarOpen && sidebarMode == TerminalViewModel.SidebarMode.SETTINGS) 360f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "settingsRotation"
    )

    val browserGlow by animateFloatAsState(
        targetValue = if (sidebarOpen && sidebarMode == TerminalViewModel.SidebarMode.BROWSER) 1f else 0f,
        animationSpec = tween(500),
        label = "browserGlow"
    )

    Column(modifier = Modifier.fillMaxHeight().width(barWidth).background(Color(0xFF161B22)), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(12.dp))
        
        // Projects Button
        IconButton(onClick = { viewModel.setSidebarMode(TerminalViewModel.SidebarMode.PROJECTS) }, modifier = Modifier.size(barWidth)) {
            Icon(Icons.Default.Menu, "Projects", tint = if (sidebarOpen && sidebarMode == TerminalViewModel.SidebarMode.PROJECTS) Color.White else Color.Gray, modifier = Modifier.size(iconSize))
        }
        
        // Explorer Button (Files)
        IconButton(onClick = { viewModel.setSidebarMode(TerminalViewModel.SidebarMode.EXPLORER) }, modifier = Modifier.size(barWidth)) {
            Icon(Icons.Default.Folder, "Explorer", tint = if (sidebarOpen && sidebarMode == TerminalViewModel.SidebarMode.EXPLORER) Color.White else Color.Gray, modifier = Modifier.size(iconSize))
        }

        // Git Button
        IconButton(onClick = { viewModel.setSidebarMode(TerminalViewModel.SidebarMode.GIT) }, modifier = Modifier.size(barWidth)) {
            Icon(Icons.Default.AccountTree, "Git", tint = if (sidebarOpen && sidebarMode == TerminalViewModel.SidebarMode.GIT) Color.White else Color.Gray, modifier = Modifier.size(iconSize))
        }

        // Search Button
        IconButton(onClick = { viewModel.setSidebarMode(TerminalViewModel.SidebarMode.SEARCH) }, modifier = Modifier.size(barWidth)) {
            Icon(Icons.Default.Search, "Search", tint = if (sidebarOpen && sidebarMode == TerminalViewModel.SidebarMode.SEARCH) Color.White else Color.Gray, modifier = Modifier.size(iconSize))
        }

        // Extensions Button
        IconButton(onClick = { viewModel.setSidebarMode(TerminalViewModel.SidebarMode.EXTENSIONS) }, modifier = Modifier.size(barWidth)) {
            Icon(Icons.Default.Extension, "Extensions", tint = if (sidebarOpen && sidebarMode == TerminalViewModel.SidebarMode.EXTENSIONS) Color.White else Color.Gray, modifier = Modifier.size(iconSize))
        }

        // Marketplace Button
        IconButton(onClick = { viewModel.setSidebarMode(TerminalViewModel.SidebarMode.MARKETPLACE) }, modifier = Modifier.size(barWidth)) {
            Icon(Icons.Default.Storefront, "Marketplace", tint = if (sidebarOpen && sidebarMode == TerminalViewModel.SidebarMode.MARKETPLACE) Color.White else Color.Gray, modifier = Modifier.size(iconSize))
        }

        // AI Assistant Button
        IconButton(onClick = { viewModel.setSidebarMode(TerminalViewModel.SidebarMode.AI) }, modifier = Modifier.size(barWidth)) {
            val isActive = sidebarOpen && sidebarMode == TerminalViewModel.SidebarMode.AI
            Icon(
                Icons.Default.AutoAwesome, 
                "AI Assistant", 
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

        // Terminal Panel Button
        IconButton(onClick = { viewModel.togglePanel(!isPanelVisible) }, modifier = Modifier.size(barWidth)) {
            Icon(
                Icons.Default.Terminal, 
                "Terminal", 
                tint = if (isPanelVisible) Color.White else Color.Gray, 
                modifier = Modifier
                    .size(iconSize)
                    .graphicsLayer(alpha = if (isPanelVisible) terminalBlink else 1f)
            )
        }

        // Debug Button
        IconButton(onClick = { viewModel.setSidebarMode(TerminalViewModel.SidebarMode.DEBUG) }, modifier = Modifier.size(barWidth)) {
            Icon(Icons.Default.BugReport, "Debug", tint = if (sidebarOpen && sidebarMode == TerminalViewModel.SidebarMode.DEBUG) Color.White else Color.Gray, modifier = Modifier.size(iconSize))
        }

        // Browser Button
        IconButton(onClick = { viewModel.setSidebarMode(TerminalViewModel.SidebarMode.BROWSER) }, modifier = Modifier.size(barWidth)) {
            val isActive = sidebarOpen && sidebarMode == TerminalViewModel.SidebarMode.BROWSER
            Icon(
                Icons.Default.Language, 
                "Browser", 
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
        


        // Settings Button (Gear)
        IconButton(onClick = { viewModel.setSidebarMode(TerminalViewModel.SidebarMode.SETTINGS) }, modifier = Modifier.size(barWidth)) {
            Icon(
                Icons.Default.Settings, 
                "Settings", 
                tint = if (sidebarOpen && sidebarMode == TerminalViewModel.SidebarMode.SETTINGS) Color.White else Color.Gray, 
                modifier = Modifier
                    .size(iconSize)
                    .graphicsLayer(rotationZ = settingsRotation)
            )
        }
    }
}
