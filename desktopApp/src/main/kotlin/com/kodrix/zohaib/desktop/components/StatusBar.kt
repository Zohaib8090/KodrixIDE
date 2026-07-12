package com.kodrix.zohaib.desktop.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kodrix.zohaib.viewmodel.DesktopIDEViewModel

@Composable
fun StatusBar(viewModel: DesktopIDEViewModel, uiScale: Float = 1f) {
    val activeProject by viewModel.state.activeProject.collectAsState()
    val branch by viewModel.state.gitCurrentBranch.collectAsState()
    val openTabs by viewModel.state.openTabs.collectAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height((24 * uiScale).dp)
            .background(Color(0xFF0D1117))
            .border(1.dp, Color(0xFF30363D))
            .padding(horizontal = (12 * uiScale).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (activeProject != null) {
            Text(activeProject!!, color = Color(0xFF58A6FF), fontSize = (11 * uiScale).sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(modifier = Modifier.weight(1f))
        if (branch.isNotEmpty()) {
            Text(branch, color = Color(0xFF8B949E), fontSize = (11 * uiScale).sp, fontFamily = FontFamily.Monospace)
            Spacer(modifier = Modifier.width((12 * uiScale).dp))
        }
        Text("${openTabs.size} files open", color = Color(0xFF8B949E), fontSize = (11 * uiScale).sp, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.width((12 * uiScale).dp))
        Text("Desktop", color = Color(0xFF8B949E), fontSize = (11 * uiScale).sp, fontFamily = FontFamily.Monospace)
    }
}
