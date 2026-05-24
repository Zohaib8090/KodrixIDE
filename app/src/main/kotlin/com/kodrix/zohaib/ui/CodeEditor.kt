package com.kodrix.zohaib.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kodrix.zohaib.viewmodel.TerminalViewModel

@Composable
fun CodeEditor(viewModel: TerminalViewModel, viewportId: Int = 0) {
    val openTabs by viewModel.openTabs.collectAsState()
    val activeTabIndices by viewModel.activeTabIndices.collectAsState()
    val activeTabIndex = activeTabIndices[viewportId] ?: -1
    val editorFontSize by viewModel.editorFontSize.collectAsState()
    val uiScale by viewModel.uiScale.collectAsState()
    val focusedViewportId by viewModel.focusedViewportId.collectAsState()
    val isFocused = focusedViewportId == viewportId
    val diagnostics by viewModel.lspDiagnostics.collectAsState()
    val completionItems by viewModel.completionItems.collectAsState()

    if (activeTabIndex == -1 || openTabs.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0D1117)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Code, null, tint = Color.DarkGray, modifier = Modifier.size((48 * uiScale).dp))
                Spacer(Modifier.height(16.dp))
                Text("Select a file to edit", color = Color.Gray, fontSize = (14 * uiScale).sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.setSidebarMode(TerminalViewModel.SidebarMode.EXPLORER) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D))
                ) {
                    Text("Open Explorer", color = Color.White)
                }
            }
        }
        return
    }

    val activeTab = openTabs.getOrNull(activeTabIndex) ?: return
    val errors = diagnostics.filter { (it.severity ?: 1) == 1 }
    val warnings = diagnostics.filter { (it.severity ?: 1) == 2 }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0D1117))
        .clickable(
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null
        ) { viewModel.updateFocusedViewport(viewportId) }
        .border(1.dp, if (isFocused) Color(0xFF58A6FF) else Color.Transparent)
    ) {
        // Tab Bar
        Row(
            modifier = Modifier.fillMaxWidth().height((35 * uiScale).dp)
                .background(Color(0xFF161B22)).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.Bottom
        ) {
            openTabs.forEachIndexed { index, tab ->
                val isActive = index == activeTabIndex
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(min = (100 * uiScale).dp, max = (200 * uiScale).dp)
                        .background(if (isActive) Color(0xFF0D1117) else Color(0xFF161B22))
                        .clickable { viewModel.switchTab(viewportId, index) }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = tab.file.name,
                            color = if (isActive) Color.White else Color.Gray,
                            fontSize = (11 * uiScale).sp,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                        if (tab.isModified) {
                            Box(Modifier.size(8.dp).background(Color(0xFF58A6FF), shape = androidx.compose.foundation.shape.CircleShape))
                        }
                        IconButton(onClick = { viewModel.closeTab(index) }, modifier = Modifier.size((16 * uiScale).dp)) {
                            Icon(Icons.Default.Close, null, tint = Color.Gray, modifier = Modifier.size((10 * uiScale).dp))
                        }
                    }
                    if (isActive) {
                        Box(Modifier.fillMaxWidth().height(2.dp).background(Color(0xFFF78166)).align(Alignment.TopCenter))
                    }
                }
                VerticalDivider(modifier = Modifier.fillMaxHeight().width(1.dp), color = Color(0xFF30363D))
            }
        }

        // Toolbar with LSP badges
        Row(
            modifier = Modifier.fillMaxWidth().height((28 * uiScale).dp)
                .background(Color(0xFF0D1117)).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                activeTab.file.path.substringAfter("projects/"),
                color = Color(0xFF8B949E),
                fontSize = (9 * uiScale).sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.weight(1f))

            // Error badge
            if (errors.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
                    Icon(Icons.Default.Error, null, tint = Color(0xFFF85149), modifier = Modifier.size((12 * uiScale).dp))
                    Spacer(Modifier.width(2.dp))
                    Text("${errors.size}", color = Color(0xFFF85149), fontSize = (10 * uiScale).sp, fontFamily = FontFamily.Monospace)
                }
            }
            // Warning badge
            if (warnings.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
                    Icon(Icons.Default.Warning, null, tint = Color(0xFFD29922), modifier = Modifier.size((12 * uiScale).dp))
                    Spacer(Modifier.width(2.dp))
                    Text("${warnings.size}", color = Color(0xFFD29922), fontSize = (10 * uiScale).sp, fontFamily = FontFamily.Monospace)
                }
            }

            IconButton(onClick = { viewModel.saveCurrentFile(viewportId) }, modifier = Modifier.size((24 * uiScale).dp)) {
                Icon(Icons.Default.Save, null, tint = Color(0xFF58A6FF), modifier = Modifier.size((14 * uiScale).dp))
            }
            IconButton(onClick = { viewModel.splitViewport(viewportId) }, modifier = Modifier.size((24 * uiScale).dp)) {
                Icon(Icons.Default.VerticalSplit, null, tint = Color.Gray, modifier = Modifier.size((14 * uiScale).dp))
            }
            if (activeTabIndices.size > 1) {
                IconButton(onClick = { viewModel.removeViewport(viewportId) }, modifier = Modifier.size((24 * uiScale).dp)) {
                    Icon(Icons.Default.Close, null, tint = Color(0xFFF85149), modifier = Modifier.size((14 * uiScale).dp))
                }
            }
        }

        HorizontalDivider(color = Color(0xFF30363D))

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val verticalScrollState = rememberScrollState()
            val horizontalScrollState = rememberScrollState()
            val lineCount = activeTab.text.text.count { it == '\n' } + 1

            val showLineNumbers by viewModel.showLineNumbers.collectAsState()

            Row(modifier = Modifier.fillMaxSize().verticalScroll(verticalScrollState)) {
                // Line numbers
                if (showLineNumbers) {
                    Column(
                        modifier = Modifier.width((40 * uiScale).dp).background(Color(0xFF0D1117)).padding(top = 8.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        for (i in 1..lineCount) {
                            Text(
                                text = i.toString(),
                                color = Color(0xFF484F58),
                                fontSize = editorFontSize.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(end = 8.dp),
                                lineHeight = (editorFontSize * 1.5).sp
                            )
                        }
                    }
                }

                val extension = activeTab.file.extension
                Box(modifier = Modifier.fillMaxWidth().horizontalScroll(horizontalScrollState)) {
                    BasicTextField(
                        value = activeTab.text,
                        onValueChange = { viewModel.updateEditorText(viewportId, it) },
                        modifier = Modifier.padding(top = 8.dp, start = 8.dp).widthIn(min = 2000.dp), // Ensure wide enough for horizontal scroll
                        textStyle = TextStyle(
                            color = Color(0xFFCDD9E5),
                            fontSize = editorFontSize.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = (editorFontSize * 1.5).sp
                        ),
                        cursorBrush = SolidColor(Color(0xFF58A6FF)),
                        visualTransformation = remember(extension, diagnostics) { SyntaxVisualTransformation(extension, diagnostics) },
                        keyboardOptions = KeyboardOptions(autoCorrect = false, imeAction = ImeAction.None)
                    )
                }
            }

            // Floating autocomplete dropdown — anchored outside scroll so it always shows
            if (completionItems.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = (48 * uiScale).dp, bottom = 8.dp)
                        .background(Color(0xFF1C2128), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFF58A6FF).copy(alpha = 0.5f), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .heightIn(max = 220.dp)
                        .widthIn(min = 220.dp, max = 400.dp)
                        .shadow(8.dp)
                ) {
                    LazyColumn {
                        items(completionItems.take(20)) { item ->
                            val icon = when (item.kind) {
                                1 -> Icons.Default.TextSnippet
                                2, 3 -> Icons.Default.Functions
                                5, 6, 10 -> Icons.Default.Code
                                7, 8, 22 -> Icons.Default.Class
                                9 -> Icons.Default.Inventory2
                                11, 12 -> Icons.Default.SettingsEthernet
                                13, 20 -> Icons.Default.List
                                14 -> Icons.Default.VpnKey
                                15 -> Icons.Default.Extension
                                16 -> Icons.Default.Palette
                                17 -> Icons.Default.Description
                                18 -> Icons.Default.Link
                                19 -> Icons.Default.Folder
                                21 -> Icons.Default.Lock
                                25 -> Icons.Default.TypeSpecimen
                                else -> Icons.Default.Code
                            }
                            val iconColor = when (item.kind) {
                                2, 3 -> Color(0xFFB392F0) // Purple
                                5, 6, 10 -> Color(0xFF79C0FF) // Blue
                                7, 8, 22 -> Color(0xFFFFA657) // Orange
                                14 -> Color(0xFFFF7B72) // Red
                                15 -> Color(0xFF7EE787) // Green
                                else -> Color(0xFF58A6FF)
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.applyCompletion(viewportId, item) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(icon, null, tint = iconColor, modifier = Modifier.size((14 * uiScale).dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = item.label,
                                    color = Color.White,
                                    fontSize = (12 * uiScale).sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (!item.detail.isNullOrBlank()) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = item.detail,
                                        color = Color.Gray,
                                        fontSize = (10 * uiScale).sp,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                            }
                            HorizontalDivider(color = Color(0xFF30363D).copy(alpha = 0.5f))
                        }
                    }
                }
            }

        }

        // LSP Diagnostics panel — shown when issues exist
        if (diagnostics.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 120.dp)
                    .background(Color(0xFF0D1117))
                    .border(1.dp, Color(0xFF30363D))
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF161B22)).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("PROBLEMS", color = Color.White.copy(alpha = 0.7f), fontSize = (10 * uiScale).sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    if (errors.isNotEmpty()) {
                        Text("${errors.size} errors", color = Color(0xFFF85149), fontSize = (10 * uiScale).sp)
                        Spacer(Modifier.width(4.dp))
                    }
                    if (warnings.isNotEmpty()) {
                        Text("${warnings.size} warnings", color = Color(0xFFD29922), fontSize = (10 * uiScale).sp)
                    }
                }
                diagnostics.forEach { diag ->
                    val isError = (diag.severity ?: 1) == 1
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (isError) Icons.Default.Error else Icons.Default.Warning,
                            null,
                            tint = if (isError) Color(0xFFF85149) else Color(0xFFD29922),
                            modifier = Modifier.size((12 * uiScale).dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "[${diag.range.start.line + 1}:${diag.range.start.character + 1}] ${diag.message}",
                            color = Color(0xFFCDD9E5),
                            fontSize = (10 * uiScale).sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
