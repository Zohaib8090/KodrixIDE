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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kodrix.zohaib.ui.SyntaxVisualTransformation
import com.kodrix.zohaib.viewmodel.DesktopIDEViewModel

@Composable
fun EditorArea(viewModel: DesktopIDEViewModel, editorFontSize: Int = 14, uiScale: Float = 1f) {
    val openTabs by viewModel.state.openTabs.collectAsState()
    val activeIndices by viewModel.state.activeTabIndices.collectAsState()
    val fileContents by viewModel.state.fileContents.collectAsState()
    val unsavedChanges by viewModel.state.unsavedChanges.collectAsState()
    val showLineNumbers by viewModel.state.showLineNumbers.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0D1117))) {
        // ── Editor Toolbar ──────────────────────────────────────────
        val activePath = if (activeIndices.isNotEmpty()) openTabs.getOrNull(activeIndices.first()) else null
        if (activePath != null) {
            EditorToolbar(
                filePath = activePath,
                isModified = activePath in unsavedChanges,
                onSave = { viewModel.saveFile(activePath) },
                onToggleLineNumbers = { viewModel.state.showLineNumbers.value = !viewModel.state.showLineNumbers.value },
                showLineNumbers = showLineNumbers,
                uiScale = uiScale
            )
        }

        // ── Tab Bar ─────────────────────────────────────────────────
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
                val fileExt = fileName.substringAfterLast('.', "").lowercase()
                val isActive = index in activeIndices
                val isModified = path in unsavedChanges

                // File icon color based on extension
                val iconColor = when {
                    fileExt in listOf("kt", "kts", "java") -> Color(0xFFE3B341)
                    fileExt in listOf("py", "pyw") -> Color(0xFF3572A5)
                    fileExt in listOf("js", "mjs", "cjs") -> Color(0xFFF1E05A)
                    fileExt in listOf("ts", "tsx") -> Color(0xFF3178C6)
                    fileExt in listOf("html", "htm") -> Color(0xFFE34C26)
                    fileExt in listOf("css", "scss", "less") -> Color(0xFF563D7C)
                    fileExt in listOf("json") -> Color(0xFF8B949E)
                    fileExt in listOf("md", "mdx") -> Color(0xFF58A6FF)
                    fileExt in listOf("c", "h", "cpp", "cc", "cxx", "hpp") -> Color(0xFF555555)
                    fileExt in listOf("rs") -> Color(0xFFDEA584)
                    fileExt in listOf("go") -> Color(0xFF00ADD8)
                    fileExt in listOf("sh", "bash", "zsh") -> Color(0xFF89E051)
                    else -> Color(0xFF8B949E)
                }

                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(min = (100 * uiScale).dp, max = (220 * uiScale).dp)
                        .background(if (isActive) Color(0xFF0D1117) else Color(0xFF161B22))
                        .clickable { viewModel.openFile(path) }
                        .padding(horizontal = (8 * uiScale).dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Colored dot as file icon
                        Box(
                            modifier = Modifier
                                .size((8 * uiScale).dp)
                                .clipToBounds()
                                .background(iconColor)
                        )
                        Spacer(modifier = Modifier.width((6 * uiScale).dp))
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
                            tint = if (isActive) Color.Gray else Color(0xFF484F58),
                            modifier = Modifier.size((10 * uiScale).dp).clickable { viewModel.closeTab(index) }
                        )
                    }
                }
            }
        }

        // ── Editor Content ──────────────────────────────────────────
        if (activePath != null) {
            val content = fileContents[activePath] ?: ""
            val extension = activePath.substringAfterLast('.', "").lowercase()
            CodeEditorContent(
                content = content,
                filePath = activePath,
                extension = extension,
                editorFontSize = editorFontSize,
                uiScale = uiScale,
                showLineNumbers = showLineNumbers,
                onContentChange = { newContent -> viewModel.updateFileContent(activePath, newContent) },
                onSave = { viewModel.saveFile(activePath) }
            )
        } else {
            // Empty state
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

/**
 * Editor toolbar: file path breadcrumb, save, line number toggle, file info.
 */
@Composable
private fun EditorToolbar(
    filePath: String,
    isModified: Boolean,
    onSave: () -> Unit,
    onToggleLineNumbers: () -> Unit,
    showLineNumbers: Boolean,
    uiScale: Float = 1f
) {
    val fileName = filePath.substringAfterLast("/")
    val dirPath = filePath.removeSuffix("/$fileName")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height((32 * uiScale).dp)
            .background(Color(0xFF161B22))
            .padding(horizontal = (10 * uiScale).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // File icon
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val iconColor = when {
            ext in listOf("kt", "java") -> Color(0xFFE3B341)
            ext in listOf("py") -> Color(0xFF3572A5)
            ext in listOf("js", "ts", "tsx") -> Color(0xFF3178C6)
            ext in listOf("html") -> Color(0xFFE34C26)
            ext in listOf("css") -> Color(0xFF563D7C)
            ext in listOf("json") -> Color(0xFF8B949E)
            ext in listOf("md") -> Color(0xFF58A6FF)
            ext in listOf("c", "cpp", "h") -> Color(0xFF555555)
            else -> Color(0xFF8B949E)
        }

        Box(
            modifier = Modifier.size((12 * uiScale).dp).background(iconColor),
            contentAlignment = Alignment.Center
        ) {
            Text(ext.take(1).uppercase(), color = Color.White, fontSize = (7 * uiScale).sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        }

        Spacer(modifier = Modifier.width((8 * uiScale).dp))

        // Breadcrumb path
        val pathSegments = dirPath.split("/").takeLast(3)
        pathSegments.forEachIndexed { i, segment ->
            if (i > 0) {
                Text("/", color = Color(0xFF484F58), fontSize = (11 * uiScale).sp, fontFamily = FontFamily.Monospace)
            }
            Text(segment, color = Color(0xFF8B949E), fontSize = (11 * uiScale).sp, fontFamily = FontFamily.Monospace)
            Text("/", color = Color(0xFF484F58), fontSize = (11 * uiScale).sp, fontFamily = FontFamily.Monospace)
        }
        Text(fileName, color = Color(0xFFC9D1D9), fontSize = (11 * uiScale).sp, fontFamily = FontFamily.Monospace, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)

        Spacer(modifier = Modifier.weight(1f))

        // Modified indicator
        if (isModified) {
            Box(
                modifier = Modifier
                    .padding(horizontal = (6 * uiScale).dp, vertical = (2 * uiScale).dp)
                    .background(Color(0xFF238636), shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
            ) {
                Text("Modified", color = Color.White, fontSize = (9 * uiScale).sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width((6 * uiScale).dp))
        }

        // Line numbers toggle
        Icon(
            Icons.Default.FormatListNumbered,
            contentDescription = "Toggle Line Numbers",
            tint = if (showLineNumbers) Color(0xFF58A6FF) else Color(0xFF484F58),
            modifier = Modifier
                .size((16 * uiScale).dp)
                .clickable { onToggleLineNumbers() }
        )
        Spacer(modifier = Modifier.width((10 * uiScale).dp))

        // Save button
        Icon(
            Icons.Default.Save,
            contentDescription = "Save (Ctrl+S)",
            tint = if (isModified) Color.White else Color(0xFF484F58),
            modifier = Modifier
                .size((16 * uiScale).dp)
                .clickable { onSave() }
        )
    }
}

@Composable
private fun CodeEditorContent(
    content: String,
    filePath: String,
    extension: String,
    editorFontSize: Int = 14,
    uiScale: Float = 1f,
    showLineNumbers: Boolean = true,
    onContentChange: (String) -> Unit,
    onSave: () -> Unit
) {
    var text by remember(content) { mutableStateOf(content) }
    val scrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }

    // Sync external content changes (e.g. after git checkout)
    LaunchedEffect(content) {
        if (text != content) text = content
    }

    // Syntax highlighting transformation
    val syntaxTransformation: VisualTransformation = remember(extension) {
        SyntaxVisualTransformation(extension, emptyList())
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0D1117))) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .focusRequester(focusRequester)
                .onKeyEvent { event ->
                    // ── Keyboard Shortcuts ──
                    if (event.type == KeyEventType.KeyDown) {
                        val ctrl = event.isCtrlPressed
                        val shift = event.isShiftPressed

                        when {
                            ctrl && event.key == Key.S -> {
                                onSave()
                                true
                            }
                            ctrl && event.key == Key.W -> {
                                // Close current tab — handled by parent
                                false // Let parent handle it
                            }
                            ctrl && shift && event.key == Key.P -> {
                                // Command palette
                                false
                            }
                            ctrl && event.key == Key.P -> {
                                // Quick open file
                                false
                            }
                            ctrl && event.key == Key.N -> {
                                // New file
                                false
                            }
                            else -> false
                        }
                    } else {
                        false
                    }
                }
        ) {
            // Line numbers
            val lineCount = text.lines().size
            if (showLineNumbers) {
                Column(
                    modifier = Modifier.width(52.dp).padding(end = 8.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    for (i in 1..lineCount.coerceAtLeast(1)) {
                        Text(
                            "$i",
                            color = Color(0xFF484F58),
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = editorFontSize.sp,
                                lineHeight = (editorFontSize + 6).sp
                            ),
                            modifier = Modifier.height(20.dp)
                        )
                    }
                }
            }

            // Code area with syntax highlighting
            BasicTextField(
                value = text,
                onValueChange = { newValue ->
                    text = newValue
                    onContentChange(newValue)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(start = 4.dp),
                textStyle = TextStyle(
                    color = Color(0xFFC9D1D9),
                    fontFamily = FontFamily.Monospace,
                    fontSize = editorFontSize.sp,
                    lineHeight = 20.sp
                ),
                cursorBrush = SolidColor(Color(0xFF58A6FF)),
                visualTransformation = syntaxTransformation,
                decorationBox = { innerTextField ->
                    Box(modifier = Modifier.fillMaxWidth().padding(start = 8.dp)) {
                        if (text.isEmpty()) {
                            Text(
                                "Start typing...",
                                color = Color(0xFF484F58),
                                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = editorFontSize.sp)
                            )
                        }
                        innerTextField()
                    }
                }
            )
        }
    }
}