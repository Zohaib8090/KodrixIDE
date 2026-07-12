package com.kodrix.zohaib.desktop.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kodrix.zohaib.viewmodel.DesktopIDEViewModel

@Composable
fun EditorArea(viewModel: DesktopIDEViewModel, editorFontSize: Int = 14, uiScale: Float = 1f) {
    val openTabs by viewModel.state.openTabs.collectAsState()
    val activeIndices by viewModel.state.activeTabIndices.collectAsState()
    val fileContents by viewModel.state.fileContents.collectAsState()
    val unsavedChanges by viewModel.state.unsavedChanges.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0D1117))) {
        // Tab Bar - exact match to Android: 35dp height, #161B22 bg
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height((35 * uiScale).dp)
                .background(Color(0xFF161B22))
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.Bottom
        ) {
            openTabs.forEachIndexed { index, path ->
                val fileName = path.substringAfterLast("/")
                val isActive = index in activeIndices
                val isModified = path in unsavedChanges

                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(min = (100 * uiScale).dp, max = (200 * uiScale).dp)
                        .background(if (isActive) Color(0xFF0D1117) else Color(0xFF161B22))
                        .clickable { viewModel.openFile(path) }
                        .padding(horizontal = (8 * uiScale).dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = fileName,
                            color = if (isActive) Color.White else Color.Gray,
                            fontSize = (11 * uiScale).sp,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                        if (isModified) {
                            Box(Modifier.size((8 * uiScale).dp).background(Color(0xFF58A6FF), shape = androidx.compose.foundation.shape.CircleShape))
                        }
                        Spacer(modifier = Modifier.width((4 * uiScale).dp))
                        Icon(
                            Icons.Default.Close,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size((10 * uiScale).dp).clickable { viewModel.closeTab(index) }
                        )
                    }
                }
            }
        }

        // Editor content
        val activePath = if (activeIndices.isNotEmpty()) openTabs.getOrNull(activeIndices.first()) else null
        if (activePath != null) {
            val content = fileContents[activePath] ?: ""
            CodeEditorContent(content, editorFontSize, uiScale) { newContent ->
                viewModel.updateFileContent(activePath, newContent)
            }
        } else {
            // Empty state - matches Android
            Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0D1117)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Code, null, tint = Color.DarkGray, modifier = Modifier.size((48 * uiScale).dp))
                    Spacer(Modifier.height((16 * uiScale).dp))
                    Text("Select a file to edit", color = Color.Gray, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.state.setSidebarMode(com.kodrix.zohaib.viewmodel.SidebarMode.EXPLORER) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D))
                    ) {
                        Text("Open Explorer", color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeEditorContent(content: String, editorFontSize: Int = 14, uiScale: Float = 1f, onContentChange: (String) -> Unit) {
    var text by remember(content) { mutableStateOf(content) }
    val scrollState = rememberScrollState()

    LaunchedEffect(content) {
        if (text != content) text = content
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0D1117))) {
        Row(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
            // Line numbers
            val lineCount = text.lines().size
            Column(
                modifier = Modifier.width(48.dp).padding(end = 8.dp),
                horizontalAlignment = Alignment.End
            ) {
                for (i in 1..lineCount.coerceAtLeast(1)) {
                    Text(
                        "$i",
                        color = Color(0xFF484F58),
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = editorFontSize.sp, lineHeight = (editorFontSize + 6).sp),
                        modifier = Modifier.height(20.dp)
                    )
                }
            }

            // Code area
            BasicTextField(
                value = text,
                onValueChange = { newValue ->
                    text = newValue
                    onContentChange(newValue)
                },
                modifier = Modifier.fillMaxWidth().fillMaxHeight().padding(start = 4.dp),
                textStyle = TextStyle(
                    color = Color(0xFFC9D1D9),
                    fontFamily = FontFamily.Monospace,
                    fontSize = editorFontSize.sp,
                    lineHeight = 20.sp
                ),
                cursorBrush = SolidColor(Color(0xFF58A6FF)),
                decorationBox = { innerTextField ->
                    Box(modifier = Modifier.fillMaxWidth().padding(start = 8.dp)) {
                        if (text.isEmpty()) {
                            Text("Start typing...", color = Color(0xFF484F58), style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = editorFontSize.sp))
                        }
                        innerTextField()
                    }
                }
            )
        }
    }
}
