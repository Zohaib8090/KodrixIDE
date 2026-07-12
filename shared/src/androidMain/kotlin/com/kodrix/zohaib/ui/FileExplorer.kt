package com.kodrix.zohaib.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kodrix.zohaib.viewmodel.TerminalViewModel
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.BasicTextField
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun FileExplorerContent(viewModel: TerminalViewModel) {
    val activeProject by viewModel.activeProject.collectAsState()
    val fileTree by viewModel.fileTree.collectAsState()
    val expandedDirs by viewModel.expandedDirs.collectAsState()
    val uiScale by viewModel.uiScale.collectAsState()
    val context = LocalContext.current
    val projectsRoot = java.io.File(context.filesDir, "projects")
    
    var showNewFileDialog by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().height((44 * uiScale).dp)
                .background(Color(0xFF0D1117)).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(activeProject?.uppercase() ?: "NO PROJECT", color = Color(0xFF58A6FF), 
                fontSize = (11 * uiScale).sp, fontWeight = FontWeight.Bold, 
                fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            
            if (activeProject != null) {
                IconButton(onClick = { showNewFileDialog = true }, modifier = Modifier.size((32 * uiScale).dp)) {
                    Icon(Icons.Default.Add, "New File", tint = Color(0xFF58A6FF), modifier = Modifier.size((18 * uiScale).dp))
                }
                IconButton(onClick = { showNewFolderDialog = true }, modifier = Modifier.size((32 * uiScale).dp)) {
                    Icon(Icons.Default.CreateNewFolder, "New Folder", tint = Color(0xFF58A6FF), modifier = Modifier.size((18 * uiScale).dp))
                }
                IconButton(onClick = { viewModel.refreshFileTree(java.io.File(projectsRoot, activeProject!!)) }, modifier = Modifier.size((32 * uiScale).dp)) {
                    Icon(Icons.Default.Refresh, "Reload", tint = Color(0xFF58A6FF), modifier = Modifier.size((18 * uiScale).dp))
                }
            }

            IconButton(onClick = { viewModel.toggleSidebar() }, modifier = Modifier.size((32 * uiScale).dp)) {
                Icon(Icons.Default.Close, null, tint = Color.Gray, modifier = Modifier.size((16 * uiScale).dp))
            }
        }
        HorizontalDivider(color = Color(0xFF30363D))

        if (activeProject == null) {
            Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("No project open.\nSwitch to PROJECTS tab.", color = Color.Gray,
                    fontSize = (11 * uiScale).sp, fontFamily = FontFamily.Monospace)
            }
        } else {
            val projFile = java.io.File(projectsRoot, activeProject!!)
            val projPath = projFile.absolutePath
            
            if (showNewFileDialog) {
                NewItemDialog(title = "New File in Root", onConfirm = { viewModel.createFile(projFile, it); showNewFileDialog = false }, onDismiss = { showNewFileDialog = false })
            }
            if (showNewFolderDialog) {
                NewItemDialog(title = "New Folder in Root", onConfirm = { viewModel.createFolder(projFile, it); showNewFolderDialog = false }, onDismiss = { showNewFolderDialog = false })
            }

            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                val rootChildren = fileTree[projPath] ?: emptyList()
                if (rootChildren.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("Project is empty.", color = Color.Gray, fontSize = (11 * uiScale).sp)
                    }
                }
                rootChildren.forEach { file ->
                    FileTreeNode(file, depth = 0, fileTree, expandedDirs, uiScale, onToggleDir = { viewModel.toggleDir(it) }, onOpenFile = { viewModel.openFile(it) }, viewModel)
                }
            }
        }
    }
}

@Composable
fun SearchContent(viewModel: TerminalViewModel) {
    val searchQuery by viewModel.searchQuery.collectAsState()
    val activeProject by viewModel.activeProject.collectAsState()
    val uiScale by viewModel.uiScale.collectAsState()
    val context = LocalContext.current
    val projectsRoot = java.io.File(context.filesDir, "projects")
    
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().height((44 * uiScale).dp)
                .background(Color(0xFF0D1117)).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("SEARCH", color = Color(0xFF58A6FF), fontSize = (11 * uiScale).sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f))
            IconButton(onClick = { viewModel.toggleSidebar() }, modifier = Modifier.size((32 * uiScale).dp)) {
                Icon(Icons.Default.Close, null, tint = Color.Gray, modifier = Modifier.size((16 * uiScale).dp))
            }
        }
        HorizontalDivider(color = Color(0xFF30363D))

        Box(modifier = Modifier.padding(12.dp)) {
            BasicTextField(
                value = searchQuery,
                onValueChange = { viewModel.updateSearchQuery(it) },
                modifier = Modifier.fillMaxWidth().background(Color(0xFF0D1117), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .border(1.dp, Color(0xFF30363D), androidx.compose.foundation.shape.RoundedCornerShape(4.dp)).padding(8.dp),
                textStyle = TextStyle(color = Color.White, fontSize = (12 * uiScale).sp, fontFamily = FontFamily.Monospace),
                cursorBrush = SolidColor(Color(0xFF58A6FF)),
                decorationBox = { innerTextField ->
                    if (searchQuery.isEmpty()) Text("Search in project...", color = Color.Gray, fontSize = (12 * uiScale).sp)
                    innerTextField()
                }
            )
        }
        
        LaunchedEffect(searchQuery, activeProject) {
            if (searchQuery.length < 2 || activeProject == null) {
                results = emptyList()
                return@LaunchedEffect
            }
            isSearching = true
            withContext(Dispatchers.IO) {
                val found = mutableListOf<SearchResult>()
                val projDir = java.io.File(projectsRoot, activeProject!!)
                projDir.walkTopDown().forEach { file ->
                    if (file.isFile && !file.path.contains("node_modules") && !file.name.startsWith(".")) {
                        try {
                            val lines = file.readLines()
                            lines.forEachIndexed { index, line ->
                                if (line.contains(searchQuery, ignoreCase = true)) {
                                    found.add(SearchResult(file, index + 1, line.trim()))
                                }
                            }
                        } catch (e: Exception) {}
                    }
                    if (found.size > 100) return@forEach
                }
                results = found
                isSearching = false
            }
        }

        if (isSearching) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp), color = Color(0xFF58A6FF), trackColor = Color.Transparent)
        }

        if (results.isEmpty() && searchQuery.length >= 2 && !isSearching) {
            Text("No results found.", color = Color.Gray, modifier = Modifier.padding(16.dp), fontSize = (11 * uiScale).sp)
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(results) { res ->
                Column(
                    modifier = Modifier.fillMaxWidth().clickable { viewModel.openFile(res.file) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(res.file.name, color = Color(0xFF58A6FF), fontSize = (11 * uiScale).sp, fontWeight = FontWeight.Bold)
                    Text("Line ${res.line}: ${res.content}", color = Color.Gray, fontSize = (10 * uiScale).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(res.file.parentFile?.name ?: "", color = Color(0xFF484F58), fontSize = (9 * uiScale).sp)
                    HorizontalDivider(modifier = Modifier.padding(top = 4.dp), color = Color(0xFF21262D))
                }
            }
        }
    }
}

data class SearchResult(val file: java.io.File, val line: Int, val content: String)

@Composable
fun FileTreeNode(
    file: java.io.File, depth: Int,
    fileTree: Map<String, List<java.io.File>>,
    expandedDirs: Set<String>,
    uiScale: Float,
    onToggleDir: (String) -> Unit,
    onOpenFile: (java.io.File) -> Unit,
    viewModel: TerminalViewModel
) {
    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }

    val clipboardFile by viewModel.clipboardFile.collectAsState()
    val context = LocalContext.current
    val isDir = file.isDirectory
    val isExpanded = expandedDirs.contains(file.absolutePath)
    val indent = (depth * 12).dp

    val icon = when {
        isDir -> if (isExpanded) "▾" else "▸"
        file.name.endsWith(".kt") || file.name.endsWith(".java") -> "☕"
        file.name.endsWith(".js") || file.name.endsWith(".ts") -> "📜"
        file.name.endsWith(".json") -> "{ }"
        file.name.endsWith(".md") -> "📝"
        file.name.endsWith(".html") -> "🌐"
        file.name.endsWith(".css") -> "🎨"
        else -> "📄"
    }

    val fileImportAction = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: android.net.Uri? ->
        uri?.let { viewModel.importFile(file, it) }
    }
    val fileExportAction = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: android.net.Uri? ->
        uri?.let { viewModel.exportFile(file, it) }
    }

    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable { if (isDir) onToggleDir(file.absolutePath) else onOpenFile(file) }
            .padding(start = indent + 16.dp, top = 3.dp, bottom = 3.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (isDir) icon else "  ", color = Color(0xFF8B949E), fontSize = (10 * uiScale).sp, modifier = Modifier.width(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(file.name, color = if (isDir) Color(0xFFCDD9E5) else Color(0xFF8B949E),
            fontSize = (11 * uiScale).sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
        
        Box {
            IconButton(onClick = { showMenu = true }, modifier = Modifier.size((24 * uiScale).dp)) {
                Icon(Icons.Default.MoreVert, null, tint = Color(0xFF484F58), modifier = Modifier.size((14 * uiScale).dp))
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                modifier = Modifier.background(Color(0xFF161B22))
            ) {
                val ext = file.extension.lowercase()
                if (ext == "html" || ext == "htm" || ext == "md") {
                    DropdownMenuItem(
                        text = { Text("Preview", color = Color(0xFF58A6FF)) },
                        onClick = { showMenu = false; viewModel.previewFile(file) },
                        leadingIcon = { Icon(Icons.Default.PlayArrow, null, tint = Color(0xFF58A6FF), modifier = Modifier.size(16.dp)) }
                    )
                    HorizontalDivider(color = Color(0xFF30363D))
                }
                if (isDir) {
                    DropdownMenuItem(
                        text = { Text("New File", color = Color.White) },
                        onClick = { showMenu = false; showNewFileDialog = true },
                        leadingIcon = { Icon(Icons.Default.Add, null, tint = Color.Gray, modifier = Modifier.size(16.dp)) }
                    )
                    DropdownMenuItem(
                        text = { Text("New Folder", color = Color.White) },
                        onClick = { showMenu = false; showNewFolderDialog = true },
                        leadingIcon = { Icon(Icons.Default.CreateNewFolder, null, tint = Color.Gray, modifier = Modifier.size(16.dp)) }
                    )
                    HorizontalDivider(color = Color(0xFF30363D))
                }
                DropdownMenuItem(
                    text = { Text("Open in Terminal", color = Color.White) },
                    onClick = { showMenu = false; viewModel.openInTerminal(file) },
                    leadingIcon = { Icon(Icons.Default.Terminal, null, tint = Color.Gray, modifier = Modifier.size(16.dp)) }
                )
                HorizontalDivider(color = Color(0xFF30363D))
                
                if (isDir) {
                    DropdownMenuItem(
                        text = { Text("Upload File", color = Color.White) },
                        onClick = { showMenu = false; fileImportAction.launch("*/*") },
                        leadingIcon = { Icon(Icons.Default.FileUpload, null, tint = Color.Gray, modifier = Modifier.size(16.dp)) }
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text("Download File", color = Color.White) },
                        onClick = { showMenu = false; fileExportAction.launch(file.name) },
                        leadingIcon = { Icon(Icons.Default.FileDownload, null, tint = Color.Gray, modifier = Modifier.size(16.dp)) }
                    )
                }
                HorizontalDivider(color = Color(0xFF30363D))

                DropdownMenuItem(
                    text = { Text("Copy", color = Color.White) },
                    onClick = { showMenu = false; viewModel.setClipboard(file, false) },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, null, tint = Color.Gray, modifier = Modifier.size(16.dp)) }
                )
                DropdownMenuItem(
                    text = { Text("Cut", color = Color.White) },
                    onClick = { showMenu = false; viewModel.setClipboard(file, true) },
                    leadingIcon = { Icon(Icons.Default.ContentCut, null, tint = Color.Gray, modifier = Modifier.size(16.dp)) }
                )
                if (isDir && clipboardFile != null) {
                    DropdownMenuItem(
                        text = { Text("Paste", color = Color.White) },
                        onClick = { showMenu = false; viewModel.pasteFile(file) },
                        leadingIcon = { Icon(Icons.Default.ContentPaste, null, tint = Color.Gray, modifier = Modifier.size(16.dp)) }
                    )
                }
                DropdownMenuItem(
                    text = { Text("Copy Path", color = Color.White) },
                    onClick = { 
                        showMenu = false
                        val clipboardManager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboardManager.setPrimaryClip(android.content.ClipData.newPlainText("Path", file.absolutePath))
                    },
                    leadingIcon = { Icon(Icons.Default.Link, null, tint = Color.Gray, modifier = Modifier.size(16.dp)) }
                )
                HorizontalDivider(color = Color(0xFF30363D))
                DropdownMenuItem(
                    text = { Text("Open Here", color = Color.White) },
                    onClick = {
                        onOpenFile(file)
                        showMenu = false
                    },
                    leadingIcon = { Icon(Icons.Default.OpenInNew, null, tint = Color.Gray, modifier = Modifier.size(18.dp)) }
                )
                DropdownMenuItem(
                    text = { Text("Rename", color = Color.White) },
                    onClick = { showMenu = false; showRenameDialog = true },
                    leadingIcon = { Icon(Icons.Default.Edit, null, tint = Color.Gray, modifier = Modifier.size(16.dp)) }
                )
                DropdownMenuItem(
                    text = { Text("Delete", color = Color.Red) },
                    onClick = { showMenu = false; showDeleteDialog = true },
                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = Color.Red, modifier = Modifier.size(16.dp)) }
                )
            }
        }
    }

    if (isDir && isExpanded) {
        fileTree[file.absolutePath]?.forEach { child ->
            FileTreeNode(child, depth + 1, fileTree, expandedDirs, uiScale, onToggleDir, onOpenFile, viewModel)
        }
    }

    if (showNewFileDialog) {
        NewItemDialog(title = "New File", onConfirm = { viewModel.createFile(file, it); showNewFileDialog = false }, onDismiss = { showNewFileDialog = false })
    }
    if (showNewFolderDialog) {
        NewItemDialog(title = "New Folder", onConfirm = { viewModel.createFolder(file, it); showNewFolderDialog = false }, onDismiss = { showNewFolderDialog = false })
    }
    if (showRenameDialog) {
        NewItemDialog(title = "Rename", initialValue = file.name, onConfirm = { viewModel.renameFile(file, it); showRenameDialog = false }, onDismiss = { showRenameDialog = false })
    }
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete File", color = Color.White) },
            text = { Text("Delete \"${file.name}\"? This cannot be undone.", color = Color.Gray) },
            confirmButton = { TextButton(onClick = { viewModel.deleteFile(file); showDeleteDialog = false }) { Text("Delete", color = Color.Red) } },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel", color = Color(0xFF58A6FF)) } },
            containerColor = Color(0xFF161B22)
        )
    }
}
