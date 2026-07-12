package com.kodrix.zohaib.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kodrix.zohaib.platform.Platform
import com.kodrix.zohaib.viewmodel.BaseIDEViewModel
import com.kodrix.zohaib.viewmodel.SidebarMode

@Composable
fun SharedIDEView(viewModel: BaseIDEViewModel) {
    val sidebarMode by viewModel.state.sidebarMode.collectAsState()
    val sidebarOpen by viewModel.state.sidebarOpen.collectAsState()
    val nodeVersion by viewModel.state.nodeVersion.collectAsState()
    val gitVersion by viewModel.state.gitVersion.collectAsState()
    val activeProject by viewModel.state.activeProject.collectAsState()
    val projects by viewModel.state.projects.collectAsState()
    val openTabs by viewModel.state.openTabs.collectAsState()

    KodrixTheme {
        Row(modifier = Modifier.fillMaxSize()) {
            // Activity Bar
            ActivityBarCompact(
                currentMode = sidebarMode,
                onModeSelected = { viewModel.state.setSidebarMode(it) }
            )

            // Sidebar
            if (sidebarOpen) {
                Box(
                    modifier = Modifier
                        .width(250.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    when (sidebarMode) {
                        SidebarMode.EXPLORER -> ExplorerSidebar(viewModel)
                        SidebarMode.GIT -> GitSidebar(viewModel)
                        SidebarMode.SETTINGS -> SettingsSidebar(nodeVersion, gitVersion)
                        SidebarMode.AI -> AiSidebar(viewModel)
                        else -> DefaultSidebar(sidebarMode)
                    }
                }
            }

            // Main editor area
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                if (openTabs.isEmpty()) {
                    WelcomeContent(Platform.name)
                } else {
                    EditorTabs(openTabs, viewModel)
                }
            }

            // Panel (terminal, etc.)
            val isPanelVisible by viewModel.state.isPanelVisible.collectAsState()
            if (isPanelVisible) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    TerminalPlaceholder()
                }
            }
        }
    }
}

@Composable
private fun ActivityBarCompact(currentMode: SidebarMode, onModeSelected: (SidebarMode) -> Unit) {
    Column(
        modifier = Modifier
            .width(48.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        SidebarMode.entries.filter { it != SidebarMode.TERMINAL && it != SidebarMode.MARKETPLACE }.forEach { mode ->
            val label = when (mode) {
                SidebarMode.PROJECTS -> "P"
                SidebarMode.EXPLORER -> "E"
                SidebarMode.GIT -> "G"
                SidebarMode.SEARCH -> "S"
                SidebarMode.AI -> "A"
                SidebarMode.DEBUG -> "D"
                SidebarMode.BROWSER -> "B"
                SidebarMode.SETTINGS -> ">"
                else -> "."
            }
            val isActive = currentMode == mode
            TextButton(
                onClick = { onModeSelected(mode) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(if (isActive) MaterialTheme.colorScheme.background else Color.Transparent)
            ) {
                Text(
                    label,
                    color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun ExplorerSidebar(viewModel: BaseIDEViewModel) {
    val projects by viewModel.state.projects.collectAsState()
    Column(modifier = Modifier.padding(12.dp)) {
        Text("EXPLORER", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        Spacer(modifier = Modifier.height(8.dp))
        if (projects.isEmpty()) {
            Text("No projects open", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn {
                items(projects) { project ->
                    Text(
                        project,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.switchProject(project) }
                            .padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun GitSidebar(viewModel: BaseIDEViewModel) {
    val branch by viewModel.state.gitCurrentBranch.collectAsState()
    val changes by viewModel.state.gitChanges.collectAsState()
    Column(modifier = Modifier.padding(12.dp)) {
        Text("SOURCE CONTROL", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Branch: $branch", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.height(4.dp))
        Text("${changes.size} changes", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SettingsSidebar(nodeVersion: String, gitVersion: String) {
    Column(modifier = Modifier.padding(12.dp)) {
        Text("SETTINGS", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Platform: ${Platform.name}", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.height(4.dp))
        Text("Node: $nodeVersion", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.height(4.dp))
        Text("Git: $gitVersion", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AiSidebar(viewModel: BaseIDEViewModel) {
    val messages by viewModel.state.aiChatMessages.collectAsState()
    Column(modifier = Modifier.padding(12.dp)) {
        Text("AI ASSISTANT", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text("${messages.size} messages", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DefaultSidebar(mode: SidebarMode) {
    Column(modifier = Modifier.padding(12.dp)) {
        Text(mode.name, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Coming soon...", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun WelcomeContent(platformName: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Kodrix IDE",
            color = Color.White,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Running on $platformName",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Open a project to get started",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun EditorTabs(tabs: List<String>, viewModel: BaseIDEViewModel) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Tab bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            tabs.forEachIndexed { index, tab ->
                val fileName = tab.substringAfterLast("/")
                val isActive = index in viewModel.state.activeTabIndices.collectAsState().value
                Surface(
                    modifier = Modifier
                        .height(36.dp)
                        .clickable { viewModel.openFile(tab) },
                    color = if (isActive) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(fileName, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("x", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clickable { viewModel.closeTab(index) })
                    }
                }
            }
        }

        // Editor content placeholder
        Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Editor coming soon...",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TerminalPlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize().padding(8.dp),
        contentAlignment = Alignment.TopStart
    ) {
        Text(
            "Terminal (not yet available on this platform)",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
    }
}
