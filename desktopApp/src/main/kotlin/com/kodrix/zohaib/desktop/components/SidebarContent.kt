package com.kodrix.zohaib.desktop.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kodrix.zohaib.viewmodel.DesktopIDEViewModel
import com.kodrix.zohaib.viewmodel.FileNode
import com.kodrix.zohaib.viewmodel.SidebarMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SidebarPanel(mode: SidebarMode, viewModel: DesktopIDEViewModel, uiScale: Float = 1f) {
    when (mode) {
        SidebarMode.PROJECTS -> ProjectsContent(viewModel, uiScale)
        SidebarMode.EXPLORER -> ExplorerContent(viewModel, uiScale)
        SidebarMode.GIT -> GitContent(viewModel, uiScale)
        SidebarMode.SEARCH -> SearchContent(viewModel, uiScale)
        SidebarMode.AI -> AiContent(viewModel, uiScale)
        SidebarMode.DEBUG -> DebugContent(viewModel, uiScale)
        SidebarMode.BROWSER -> BrowserContent(uiScale)
        SidebarMode.SETTINGS -> SettingsContent(viewModel, uiScale)
        SidebarMode.EXTENSIONS, SidebarMode.MARKETPLACE -> ExtensionsContent(uiScale)
        SidebarMode.TERMINAL -> { }
    }
}

@Composable
fun ProjectsContent(viewModel: DesktopIDEViewModel, uiScale: Float = 1f) {
    val projects by viewModel.state.projects.collectAsState()
    val activeProject by viewModel.state.activeProject.collectAsState()
    var newName by remember { mutableStateOf("") }
    var showNew by remember { mutableStateOf(false) }
    var cloneUrl by remember { mutableStateOf("") }
    var showClone by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().height(44.dp).background(Color(0xFF0D1117)).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("PROJECTS", color = Color(0xFF58A6FF), fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            // Open folder from file manager
            Icon(Icons.Default.FolderOpen, "Open Folder", tint = Color(0xFF58A6FF), modifier = Modifier.size(18.dp).clickable { showFileChooser { path -> viewModel.openFolder(path) } })
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.Default.Add, "New", tint = if (showNew) Color(0xFF3FB950) else Color(0xFF58A6FF), modifier = Modifier.size(18.dp).clickable { showNew = !showNew; if (showNew) showClone = false })
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.Default.CloudDownload, "Clone", tint = if (showClone) Color(0xFF3FB950) else Color(0xFF58A6FF), modifier = Modifier.size(18.dp).clickable { showClone = !showClone; if (showClone) showNew = false })
        }
        HorizontalDivider(color = Color(0xFF30363D))

        if (showNew) {
            Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(value = newName, onValueChange = { newName = it }, modifier = Modifier.weight(1f).height(32.dp).background(Color(0xFF0D1117)).padding(horizontal = 8.dp, vertical = 6.dp), textStyle = TextStyle(color = Color(0xFFC9D1D9), fontSize = 12.sp), cursorBrush = SolidColor(Color(0xFF58A6FF)), decorationBox = { inner -> Box { if (newName.isEmpty()) Text("Project name...", color = Color(0xFF484F58), fontSize = 12.sp); inner() } })
                Spacer(modifier = Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .height(32.dp)
                        .background(Color(0xFF238636), shape = RoundedCornerShape(4.dp))
                        .clickable { if (newName.isNotBlank()) { viewModel.createProject(newName); newName = ""; showNew = false } }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Create", color = Color.White, fontSize = 11.sp)
                }
            }
        }
        if (showClone) {
            Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(value = cloneUrl, onValueChange = { cloneUrl = it }, modifier = Modifier.weight(1f).height(32.dp).background(Color(0xFF0D1117)).padding(horizontal = 8.dp, vertical = 6.dp), textStyle = TextStyle(color = Color(0xFFC9D1D9), fontSize = 12.sp), cursorBrush = SolidColor(Color(0xFF58A6FF)), decorationBox = { inner -> Box { if (cloneUrl.isEmpty()) Text("Git URL...", color = Color(0xFF484F58), fontSize = 12.sp); inner() } })
                Spacer(modifier = Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .height(32.dp)
                        .background(Color(0xFF238636), shape = RoundedCornerShape(4.dp))
                        .clickable { if (cloneUrl.isNotBlank()) { viewModel.cloneProject(cloneUrl, cloneUrl.substringAfterLast("/").removeSuffix(".git")); cloneUrl = ""; showClone = false } }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Clone", color = Color.White, fontSize = 11.sp)
                }
            }
        }

        val currentDir = remember(activeProject) { viewModel.getWorkingDirectory() }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(projects) { project ->
                val isActive = project == currentDir
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .background(if (isActive) Color(0xFF21262D) else Color.Transparent)
                        .clickable { viewModel.openFolder(project) }
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = null,
                        tint = if (isActive) Color(0xFF58A6FF) else Color(0xFF8B949E),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            java.io.File(project).name,
                            color = if (isActive) Color(0xFF58A6FF) else Color(0xFFC9D1D9),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            project,
                            color = Color(0xFF8B949E),
                            fontSize = 9.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Remove",
                        tint = Color(0xFF8B949E),
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { viewModel.removeRecentProject(project) }
                    )
                }
            }
        }
    }
}

fun showFileChooser(onFolderSelected: (String) -> Unit) {
    java.awt.EventQueue.invokeLater {
        val chooser = javax.swing.JFileChooser().apply {
            fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
            dialogTitle = "Select Project Folder"
            approveButtonText = "Open Folder"
        }
        val result = chooser.showOpenDialog(null)
        if (result == javax.swing.JFileChooser.APPROVE_OPTION) {
            onFolderSelected(chooser.selectedFile.absolutePath)
        }
    }
}

@Composable
fun ExplorerContent(viewModel: DesktopIDEViewModel, uiScale: Float = 1f) {
    val activeProject by viewModel.state.activeProject.collectAsState()
    var treeNodes by remember { mutableStateOf<List<FileNode>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(activeProject) {
        if (activeProject == null) {
            treeNodes = emptyList()
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            val dir = viewModel.getWorkingDirectory()
            val root = java.io.File(dir)
            if (root.exists() && root.isDirectory) {
                treeNodes = viewModel.getDirectoryTree(root)
            }
        }
    }

    val onToggleExpand = { node: FileNode ->
        if (node.isDirectory && !node.isLoaded) {
            scope.launch(Dispatchers.IO) {
                val children = viewModel.getDirectoryTree(java.io.File(node.path))
                withContext(Dispatchers.Main) {
                    treeNodes = viewModel.updateNodeChildren(treeNodes, node.path, children)
                }
            }
        }
    }

    if (activeProject == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0D1117))
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "No Project Open",
                color = Color(0xFF58A6FF),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Open a directory to start working in the IDE.",
                color = Color(0xFF8B949E),
                fontSize = 11.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))

            // 1. Choose Folder Button
            Button(
                onClick = {
                    showFileChooser { path ->
                        viewModel.openFolder(path)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636)),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Select Folder", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("OR", color = Color(0xFF30363D), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))

            // 2. Manual Path Input
            var pathInput by remember { mutableStateOf("") }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = pathInput,
                    onValueChange = { pathInput = it },
                    modifier = Modifier
                        .weight(1f)
                        .height(32.dp)
                        .background(Color(0xFF161B22), shape = RoundedCornerShape(4.dp, 0.dp, 0.dp, 4.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    textStyle = TextStyle(color = Color(0xFFC9D1D9), fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                    cursorBrush = SolidColor(Color(0xFF58A6FF)),
                    decorationBox = { inner ->
                        Box {
                            if (pathInput.isEmpty()) Text("Enter folder path...", color = Color(0xFF484F58), fontSize = 11.sp)
                            inner()
                        }
                    }
                )
                Box(
                    modifier = Modifier
                        .height(32.dp)
                        .background(Color(0xFF21262D), shape = RoundedCornerShape(0.dp, 4.dp, 4.dp, 0.dp))
                        .clickable {
                            if (pathInput.isNotBlank()) {
                                viewModel.openFolder(pathInput)
                            }
                        }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Open", color = Color(0xFF58A6FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 3. Drag and Drop visual zone
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .background(Color(0xFF161B22), shape = RoundedCornerShape(6.dp))
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Drag & Drop folder here",
                    color = Color(0xFF8B949E),
                    fontSize = 11.sp,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                )
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().height(44.dp).background(Color(0xFF0D1117)).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    activeProject?.uppercase() ?: "NO PROJECT",
                    color = Color(0xFF58A6FF),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Default.Add,
                    "New File",
                    tint = Color(0xFF58A6FF),
                    modifier = Modifier.size(18.dp).clickable {
                        viewModel.createFile("new_file.txt")
                        scope.launch(Dispatchers.IO) {
                            treeNodes = viewModel.getDirectoryTree(java.io.File(viewModel.getWorkingDirectory()))
                        }
                    }
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    Icons.Default.Refresh,
                    "Reload",
                    tint = Color(0xFF58A6FF),
                    modifier = Modifier.size(18.dp).clickable {
                        scope.launch(Dispatchers.IO) {
                            treeNodes = viewModel.getDirectoryTree(java.io.File(viewModel.getWorkingDirectory()))
                        }
                    }
                )
            }
            HorizontalDivider(color = Color(0xFF30363D))
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(treeNodes) { node ->
                    FileTreeNode(node, 0, { path -> viewModel.openFile(path) }, onToggleExpand)
                }
            }
        }
    }
}

@Composable
private fun FileTreeNode(
    node: FileNode,
    depth: Int,
    onFileClick: (String) -> Unit,
    onToggleExpand: (FileNode) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 12).dp, end = 4.dp)
            .height(24.dp)
            .clickable {
                if (node.isDirectory) {
                    expanded = !expanded
                    if (expanded) {
                        onToggleExpand(node)
                    }
                } else {
                    onFileClick(node.path)
                }
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (node.isDirectory) {
            Icon(
                if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = Color(0xFF8B949E),
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
        } else {
            Spacer(modifier = Modifier.width(18.dp))
        }

        val icon = when {
            node.isDirectory -> Icons.Default.Folder
            node.name.endsWith(".kt") || node.name.endsWith(".java") || node.name.endsWith(".py") || node.name.endsWith(".js") || node.name.endsWith(".ts") -> Icons.Default.Code
            else -> Icons.Default.Description
        }

        val iconColor = when {
            node.isDirectory -> Color(0xFF58A6FF)
            node.name.endsWith(".kt") || node.name.endsWith(".java") -> Color(0xFFE3B341)
            node.name.endsWith(".py") -> Color(0xFF3572A5)
            node.name.endsWith(".js") || node.name.endsWith(".ts") -> Color(0xFFF1E05A)
            else -> Color(0xFF8B949E)
        }

        Icon(
            icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))

        Text(
            node.name,
            color = if (node.isDirectory) Color(0xFFC9D1D9) else Color(0xFF8B949E),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }

    if (node.isDirectory && expanded) {
        if (!node.isLoaded) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = ((depth + 1) * 12 + 18).dp).height(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.dp, color = Color(0xFF58A6FF))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Loading...", color = Color(0xFF8B949E), fontSize = 10.sp)
            }
        } else {
            node.children.forEach { child ->
                FileTreeNode(child, depth + 1, onFileClick, onToggleExpand)
            }
        }
    }
}

@Composable
fun GitContent(viewModel: DesktopIDEViewModel, uiScale: Float = 1f) {
    val branch by viewModel.state.gitCurrentBranch.collectAsState()
    val branches by viewModel.state.gitBranches.collectAsState()
    val changes by viewModel.state.gitChanges.collectAsState()
    val commits by viewModel.state.gitCommits.collectAsState()
    val gitStatusMessage by viewModel.state.gitStatusMessage.collectAsState()
    val githubUser by viewModel.state.githubUser.collectAsState()

    var commitMsg by remember { mutableStateOf("") }
    var newBranchName by remember { mutableStateOf("") }
    var isBranchListExpanded by remember { mutableStateOf(false) }

    var isChangesExpanded by remember { mutableStateOf(true) }
    var isCommitsExpanded by remember { mutableStateOf(true) }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0D1117))) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp).background(Color(0xFF161B22)).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "SOURCE CONTROL",
                color = Color(0xFFC9D1D9),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
        HorizontalDivider(color = Color(0xFF30363D))

        // ── GitHub Account Card ──────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0D1117))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.AccountCircle,
                contentDescription = null,
                tint = if (githubUser != null) Color(0xFF58A6FF) else Color(0xFF484F58),
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (githubUser != null) "Logged in as $githubUser" else "GitHub Account",
                    color = Color(0xFFC9D1D9),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (githubUser != null) "Automatic Auth Active" else "Not signed in",
                    color = Color(0xFF8B949E),
                    fontSize = 10.sp
                )
            }
            Box(
                modifier = Modifier
                    .height(26.dp)
                    .background(
                        if (githubUser != null) Color(0xFFF85149).copy(alpha = 0.15f) else Color(0xFF238636),
                        shape = RoundedCornerShape(4.dp)
                    )
                    .clickable {
                        if (githubUser != null) viewModel.logoutGithub() else viewModel.loginGithub()
                    }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (githubUser != null) "Sign Out" else "Sign In",
                    color = if (githubUser != null) Color(0xFFF85149) else Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        HorizontalDivider(color = Color(0xFF30363D))

        // Operations status message banner
        if (gitStatusMessage.isNotEmpty()) {
            val isError = gitStatusMessage.startsWith("Failed", ignoreCase = true) || gitStatusMessage.startsWith("Error", ignoreCase = true)
            val isSuccess = gitStatusMessage.contains("success", ignoreCase = true) || gitStatusMessage.contains("done", ignoreCase = true) || gitStatusMessage.startsWith("Staged") || gitStatusMessage.startsWith("Unstaged") || gitStatusMessage.startsWith("Discarded")

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        when {
                            isError -> Color(0xFFF85149).copy(alpha = 0.15f)
                            isSuccess -> Color(0xFF3FB950).copy(alpha = 0.15f)
                            else -> Color(0xFF58A6FF).copy(alpha = 0.15f)
                        }
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(
                                when {
                                    isError -> Color(0xFFF85149)
                                    isSuccess -> Color(0xFF3FB950)
                                    else -> Color(0xFF58A6FF)
                                },
                                shape = CircleShape
                            )
                    )
                    Text(
                        gitStatusMessage,
                        color = when {
                            isError -> Color(0xFFF85149)
                            isSuccess -> Color(0xFF56D364)
                            else -> Color(0xFF58A6FF)
                        },
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            HorizontalDivider(color = Color(0xFF30363D))
        }

        // Branch Section
        Column(modifier = Modifier.padding(12.dp)) {
            // Branch selector row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .background(Color(0xFF21262D), shape = RoundedCornerShape(4.dp))
                    .clickable { isBranchListExpanded = !isBranchListExpanded }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.AccountTree,
                    contentDescription = "Branch",
                    tint = Color(0xFF58A6FF),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    branch.ifEmpty { "no branch" },
                    color = Color(0xFFC9D1D9),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (isBranchListExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Expand",
                    tint = Color(0xFF8B949E),
                    modifier = Modifier.size(18.dp)
                )
            }

            // Branch List Dropdown
            if (isBranchListExpanded) {
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 120.dp)
                        .background(Color(0xFF161B22), shape = RoundedCornerShape(4.dp))
                        .padding(4.dp)
                ) {
                    LazyColumn {
                        items(branches) { b ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.checkoutBranch(b)
                                        isBranchListExpanded = false
                                    }
                                    .padding(vertical = 4.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    b,
                                    color = if (b == branch) Color(0xFF58A6FF) else Color(0xFFC9D1D9),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f)
                                )
                                if (b == branch) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "Active",
                                        tint = Color(0xFF58A6FF),
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Create Branch Input
            Row(
                modifier = Modifier.fillMaxWidth().height(34.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                BasicTextField(
                    value = newBranchName,
                    onValueChange = { newBranchName = it },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(Color(0xFF161B22), shape = RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    textStyle = TextStyle(color = Color(0xFFC9D1D9), fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    cursorBrush = SolidColor(Color(0xFF58A6FF)),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (newBranchName.isEmpty()) Text("New branch...", color = Color(0xFF484F58), fontSize = 11.sp)
                            inner()
                        }
                    }
                )
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(Color(0xFF21262D), shape = RoundedCornerShape(4.dp))
                        .clickable {
                            if (newBranchName.isNotBlank()) {
                                viewModel.createBranch(newBranchName.trim())
                                newBranchName = ""
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Create Branch",
                        tint = Color(0xFFC9D1D9),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Commit message
            BasicTextField(
                value = commitMsg,
                onValueChange = { commitMsg = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(Color(0xFF161B22), shape = RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                textStyle = TextStyle(color = Color(0xFFC9D1D9), fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                cursorBrush = SolidColor(Color(0xFF58A6FF)),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (commitMsg.isEmpty()) Text("Commit message...", color = Color(0xFF484F58), fontSize = 12.sp)
                        inner()
                    }
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .background(Color(0xFF238636), shape = RoundedCornerShape(4.dp))
                    .clickable {
                        if (commitMsg.isNotBlank()) {
                            viewModel.commitChanges(commitMsg)
                            commitMsg = ""
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("Commit", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .background(Color(0xFF21262D), shape = RoundedCornerShape(4.dp))
                        .clickable { viewModel.pushChanges() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("Push", color = Color(0xFFC9D1D9), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .background(Color(0xFF21262D), shape = RoundedCornerShape(4.dp))
                        .clickable { viewModel.pullChanges() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("Pull", color = Color(0xFFC9D1D9), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        HorizontalDivider(color = Color(0xFF30363D))

        // --- Changes Section (Collapsible) ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .background(Color(0xFF161B22))
                .clickable { isChangesExpanded = !isChangesExpanded }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (isChangesExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = Color(0xFF8B949E),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                "CHANGES (${changes.size})",
                color = Color(0xFF8B949E),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        if (isChangesExpanded) {
            val stagedChanges = changes.filter { it.isStaged }
            val unstagedChanges = changes.filter { !it.isStaged }
            val weight = if (isCommitsExpanded) 1f else 2f

            Box(modifier = Modifier.weight(weight).fillMaxWidth()) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (stagedChanges.isNotEmpty()) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().height(24.dp).background(Color(0xFF0D1117).copy(alpha = 0.5f)).padding(horizontal = 24.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "STAGED CHANGES (${stagedChanges.size})",
                                    color = Color(0xFF8B949E),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                        items(stagedChanges) { change ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(28.dp)
                                    .clickable { viewModel.openFile(viewModel.getWorkingDirectory() + "/" + change.path) }
                                    .padding(horizontal = 24.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    change.status,
                                    color = when (change.status) {
                                        "M" -> Color(0xFFE3B341)
                                        "A" -> Color(0xFF3FB950)
                                        "D" -> Color(0xFFF85149)
                                        else -> Color(0xFF8B949E)
                                    },
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(18.dp)
                                )
                                Text(
                                    change.path,
                                    color = Color(0xFFC9D1D9),
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clickable { viewModel.unstageFile(change.path) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("-", color = Color(0xFF8B949E), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    if (unstagedChanges.isNotEmpty()) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().height(24.dp).background(Color(0xFF0D1117).copy(alpha = 0.5f)).padding(horizontal = 24.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "CHANGES (${unstagedChanges.size})",
                                    color = Color(0xFF8B949E),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                        items(unstagedChanges) { change ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(28.dp)
                                    .clickable { viewModel.openFile(viewModel.getWorkingDirectory() + "/" + change.path) }
                                    .padding(horizontal = 24.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    change.status,
                                    color = when (change.status) {
                                        "M" -> Color(0xFFE3B341)
                                        "A" -> Color(0xFF3FB950)
                                        "D" -> Color(0xFFF85149)
                                        else -> Color(0xFF8B949E)
                                    },
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(18.dp)
                                )
                                Text(
                                    change.path,
                                    color = Color(0xFFC9D1D9),
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clickable { viewModel.discardFileChanges(change.path) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.Refresh,
                                            contentDescription = "Discard Changes",
                                            tint = Color(0xFF8B949E),
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clickable { viewModel.stageFile(change.path) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("+", color = Color(0xFF58A6FF), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    if (changes.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("No changes detected", color = Color(0xFF8B949E), fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = Color(0xFF30363D))

        // --- Recent Commits Section (Collapsible) ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .background(Color(0xFF161B22))
                .clickable { isCommitsExpanded = !isCommitsExpanded }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (isCommitsExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = Color(0xFF8B949E),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                "RECENT COMMITS (${commits.size})",
                color = Color(0xFF8B949E),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        if (isCommitsExpanded) {
            val weight = if (isChangesExpanded) 1f else 2f
            Box(modifier = Modifier.weight(weight).fillMaxWidth()) {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    items(commits.take(30)) { c ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(
                                c.hash,
                                color = Color(0xFF58A6FF),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.width(60.dp)
                            )
                            Text(
                                c.message,
                                color = Color(0xFFC9D1D9),
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SearchContent(viewModel: DesktopIDEViewModel, uiScale: Float = 1f) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Pair<String, List<String>>>>(emptyList()) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().height(44.dp).background(Color(0xFF0D1117)).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) { Text("SEARCH", color = Color(0xFF8B949E), fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) }
        HorizontalDivider(color = Color(0xFF30363D))
        BasicTextField(value = query, onValueChange = { query = it; if (it.length >= 2) { scope.launch(Dispatchers.IO) { val dir = java.io.File(viewModel.getWorkingDirectory()); if (dir.exists()) { val f = mutableListOf<Pair<String, List<String>>>(); dir.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.take(500).forEach { file -> try { val l = file.readLines(); val m = l.filter { ll -> ll.contains(query, ignoreCase = true) }; if (m.isNotEmpty()) f.add(file.absolutePath to m.take(3)) } catch (_: Exception) {} }; results = f } } } else results = emptyList() }, modifier = Modifier.fillMaxWidth().height(32.dp).background(Color(0xFF0D1117)).padding(horizontal = 8.dp, vertical = 6.dp), textStyle = TextStyle(color = Color(0xFFC9D1D9), fontFamily = FontFamily.Monospace, fontSize = 12.sp), cursorBrush = SolidColor(Color(0xFF58A6FF)), decorationBox = { inner -> Box { if (query.isEmpty()) Text("Search...", color = Color(0xFF484F58), fontSize = 12.sp); inner() } })
        Text("${results.size} results", color = Color(0xFF8B949E), fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            items(results) { (path, lines) ->
                val short = path.removePrefix(viewModel.getWorkingDirectory()).removePrefix("/")
                Column(modifier = Modifier.fillMaxWidth().clickable { viewModel.openFile(path) }) {
                    Text(short, color = Color(0xFF58A6FF), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    lines.forEach { l -> Text(l.trim().take(80), color = Color(0xFF8B949E), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
fun AiContent(viewModel: DesktopIDEViewModel, uiScale: Float = 1f) {
    val messages by viewModel.state.aiChatMessages.collectAsState()
    val isThinking by viewModel.state.isAiThinking.collectAsState()
    var input by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0D1117))) {
        Row(modifier = Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("AI ASSISTANT", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
        }
        HorizontalDivider(color = Color(0xFF30363D))
        LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            items(messages) { msg ->
                Column(modifier = Modifier.fillMaxWidth().background(if (msg.role == "user") Color(0xFF21262D) else Color(0xFF161B22)).padding(8.dp)) {
                    Text(if (msg.role == "user") "You" else "AI", color = Color(0xFF58A6FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(msg.content, color = Color(0xFFC9D1D9), fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
            if (isThinking) item { Text("Thinking...", color = Color(0xFF8B949E), fontSize = 12.sp) }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            BasicTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f).height(36.dp).background(Color(0xFF161B22)).padding(8.dp), textStyle = TextStyle(color = Color(0xFFC9D1D9), fontSize = 12.sp), cursorBrush = SolidColor(Color(0xFF58A6FF)), decorationBox = { inner -> Box { if (input.isEmpty()) Text("Ask AI...", color = Color(0xFF484F58), fontSize = 12.sp); inner() } })
            Spacer(modifier = Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .height(36.dp)
                    .background(Color(0xFF238636), shape = RoundedCornerShape(4.dp))
                    .clickable { if (input.isNotBlank()) { viewModel.sendAiMessage(input); input = "" } }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Send", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun DebugContent(viewModel: DesktopIDEViewModel, uiScale: Float = 1f) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().height(44.dp).background(Color(0xFF0D1117)).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) { Text("DEBUG CONSOLE", color = Color(0xFF8B949E), fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) }
        HorizontalDivider(color = Color(0xFF30363D))
        Box(modifier = Modifier.fillMaxSize().padding(16.dp)) { Text("Debug console coming soon...", color = Color(0xFF8B949E), fontSize = 12.sp) }
    }
}

@Composable
fun BrowserContent(uiScale: Float = 1f) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().height(44.dp).background(Color(0xFF0D1117)).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) { Text("WEB PREVIEW", color = Color(0xFF8B949E), fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) }
        HorizontalDivider(color = Color(0xFF30363D))
        Box(modifier = Modifier.fillMaxSize().padding(16.dp)) { Text("Browser preview coming soon...", color = Color(0xFF8B949E), fontSize = 12.sp) }
    }
}

@Composable
fun ExtensionsContent(uiScale: Float = 1f) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().height(44.dp).background(Color(0xFF0D1117)).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) { Text("EXTENSIONS", color = Color(0xFF8B949E), fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) }
        HorizontalDivider(color = Color(0xFF30363D))
        Box(modifier = Modifier.fillMaxSize().padding(16.dp)) { Text("Extension marketplace coming soon...", color = Color(0xFF8B949E), fontSize = 12.sp) }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// SETTINGS — matches Android version
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun SettingsContent(viewModel: DesktopIDEViewModel, uiScale: Float = 1f) {
    val nodeVer by viewModel.state.nodeVersion.collectAsState()
    val gitVer by viewModel.state.gitVersion.collectAsState()
    val terminalFontSize by viewModel.state.terminalFontSize.collectAsState()
    val editorFontSize by viewModel.state.editorFontSize.collectAsState()
    val uiScale by viewModel.state.uiScale.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0D1117))) {
        // Header
        Row(modifier = Modifier.fillMaxWidth().background(Color(0xFF0D1117)).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Settings, contentDescription = null, tint = Color(0xFF58A6FF), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("SETTINGS", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp)
        }
        HorizontalDivider(color = Color(0xFF21262D), thickness = 1.dp)

        // Scrollable body
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            // ── Appearance / Font Sizes ──
            SettingsCard("Appearance", Color(0xFF7C6BFF)) {
                SettingsStepperRow("Terminal Font Size", "$terminalFontSize",
                    onMinus = { viewModel.state.terminalFontSize.value = (terminalFontSize - 1).coerceAtLeast(8) },
                    onPlus = { viewModel.state.terminalFontSize.value = (terminalFontSize + 1).coerceAtMost(32) }
                )
                SettingsDivider()
                SettingsStepperRow("Editor Font Size", "$editorFontSize",
                    onMinus = { viewModel.state.editorFontSize.value = (editorFontSize - 1).coerceAtLeast(8) },
                    onPlus = { viewModel.state.editorFontSize.value = (editorFontSize + 1).coerceAtMost(32) }
                )
                SettingsDivider()
                SettingsStepperRow("UI Scale", "${(uiScale * 100).toInt()}%",
                    onMinus = { viewModel.state.uiScale.value = (uiScale - 0.05f).coerceAtLeast(0.5f) },
                    onPlus = { viewModel.state.uiScale.value = (uiScale + 0.05f).coerceAtMost(2.0f) }
                )
            }

            // ── Environment ──
            SettingsCard("Environment", Color(0xFFD29922)) {
                SettingsInfoRow("Platform", com.kodrix.zohaib.platform.Platform.name)
                SettingsDivider()
                SettingsInfoRow("Node.js", nodeVer)
                SettingsDivider()
                SettingsInfoRow("Git", gitVer)
                SettingsDivider()
                SettingsInfoRow("OS", System.getProperty("os.name") ?: "Linux")
                SettingsDivider()
                SettingsInfoRow("Architecture", System.getProperty("os.arch") ?: "unknown")
                SettingsDivider()
                SettingsInfoRow("Java", System.getProperty("java.version") ?: "unknown")
                SettingsDivider()
                SettingsInfoRow("Projects", System.getProperty("user.home") + "/KodrixProjects")
            }

            // ── About ──
            SettingsCard("About", Color(0xFF58A6FF)) {
                SettingsInfoRow("App", "Kodrix IDE v1.2.0")
                SettingsDivider()
                SettingsInfoRow("License", "MIT")
                SettingsDivider()
                SettingsInfoRow("Author", "Zohaib8090")
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, iconTint: Color, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF161B22)).padding(12.dp)) {
        Text(title, color = iconTint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        Spacer(Modifier.height(8.dp))
        Column(content = content)
    }
}

@Composable
private fun SettingsInfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color(0xFF8B949E), fontSize = 11.sp)
        Text(value, color = Color(0xFFC9D1D9), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun SettingsStepperRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color(0xFF8B949E), fontSize = 11.sp, modifier = Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(24.dp).background(Color(0xFF21262D), shape = RoundedCornerShape(4.dp)).clickable { onMinus() },
                contentAlignment = Alignment.Center
            ) { Text("-", color = Color(0xFFC9D1D9), fontSize = 14.sp) }
            Text(value, color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Box(
                modifier = Modifier.size(24.dp).background(Color(0xFF21262D), shape = RoundedCornerShape(4.dp)).clickable { onPlus() },
                contentAlignment = Alignment.Center
            ) { Text("+", color = Color(0xFFC9D1D9), fontSize = 14.sp) }
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(color = Color(0xFF21262D), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))
}
