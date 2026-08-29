package com.kodrix.zohaib.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.zIndex
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kodrix.zohaib.viewmodel.TerminalViewModel
import com.kodrix.zohaib.ai.AgentRole
import coil.compose.AsyncImage
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch

@Composable
fun IDEView(viewModel: TerminalViewModel) {
    val isPanelVisible by viewModel.isPanelVisible.collectAsState()
    val panelHeight by viewModel.panelHeight.collectAsState()
    val uiScale by viewModel.uiScale.collectAsState()
    val sidebarOpen by viewModel.sidebarOpen.collectAsState()
    val sidebarMode by viewModel.sidebarMode.collectAsState()
    val activeProject by viewModel.activeProject.collectAsState()
    var fullScreenImage by remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        val sidebarWidthPx = with(LocalDensity.current) { (280 * uiScale).dp.toPx() }
        val animationProgress by animateFloatAsState(
            targetValue = if (sidebarOpen) 1f else 0f,
            animationSpec = tween(300, easing = FastOutSlowInEasing)
        )

        Row(
            modifier = Modifier.fillMaxSize().padding(start = (48 * uiScale).dp).background(Color(0xFF161B22)), 
            verticalAlignment = Alignment.Top
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                // Sidebar - Always has fixed width but is translated
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width((280 * uiScale).dp)
                        .graphicsLayer {
                            translationX = (animationProgress - 1f) * sidebarWidthPx
                            clip = true
                        }
                ) {
                    ProjectSidebar(viewModel)
                }

                // Editor Area - Pushed by translation, not width
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = animationProgress * sidebarWidthPx
                        }
                ) {
                    val maxHeight = maxHeight
                    val constrainedHeight = panelHeight.coerceAtMost(maxHeight * 0.8f)
                    Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height((32 * uiScale).dp)
                            .background(Color(0xFF161B22)).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = activeProject?.let { "📁 $it" } ?: "Kodrix",
                            color = Color(0xFF8B949E), fontSize = (11 * uiScale).sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        NotificationBellButton(viewModel)
                    }
                    val activeExtensionDetail by viewModel.activeExtensionDetail.collectAsState()
                    val activeGithubExtensionDetail by viewModel.activeGithubExtensionDetail.collectAsState()
                    
                    Box(modifier = Modifier.fillMaxWidth().weight(1f).background(Color(0xFF0D1117))) {
                        if (activeGithubExtensionDetail != null) {
                            GithubExtensionDetailView(activeGithubExtensionDetail!!, viewModel, onImageClick = { fullScreenImage = it })
                        } else if (activeExtensionDetail != null) {
                            ExtensionDetailView(activeExtensionDetail!!, viewModel)
                        } else if (sidebarMode == TerminalViewModel.SidebarMode.BROWSER) {
                            BrowserView(viewModel)
                        } else {
                            val activeTabIndices by viewModel.activeTabIndices.collectAsState()
                            Row(modifier = Modifier.fillMaxSize()) {
                                activeTabIndices.keys.sorted().forEach { viewportId ->
                                    Box(modifier = Modifier.weight(1f).fillMaxHeight().border(1.dp, Color(0xFF30363D))) {
                                        CodeEditor(viewModel, viewportId)
                                    }
                                }
                            }
                        }
                    }
                    if (isPanelVisible) {
                        val density = LocalDensity.current
                        Box(modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .background(Color.Transparent)
                            .draggable(
                                orientation = Orientation.Vertical,
                                state = rememberDraggableState { delta ->
                                    val deltaDp = with(density) { delta.toDp() }
                                    viewModel.updatePanelHeight(-deltaDp)
                                }
                            ),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(Color(0xFF30363D)))
                        }
                        BottomPanel(viewModel, constrainedHeight)
                    }
                }
            }
        }
    }

    // High-priority Activity Bar Overlay
    Box(modifier = Modifier.fillMaxHeight().width((48 * uiScale).dp).background(Color(0xFF161B22)).zIndex(10f)) {
        ActivityBar(viewModel)
    }

        val previewFile by viewModel.previewFile.collectAsState()
        if (previewFile != null) {
            FilePreview(previewFile!!, onDismiss = { viewModel.closePreview() })
        }

        val availableUpdate by viewModel.availableUpdate.collectAsState()
        if (availableUpdate != null) {
            UpdateDialog(availableUpdate!!, onDismiss = { viewModel.dismissUpdate() })
        }
        
        if (fullScreenImage != null) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black).clickable { fullScreenImage = null },
                contentAlignment = Alignment.Center
            ) {
                coil.compose.AsyncImage(
                    model = fullScreenImage,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit
                )
                IconButton(
                    onClick = { fullScreenImage = null },
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).statusBarsPadding()
                ) {
                    Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(32.dp))
                }
            }
        }

        val showThirdPartyDialog by viewModel.showThirdPartyDialog.collectAsState()
        if (showThirdPartyDialog) {
            ThirdPartyServicesDialog(onAccept = { viewModel.acceptThirdPartyServices() })
        }
    }
}

@Composable
fun ThirdPartyServicesDialog(onAccept: () -> Unit) {
    AlertDialog(
        onDismissRequest = {}, // Force acceptance
        title = { 
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Security, null, tint = Color(0xFF58A6FF), modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text("Third-Party Services Disclosure", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Kodrix IDE integrates several third-party services to provide a seamless development experience. By continuing, you acknowledge the use of:",
                    color = Color.LightGray,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(16.dp))
                
                ServiceItem("GitHub OAuth", "Used for repository access, Gists, and account synchronization.")
                ServiceItem("Google Gemini AI", "Powers the intelligent coding assistant and agent orchestrator.")
                ServiceItem("Bore.pub / Tunneling", "Enables local web server previewing and port forwarding.")
                ServiceItem("NPM / JSR Registry", "Used for searching and installing packages and extensions.")
                
                Spacer(Modifier.height(8.dp))
                Text(
                    "No private code is shared without your explicit action (e.g. asking the AI or pushing to Git).",
                    color = Color.Gray,
                    fontSize = 11.sp,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onAccept,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636)),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
            ) {
                Text("I Understand & Accept", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = Color(0xFF161B22),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    )
}

@Composable
fun ServiceItem(name: String, description: String) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(name, color = Color(0xFF58A6FF), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(description, color = Color.Gray, fontSize = 12.sp)
    }
}

@Composable
fun UpdateDialog(update: TerminalViewModel.UpdateInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update Available! 🚀", color = Color.White) },
        text = {
            Column {
                Text("Version ${update.version} is now available on GitHub.", color = Color.Gray, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Text("Release Notes:", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Box(modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                    Text(update.releaseNotes, color = Color.Gray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(update.downloadUrl))
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636))
            ) {
                Text("Update Now", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Later", color = Color.Gray)
            }
        },
        containerColor = Color(0xFF161B22),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
    )
}

@Composable
fun ProjectSidebar(viewModel: TerminalViewModel) {
    val sidebarMode by viewModel.sidebarMode.collectAsState()
    val uiScale by viewModel.uiScale.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width((280 * uiScale).dp)
            .background(Color(0xFF161B22)),
        verticalArrangement = Arrangement.Top
    ) {
        when (sidebarMode) {
            TerminalViewModel.SidebarMode.PROJECTS -> ProjectListContent(viewModel)
            TerminalViewModel.SidebarMode.EXPLORER -> FileExplorerContent(viewModel)
            TerminalViewModel.SidebarMode.GIT -> SourceControlContent(viewModel)
            TerminalViewModel.SidebarMode.SEARCH -> SearchContent(viewModel)
            TerminalViewModel.SidebarMode.EXTENSIONS -> ExtensionsContent(viewModel)
            TerminalViewModel.SidebarMode.MARKETPLACE -> MarketplaceView(viewModel)
            TerminalViewModel.SidebarMode.DEBUG -> DebugContent(viewModel)
            TerminalViewModel.SidebarMode.BROWSER -> Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text("Web Preview Tools\n\nDebugger Bridge Active", color = Color.Gray, fontSize = (11 * uiScale).sp, fontFamily = FontFamily.Monospace)
            }
            TerminalViewModel.SidebarMode.SETTINGS -> SettingsContent(viewModel)
            TerminalViewModel.SidebarMode.AI -> AIChatContent(viewModel)
        }
    }
}

@Composable
fun AIChatContent(viewModel: TerminalViewModel) {
    val uiScale by viewModel.uiScale.collectAsState()
    val messages by viewModel.aiChatMessages.collectAsState()
    val isThinking by viewModel.isAiThinking.collectAsState()
    var text by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val openTabs by viewModel.openTabs.collectAsState()
    val activeIndices by viewModel.activeTabIndices.collectAsState()
    var showAttachmentMenu by remember { mutableStateOf(false) }
    var attachedFileName by remember { mutableStateOf<String?>(null) }
    var attachedFileContent by remember { mutableStateOf<String?>(null) }
    
    val chatSessions by viewModel.chatSessions.collectAsState()
    var showHistoryMenu by remember { mutableStateOf(false) }
    
    var attachedFileUri by remember { mutableStateOf<android.net.Uri?>(null) }
    
    val isAgentMode by viewModel.agentOrchestrator.isAgentMode.collectAsState()
    val orchestratorState by viewModel.agentOrchestrator.orchestratorState.collectAsState()
    val pendingQuestion by viewModel.agentOrchestrator.pendingQuestion.collectAsState()
    
    val context = LocalContext.current
    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                var name = "unknown_file"
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) name = cursor.getString(nameIndex)
                    }
                }
                attachedFileName = name
                val mimeType = context.contentResolver.getType(uri)
                if (mimeType?.startsWith("image/") == true) {
                    attachedFileUri = uri
                    attachedFileContent = null
                } else {
                    attachedFileContent = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    attachedFileUri = null
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Failed to read file", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(messages.size, isThinking) {
        if (messages.isNotEmpty() || isThinking) {
            kotlinx.coroutines.delay(100)
            val displayCount = messages.takeLast(10).size
            val targetIndex = if (isThinking) displayCount else displayCount - 1
            if (targetIndex >= 0) {
                listState.animateScrollToItem(targetIndex)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0D1117)),
        verticalArrangement = Arrangement.Top
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (isAgentMode) "AGENT WORKSPACE" else "AI ASSISTANT", color = Color.White.copy(alpha = 0.7f), fontSize = (12 * uiScale).sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
            Spacer(Modifier.width(8.dp))
            androidx.compose.material3.Switch(
                checked = isAgentMode,
                onCheckedChange = { viewModel.agentOrchestrator.toggleAgentMode(it) },
                modifier = Modifier.scale(0.7f * uiScale),
                colors = androidx.compose.material3.SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF238636),
                    uncheckedThumbColor = Color.Gray,
                    uncheckedTrackColor = Color(0xFF30363D)
                )
            )
            if (isAgentMode) {
                Spacer(Modifier.width(12.dp))
                val isFastMode by viewModel.agentOrchestrator.isFastMode.collectAsState()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("FAST", color = if (isFastMode) Color(0xFFD29922) else Color.Gray, fontSize = (9 * uiScale).sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(4.dp))
                    androidx.compose.material3.Switch(
                        checked = isFastMode,
                        onCheckedChange = { viewModel.agentOrchestrator.toggleFastMode(it) },
                        modifier = Modifier.scale(0.6f * uiScale),
                        colors = androidx.compose.material3.SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFFD29922),
                            uncheckedThumbColor = Color.Gray,
                            uncheckedTrackColor = Color(0xFF30363D)
                        )
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Box {
                IconButton(onClick = { showHistoryMenu = true }, modifier = Modifier.size((24 * uiScale).dp)) {
                    Icon(Icons.Default.History, "History", tint = Color.Gray, modifier = Modifier.size((16 * uiScale).dp))
                }
                DropdownMenu(
                    expanded = showHistoryMenu,
                    onDismissRequest = { showHistoryMenu = false },
                    modifier = Modifier.background(Color(0xFF161B22)).widthIn(max = 250.dp)
                ) {
                    if (chatSessions.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No history", color = Color.Gray) },
                            onClick = { showHistoryMenu = false }
                        )
                    } else {
                        chatSessions.sortedByDescending { it.timestamp }.forEach { session ->
                            DropdownMenuItem(
                                text = { 
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(session.title, color = Color.White, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, fontSize = (12 * uiScale).sp)
                                            Text(java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(session.timestamp)), color = Color.Gray, fontSize = (9 * uiScale).sp)
                                        }
                                        IconButton(
                                            onClick = { viewModel.deleteChatSession(session.id) },
                                            modifier = Modifier.size((24 * uiScale).dp)
                                        ) {
                                            Icon(Icons.Default.Delete, "Delete", tint = Color(0xFFF85149).copy(alpha = 0.7f), modifier = Modifier.size((14 * uiScale).dp))
                                        }
                                    }
                                },
                                onClick = {
                                    viewModel.switchSession(session.id)
                                    showHistoryMenu = false
                                }
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { if (isAgentMode) viewModel.agentOrchestrator.stopLoop() else viewModel.clearAiChat() }, modifier = Modifier.size((28 * uiScale).dp)) {
                Icon(if (isAgentMode) Icons.Default.AddCircleOutline else Icons.Default.Refresh, "New", tint = Color.Gray, modifier = Modifier.size((18 * uiScale).dp))
            }
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.AutoAwesome, null, tint = Color(0xFF58A6FF), modifier = Modifier.size((18 * uiScale).dp))
        }
        
        HorizontalDivider(color = Color(0xFF30363D).copy(alpha = 0.5f))
        Spacer(Modifier.height(8.dp))

        if (isAgentMode) {
            val agentWorkspaces by viewModel.agentOrchestrator.workspaces.collectAsState()
            val activeRole by viewModel.agentOrchestrator.activeRole.collectAsState()

            // Poll the agent server's health + wire the tool context for the
            // current project. The tool context is what the agent uses when
            // it calls read_file / write_file / run_shell etc.
            val context = LocalContext.current
            val viewModelRef = viewModel
            val currentProject: String? = viewModelRef.activeProject.value
            androidx.compose.runtime.LaunchedEffect(Unit) {
                while (true) {
                    viewModelRef.agentServerHealthy.value = try {
                        val conn = java.net.URL("http://127.0.0.1:3080/healthz").openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 1_000
                        conn.readTimeout = 1_000
                        val ok = conn.responseCode == 200
                        conn.disconnect()
                        ok
                    } catch (_: Throwable) { false }
                    kotlinx.coroutines.delay(2_000)
                }
            }
            androidx.compose.runtime.LaunchedEffect(currentProject) {
                val proj: String? = currentProject
                val ctx = if (proj != null) {
                    com.kodrix.zohaib.agent.AndroidToolContext(
                        appContext = context,
                        projectRootPath = java.io.File(viewModelRef.projectsRoot, proj).absolutePath,
                    )
                } else null
                if (ctx != null) viewModelRef.autoAgent.setToolContext(ctx)
            }

            // Tab Row for Agents
            ScrollableTabRow(
                selectedTabIndex = activeRole.ordinal,
                containerColor = Color(0xFF161B22),
                contentColor = Color(0xFF58A6FF),
                edgePadding = 0.dp,
                divider = {},
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[activeRole.ordinal]),
                        color = Color(0xFF58A6FF)
                    )
                },
                modifier = Modifier.fillMaxWidth().height((36 * uiScale).dp)
            ) {
                AgentRole.values().forEach { role ->
                    Tab(
                        selected = activeRole == role,
                        onClick = { viewModel.agentOrchestrator.setActiveRole(role) },
                        text = {
                            Text(
                                role.name.take(3),
                                fontSize = (10 * uiScale).sp,
                                fontWeight = if (activeRole == role) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            // The new Hermes-style agent panel replaces the old multi-agent
            // markdown workspace. It talks to the same spawned agent server
            // and exposes: chat + auto-run + tool approval + status.
            com.kodrix.zohaib.ui.AgentPanel(
                agent = viewModel.autoAgent,
                serverHealthy = viewModel.agentServerHealthy.value,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        } else {
            LazyColumn(
                state = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val displayMessages = messages.takeLast(10)
            items(displayMessages) { msg ->
                val isUser = msg.role == "user"
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (isUser) Color(0xFF238636).copy(alpha = 0.2f) else Color.Transparent,
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                            )
                            .padding(if (isUser) 12.dp else 0.dp)
                    ) {
                        Column {
                            SelectionContainer {
                                if (isUser) {
                                    Text(
                                        text = msg.content,
                                        color = Color(0xFF3FB950),
                                        fontSize = (13 * uiScale).sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                } else {
                                    MarkdownText(
                                        text = msg.content,
                                        color = Color(0xFFCDD9E5),
                                        fontSize = (13 * uiScale).sp
                                    )
                                }
                            }
                            
                            val clipboardManager = LocalClipboardManager.current
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                            ) {
                                IconButton(
                                    onClick = { clipboardManager.setText(AnnotatedString(msg.content)) },
                                    modifier = Modifier.size((24 * uiScale).dp).padding(top = 4.dp)
                                ) {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        contentDescription = "Copy",
                                        tint = Color.Gray.copy(alpha = 0.5f),
                                        modifier = Modifier.size((14 * uiScale).dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (isThinking) {
                item {
                    Text("AI is thinking...", color = Color.Gray, fontSize = (11 * uiScale).sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                }
            }
        }

        Box(modifier = Modifier.padding(16.dp)) {
            Column {
                if (attachedFileName != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(bottom = 8.dp)
                            .background(Color(0xFF161B22), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.AttachFile, null, tint = Color.Gray, modifier = Modifier.size((14 * uiScale).dp))
                        Spacer(Modifier.width(4.dp))
                        Text(attachedFileName!!, color = Color.LightGray, fontSize = (11 * uiScale).sp)
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Default.Close, 
                            null, 
                            tint = Color.Gray, 
                            modifier = Modifier.size((14 * uiScale).dp).clickable { 
                                attachedFileName = null
                                attachedFileContent = null
                                attachedFileUri = null
                            }
                        )
                    }
                }
                
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Ask AI something...", color = Color.Gray, fontSize = (13 * uiScale).sp) },
                    textStyle = TextStyle(color = Color.White, fontSize = (14 * uiScale).sp),
                    maxLines = 4,
                    leadingIcon = {
                        Box {
                            IconButton(onClick = { showAttachmentMenu = true }) {
                                Icon(Icons.Default.AttachFile, null, tint = Color.Gray)
                            }
                            DropdownMenu(
                                expanded = showAttachmentMenu,
                                onDismissRequest = { showAttachmentMenu = false },
                                modifier = Modifier.background(Color(0xFF161B22))
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Attach Active File", color = Color.White) },
                                    onClick = {
                                        val activeTab = openTabs.getOrNull(activeIndices[0] ?: -1)
                                        if (activeTab != null) {
                                            attachedFileName = activeTab.file.name
                                            attachedFileContent = try { activeTab.file.readText() } catch (e: Exception) { null }
                                            attachedFileUri = null
                                        }
                                        showAttachmentMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Browse Files...", color = Color.White) },
                                    onClick = {
                                        filePickerLauncher.launch("*/*")
                                        showAttachmentMenu = false
                                    }
                                )
                            }
                        }
                    },
                    trailingIcon = {
                        IconButton(onClick = {
                            if (text.isNotBlank()) {
                                viewModel.sendAiMessage(text, attachedFileName, attachedFileContent, attachedFileUri)
                                text = ""
                                attachedFileName = null
                                attachedFileContent = null
                                attachedFileUri = null
                            }
                        }) {
                            Icon(Icons.Default.Send, null, tint = Color(0xFF58A6FF))
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF58A6FF),
                        unfocusedBorderColor = Color(0xFF30363D)
                    )
                )
            }
        }
        }
    }
}

@Composable
fun DebugContent(viewModel: TerminalViewModel) {
    val uiScale by viewModel.uiScale.collectAsState()
    
    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0D1117))) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("RUN AND DEBUG", color = Color.White.copy(alpha = 0.7f), fontSize = (12 * uiScale).sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = { viewModel.startDebugProcess() },
                modifier = Modifier.size((24 * uiScale).dp)
            ) {
                Icon(Icons.Default.PlayArrow, "Run and Debug", tint = Color(0xFF238636), modifier = Modifier.size((18 * uiScale).dp))
            }
        }

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            DebugSection("VARIABLES", uiScale) {
                Box(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("No variables available", color = Color.Gray, fontSize = (11 * uiScale).sp)
                }
            }
            DebugSection("WATCH", uiScale) {
                Box(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("No expressions watched", color = Color.Gray, fontSize = (11 * uiScale).sp)
                }
            }
            DebugSection("CALL STACK", uiScale) {
                Box(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Not paused on debugger", color = Color.Gray, fontSize = (11 * uiScale).sp)
                }
            }
            DebugSection("BREAKPOINTS", uiScale) {
                Box(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("No breakpoints set", color = Color.Gray, fontSize = (11 * uiScale).sp)
                }
            }
        }
    }
}

@Composable
fun DebugSection(title: String, uiScale: Float, content: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(true) }
    Column(modifier = Modifier.animateContentSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.background(Color(0xFF21262D).copy(alpha = 0.5f)).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight, null, tint = Color.Gray, modifier = Modifier.size((16 * uiScale).dp))
            Spacer(Modifier.width(4.dp))
            Text(title, color = Color.White, fontSize = (11 * uiScale).sp, fontWeight = FontWeight.Bold)
        }
        if (expanded) content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsContent(viewModel: TerminalViewModel) {
    val uiScale by viewModel.uiScale.collectAsState()
    val marketplaceExtensions by viewModel.marketplaceExtensions.collectAsState()
    val npmResults by viewModel.npmResults.collectAsState()
    val isSearching by viewModel.isSearchingExtensions.collectAsState()
    val isNpmSearching by viewModel.isNpmSearching.collectAsState()
    val marketMode by viewModel.marketMode.collectAsState()
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(searchQuery, marketMode) {
        if (searchQuery.length >= 2) {
            if (marketMode == TerminalViewModel.MarketMode.EXTENSIONS) {
                viewModel.searchExtensions(searchQuery)
            } else {
                viewModel.searchNpm(searchQuery)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "MARKETPLACE",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = (12 * uiScale).sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { viewModel.setMarketMode(TerminalViewModel.MarketMode.EXTENSIONS) }, modifier = Modifier.size((24 * uiScale).dp)) {
                Icon(Icons.Default.Extension, null, tint = if (marketMode == TerminalViewModel.MarketMode.EXTENSIONS) Color(0xFF58A6FF) else Color.Gray)
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { viewModel.setMarketMode(TerminalViewModel.MarketMode.PACKAGES) }, modifier = Modifier.size((24 * uiScale).dp)) {
                Icon(Icons.Default.Layers, null, tint = if (marketMode == TerminalViewModel.MarketMode.PACKAGES) Color(0xFF58A6FF) else Color.Gray)
            }
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { 
                Text(
                    if (marketMode == TerminalViewModel.MarketMode.EXTENSIONS) "Search Extensions..." else "Search NPM Packages...", 
                    fontSize = (13 * uiScale).sp, 
                    color = Color.Gray
                ) 
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .heightIn(min = (52 * uiScale).dp),
            textStyle = androidx.compose.ui.text.TextStyle(
                color = Color.White, 
                fontSize = (15 * uiScale).sp,
                fontFamily = FontFamily.SansSerif
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF58A6FF),
                unfocusedBorderColor = Color(0xFF30363D),
                focusedContainerColor = Color(0xFF161B22),
                unfocusedContainerColor = Color(0xFF161B22),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = Color(0xFF58A6FF)
            ),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            leadingIcon = { Icon(Icons.Default.Search, null, tint = Color.Gray, modifier = Modifier.size((20 * uiScale).dp)) },
            singleLine = true
        )

        Spacer(modifier = Modifier.height(16.dp))

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                if (searchQuery.isBlank()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text(
                                "Type to search for extensions...",
                                color = Color.Gray,
                                fontSize = (13 * uiScale).sp
                            )
                        }
                    }
                } else {
                    if (marketMode == TerminalViewModel.MarketMode.EXTENSIONS) {
                        if (marketplaceExtensions.isEmpty() && !isSearching) {
                            item {
                                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                    Text("No extensions found for \"$searchQuery\"", color = Color.Gray, fontSize = (13 * uiScale).sp)
                                }
                            }
                        } else {
                            item { ExtensionSection("OPEN VSX MARKETPLACE", uiScale) { /* Header */ } }
                            items(marketplaceExtensions) { ext ->
                                ExtensionItem(
                                    name = ext.displayName,
                                    author = ext.publisher,
                                    desc = ext.description,
                                    version = ext.version,
                                    iconUrl = ext.iconUrl,
                                    downloads = ext.downloadCount,
                                    uiScale = uiScale,
                                    isEnabled = viewModel.isExtensionEnabled("${ext.namespace}.${ext.name}"),
                                    onToggle = { 
                                        val id = "${ext.namespace}.${ext.name}"
                                        viewModel.toggleExtensionEnabled(id, !viewModel.isExtensionEnabled(id))
                                    },
                                    onDetailClick = { viewModel.setActiveExtensionDetail(ext) }
                                )
                            }
                        }
                    } else {
                        if (npmResults.isEmpty() && !isNpmSearching) {
                            item {
                                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                    Text("No NPM packages found for \"$searchQuery\"", color = Color.Gray, fontSize = (13 * uiScale).sp)
                                }
                            }
                        } else {
                            item { ExtensionSection("NPM REGISTRY", uiScale) { /* Header */ } }
                            items(npmResults) { pkg ->
                                NpmPackageItem(pkg, uiScale) {
                                    viewModel.installNpmPackage(pkg.name)
                                }
                            }
                        }
                    }
                }
            }
            if (isSearching || isNpmSearching) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = Color(0xFF58A6FF),
                    trackColor = Color.Transparent
                )
            }
        }
    }
}

@Composable
fun ExtensionSection(title: String, uiScale: Float, content: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(true) }
    
    Column(modifier = Modifier.animateContentSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .background(Color(0xFF21262D).copy(alpha = 0.5f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                null,
                tint = Color.Gray,
                modifier = Modifier.size((16 * uiScale).dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                title, 
                color = Color.White, 
                fontSize = (11 * uiScale).sp, 
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        }
        if (expanded) {
            content()
        }
    }
}

@Composable
fun ExtensionItem(
    name: String, 
    author: String, 
    desc: String, 
    version: String, 
    iconUrl: String?, 
    downloads: Int,
    uiScale: Float,
    isEnabled: Boolean = true,
    onToggle: () -> Unit = {},
    onDetailClick: () -> Unit = {}
) {
    var isHovered by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onDetailClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size((48 * uiScale).dp)
                .background(Color(0xFF161B22), shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                .border(1.dp, Color(0xFF30363D), shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                .padding(6.dp),
            contentAlignment = Alignment.Center
        ) {
            if (iconUrl != null) {
                AsyncImage(
                    model = iconUrl,
                    contentDescription = name,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Default.Extension, null, tint = Color(0xFF58A6FF), modifier = Modifier.size((28 * uiScale).dp))
            }
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            name, 
                            color = Color(0xFF58A6FF), 
                            fontSize = (14 * uiScale).sp, 
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        if (!isEnabled) {
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .background(Color(0xFFF85149).copy(alpha = 0.2f), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Text("DISABLED", color = Color(0xFFF85149), fontSize = (8 * uiScale).sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(author, color = Color.Gray, fontSize = (11 * uiScale).sp)
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Default.CloudDownload, null, tint = Color.Gray.copy(alpha = 0.5f), modifier = Modifier.size((12 * uiScale).dp))
                        Spacer(Modifier.width(2.dp))
                        Text(formatDownloads(downloads), color = Color.Gray.copy(alpha = 0.5f), fontSize = (10 * uiScale).sp)
                    }
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onToggle,
                        modifier = Modifier.size((28 * uiScale).dp)
                    ) {
                        Icon(
                            if (isEnabled) Icons.Default.CheckCircle else Icons.Default.Block,
                            null,
                            tint = if (isEnabled) Color(0xFF3FB950) else Color(0xFFF85149),
                            modifier = Modifier.size((18 * uiScale).dp)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = { /* Install */ },
                        modifier = Modifier.height((28 * uiScale).dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF238636),
                            contentColor = Color.White
                        ),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                    ) {
                        Text("Install", fontSize = (11 * uiScale).sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            
            Spacer(Modifier.height(4.dp))
            
            Text(
                desc, 
                color = Color.Gray, 
                fontSize = (12 * uiScale).sp, 
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                lineHeight = (16 * uiScale).sp
            )
        }
    }
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = Color(0xFF30363D).copy(alpha = 0.5f))
}

@Composable
fun ExtensionDetailView(extension: TerminalViewModel.MarketplaceExtension, viewModel: TerminalViewModel) {
    val uiScale by viewModel.uiScale.collectAsState()
    var selectedVersion by remember(extension.name) { mutableStateOf(extension.version) }
    var showVersionPicker by remember { mutableStateOf(false) }
    
    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0D1117)).verticalScroll(rememberScrollState())) {
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF161B22)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Extension Details", color = Color.Gray, fontSize = (12 * uiScale).sp)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { viewModel.setActiveExtensionDetail(null) }, modifier = Modifier.size((24 * uiScale).dp)) {
                Icon(Icons.Default.Close, null, tint = Color.Gray)
            }
        }
        
        Row(modifier = Modifier.padding(24.dp)) {
            Box(
                modifier = Modifier
                    .size((128 * uiScale).dp)
                    .background(Color(0xFF161B22), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFF30363D), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                if (extension.iconUrl != null) {
                    AsyncImage(model = extension.iconUrl, contentDescription = null, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(Icons.Default.Extension, null, tint = Color(0xFF58A6FF), modifier = Modifier.size((64 * uiScale).dp))
                }
            }
            
            Spacer(Modifier.width(24.dp))
            
            Column {
                Text(extension.displayName, color = Color.White, fontSize = (28 * uiScale).sp, fontWeight = FontWeight.Bold)
                Text(extension.publisher, color = Color(0xFF58A6FF), fontSize = (16 * uiScale).sp)
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { /* Install logic with selectedVersion */ },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636)),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                    ) {
                        Text("Install", fontWeight = FontWeight.Bold)
                    }
                    
                    Spacer(Modifier.width(12.dp))
                    
                    val isDisabled = !viewModel.isExtensionEnabled("${extension.namespace}.${extension.name}")
                    OutlinedButton(
                        onClick = { viewModel.toggleExtensionEnabled("${extension.namespace}.${extension.name}", isDisabled) },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                        modifier = Modifier.height((36 * uiScale).dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (isDisabled) Color(0xFF238636) else Color(0xFFF85149)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (isDisabled) Color(0xFF238636) else Color(0xFFF85149))
                    ) {
                        Text(if (isDisabled) "Enable" else "Disable", fontSize = (12 * uiScale).sp)
                    }

                    Spacer(Modifier.width(12.dp))
                    
                    Box {
                        OutlinedButton(
                            onClick = { showVersionPicker = true },
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF30363D))
                        ) {
                            Text(selectedVersion, fontSize = (12 * uiScale).sp)
                            Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size((16 * uiScale).dp))
                        }
                        
                        DropdownMenu(
                            expanded = showVersionPicker,
                            onDismissRequest = { showVersionPicker = false },
                            modifier = Modifier.background(Color(0xFF161B22)).border(1.dp, Color(0xFF30363D))
                        ) {
                            val versions = extension.versions.ifEmpty { listOf(extension.version) }
                            versions.forEach { version ->
                                DropdownMenuItem(
                                    text = { Text(version, color = Color.White) },
                                    onClick = {
                                        selectedVersion = version
                                        showVersionPicker = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
        
        HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp), color = Color(0xFF30363D))
        
        Column(modifier = Modifier.padding(24.dp)) {
            Text("Description", color = Color.White, fontSize = (18 * uiScale).sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(
                extension.description,
                color = Color.Gray,
                fontSize = (14 * uiScale).sp,
                lineHeight = (20 * uiScale).sp
            )
            
            Spacer(Modifier.height(32.dp))
            Text("Information", color = Color.White, fontSize = (18 * uiScale).sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            InfoRow("Publisher", extension.publisher, uiScale)
            InfoRow("Downloads", formatDownloads(extension.downloadCount), uiScale)
            InfoRow("Identifier", "${extension.namespace}.${extension.name}", uiScale)
        }
    }
}

@Composable
fun InfoRow(label: String, value: String, uiScale: Float) {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, color = Color.Gray, modifier = Modifier.width(100.dp), fontSize = (13 * uiScale).sp)
        Text(value, color = Color.White, fontSize = (13 * uiScale).sp)
    }
}

fun formatDownloads(count: Int): String {
    return when {
        count >= 1_000_000 -> "${String.format("%.1f", count / 1_000_000f)}M"
        count >= 1_000 -> "${count / 1_000}K"
        else -> count.toString()
    }
}

@Composable
fun ProjectListContent(viewModel: TerminalViewModel) {
    val projects by viewModel.projects.collectAsState()
    val activeProject by viewModel.activeProject.collectAsState()
    val uiScale by viewModel.uiScale.collectAsState()
    var showNewProjectDialog by remember { mutableStateOf(false) }
    var projectToDelete by remember { mutableStateOf<String?>(null) }
    var pendingExportProject by remember { mutableStateOf<String?>(null) }

    val importAction = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: android.net.Uri? ->
        uri?.let { viewModel.importProjectZip(it) }
    }
    val exportAction = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: android.net.Uri? ->
        uri?.let { pendingExportProject?.let { name -> viewModel.exportProjectZip(name, it) } }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().height((44 * uiScale).dp)
                .background(Color(0xFF0D1117)).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("PROJECTS", color = Color(0xFF58A6FF), fontSize = (11 * uiScale).sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f))
            IconButton(onClick = { showNewProjectDialog = true }, modifier = Modifier.size((32 * uiScale).dp)) {
                Icon(Icons.Default.Add, null, tint = Color(0xFF58A6FF), modifier = Modifier.size((18 * uiScale).dp))
            }
            var showCloneDialog by remember { mutableStateOf(false) }
            IconButton(onClick = { showCloneDialog = true }, modifier = Modifier.size((32 * uiScale).dp)) {
                Icon(Icons.Default.CloudDownload, "Clone Git", tint = Color(0xFF58A6FF), modifier = Modifier.size((18 * uiScale).dp))
            }
            if (showCloneDialog) {
                CloneProjectDialog(
                    onConfirm = { url, name, user, token -> 
                        viewModel.cloneProject(url, name, user, token)
                        showCloneDialog = false 
                    },
                    onDismiss = { showCloneDialog = false }
                )
            }
            IconButton(onClick = { importAction.launch("application/zip") }, modifier = Modifier.size((32 * uiScale).dp)) {
                Icon(Icons.Default.FileUpload, "Import ZIP", tint = Color(0xFF58A6FF), modifier = Modifier.size((18 * uiScale).dp))
            }
            IconButton(onClick = { viewModel.toggleSidebar() }, modifier = Modifier.size((32 * uiScale).dp)) {
                Icon(Icons.Default.Close, null, tint = Color.Gray, modifier = Modifier.size((16 * uiScale).dp))
            }
        }
        HorizontalDivider(color = Color(0xFF30363D))

        val cloneProgress by viewModel.cloneProgress.collectAsState()
        val setupStatus by viewModel.setupStatus.collectAsState()

        if (cloneProgress > 0 || setupStatus.contains("Cloning") || setupStatus.contains("Preparing") || setupStatus.contains("Receiving") || setupStatus.contains("Resolving")) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(setupStatus, color = Color.Gray, fontSize = (11 * uiScale).sp)
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { if (cloneProgress > 0) cloneProgress / 100f else 0f },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = Color(0xFF58A6FF),
                    trackColor = Color(0xFF30363D)
                )
            }
            HorizontalDivider(color = Color(0xFF30363D))
        }

        if (projects.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("No projects yet.\nTap ＋ to create one.", color = Color.Gray,
                    fontSize = (11 * uiScale).sp, fontFamily = FontFamily.Monospace)
            }
        }

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            projects.forEach { name ->
                val isActive = name == activeProject
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(if (isActive) Color(0xFF21262D) else Color.Transparent)
                        .clickable { viewModel.openProject(name) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isActive) Box(Modifier.width(3.dp).height((16 * uiScale).dp).background(Color(0xFF58A6FF)))
                    Spacer(Modifier.width(if (isActive) 6.dp else 9.dp))
                    Text("📁 $name", color = if (isActive) Color.White else Color(0xFF8B949E),
                        fontSize = (12 * uiScale).sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { 
                        pendingExportProject = name
                        exportAction.launch("$name.zip") 
                    }, modifier = Modifier.size((24 * uiScale).dp)) {
                        Icon(Icons.Default.FileDownload, "Export ZIP", tint = Color(0xFF444C56), modifier = Modifier.size((14 * uiScale).dp))
                    }
                    IconButton(onClick = { projectToDelete = name }, modifier = Modifier.size((24 * uiScale).dp)) {
                        Icon(Icons.Default.Delete, null, tint = Color(0xFF444C56), modifier = Modifier.size((14 * uiScale).dp))
                    }
                }
            }
        }

        HorizontalDivider(color = Color(0xFF30363D))
        Row(modifier = Modifier.fillMaxWidth().clickable { viewModel.refreshProjects() }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Refresh, null, tint = Color.Gray, modifier = Modifier.size((14 * uiScale).dp))
            Spacer(Modifier.width(6.dp))
            Text("Refresh List", color = Color.Gray, fontSize = (11 * uiScale).sp)
        }
    }

    if (showNewProjectDialog) {
        NewProjectDialog(onConfirm = { name -> viewModel.createProject(name); showNewProjectDialog = false },
            onDismiss = { showNewProjectDialog = false })
    }

    projectToDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { projectToDelete = null },
            title = { Text("Delete Project", color = Color.White) },
            text = { Text("Delete \"$name\" and all its files? This cannot be undone.", color = Color.Gray) },
            confirmButton = { TextButton(onClick = { viewModel.deleteProject(name); projectToDelete = null }) { Text("Delete", color = Color.Red) } },
            dismissButton = { TextButton(onClick = { projectToDelete = null }) { Text("Cancel", color = Color(0xFF58A6FF)) } },
            containerColor = Color(0xFF161B22)
        )
    }
}

@Composable
fun NewProjectDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161B22),
        title = { Text("New Project", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("Enter a project name:", color = Color.Gray, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                BasicTextField(
                    value = name, onValueChange = { name = it },
                    textStyle = TextStyle(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF0D1117)).padding(10.dp),
                    decorationBox = { inner ->
                        if (name.isEmpty()) Text("my-project", color = Color.DarkGray, fontFamily = FontFamily.Monospace)
                        inner()
                    }
                )
            }
        },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onConfirm(name) }) { Text("Create", color = Color(0xFF58A6FF)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color.Gray) } }
    )
}

@Composable
fun BottomPanel(viewModel: TerminalViewModel, height: Dp) {
    var selectedTab by remember { mutableStateOf("TERMINAL") }
    val tabs = listOf("PROBLEMS", "OUTPUT", "DEBUG CONSOLE", "TERMINAL", "PORTS")
    val uiScale by viewModel.uiScale.collectAsState()

    Surface(modifier = Modifier.fillMaxWidth().height(height), color = Color.Black) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth().height((35 * uiScale).dp).background(Color(0xFF161B22)), verticalAlignment = Alignment.CenterVertically) {
                Row(modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(start = 8.dp)) {
                    tabs.forEach { tab ->
                        val isSelected = selectedTab == tab
                        Box(modifier = Modifier.width((85 * uiScale).dp).fillMaxHeight().clickable { selectedTab = tab }, contentAlignment = Alignment.Center) {
                            Text(text = tab, color = if (isSelected) Color.White else Color.Gray, fontSize = (10 * uiScale).sp, fontWeight = FontWeight.Normal)
                            if (isSelected) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp).background(Color(0xFF58A6FF)))
                        }
                    }
                }
                IconButton(onClick = { viewModel.togglePanel(false) }, modifier = Modifier.size((35 * uiScale).dp)) {
                    Icon(Icons.Default.Close, null, tint = Color.Gray, modifier = Modifier.size((16 * uiScale).dp))
                }
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
                when (selectedTab) {
                    "TERMINAL" -> TerminalTabContent(viewModel)
                    "PROBLEMS" -> ProblemsView(viewModel)
                    "OUTPUT" -> OutputView(viewModel)
                    "DEBUG CONSOLE" -> DebugConsoleView(viewModel)
                    "PORTS" -> PortsTabContent(viewModel)
                    else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(selectedTab, color = Color.DarkGray) }
                }
            }
        }
    }
}

@Composable
fun DebugConsoleView(viewModel: TerminalViewModel) {
    val logcatLines by viewModel.logcatOutput.collectAsState()
    val filter by viewModel.logcatFilter.collectAsState()
    val uiScale by viewModel.uiScale.collectAsState()
    val scrollState = rememberScrollState()
    
    val filteredLines = remember(logcatLines, filter) {
        if (filter.isBlank()) logcatLines
        else logcatLines.filter { it.contains(filter, ignoreCase = true) }
    }

    LaunchedEffect(filteredLines.size) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0D1117))) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = filter,
                onValueChange = { viewModel.updateLogcatFilter(it) },
                modifier = Modifier.weight(1f).background(Color(0xFF161B22), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .border(1.dp, Color(0xFF30363D), androidx.compose.foundation.shape.RoundedCornerShape(4.dp)).padding(8.dp),
                textStyle = TextStyle(color = Color.White, fontSize = (11 * uiScale).sp, fontFamily = FontFamily.Monospace),
                cursorBrush = SolidColor(Color(0xFF58A6FF)),
                decorationBox = { innerTextField ->
                    if (filter.isEmpty()) Text("Filter (e.g. text, !exclude)", color = Color.Gray, fontSize = (11 * uiScale).sp)
                    innerTextField()
                }
            )
            Spacer(Modifier.width(8.dp))
            val isPaused by viewModel.isLogsPaused.collectAsState()
            IconButton(
                onClick = { viewModel.toggleLogsPause() },
                modifier = Modifier.size((32 * uiScale).dp).background(if (isPaused) Color(0xFFD29922).copy(alpha = 0.1f) else Color.Transparent, androidx.compose.foundation.shape.CircleShape)
            ) {
                Icon(if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause, "Toggle Logs", tint = if (isPaused) Color(0xFFD29922) else Color.Gray, modifier = Modifier.size((18 * uiScale).dp))
            }
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = { viewModel.stopDebugProcess() },
                modifier = Modifier.size((32 * uiScale).dp).background(Color(0xFFF85149).copy(alpha = 0.1f), androidx.compose.foundation.shape.CircleShape)
            ) {
                Icon(Icons.Default.Stop, "Stop Process", tint = Color(0xFFF85149), modifier = Modifier.size((18 * uiScale).dp))
            }
        }
        HorizontalDivider(color = Color(0xFF30363D))

        Column(modifier = Modifier.weight(1f).padding(8.dp).verticalScroll(scrollState)) {
            filteredLines.forEach { line ->
                val color = when {
                    line.contains(" E ") || line.contains("Error") -> Color(0xFFF85149)
                    line.contains(" W ") || line.contains("Warn") -> Color(0xFFD29922)
                    line.contains(" I ") -> Color(0xFF58A6FF)
                    else -> Color.Gray
                }
                Text(
                    text = line,
                    color = color,
                    fontSize = (10 * uiScale).sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 1.dp)
                )
            }
        }
    }
}

@Composable
fun SplashScreen(viewModel: TerminalViewModel) {
    val status by viewModel.setupStatus.collectAsState()
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0D1117)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Kodrix", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator(color = Color(0xFF58A6FF))
            Spacer(Modifier.height(16.dp))
            Text(status, color = Color.Gray, fontSize = 14.sp)
        }
    }
}

@Composable
fun FilePreview(file: java.io.File, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        confirmButton = {},
        containerColor = Color(0xFF0D1117),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(file.name, color = Color.White, modifier = Modifier.weight(1f), fontSize = 16.sp)
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null, tint = Color.Gray) }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxSize()) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { context ->
                        android.webkit.WebView(context).apply {
                            settings.apply {
                                javaScriptEnabled = true
                                allowFileAccess = true
                                allowContentAccess = true
                                domStorageEnabled = true
                                loadWithOverviewMode = true
                                useWideViewPort = true
                            }
                            webViewClient = android.webkit.WebViewClient()
                            loadUrl("file://${file.absolutePath}")
                        }
                    },
                    modifier = Modifier.fillMaxSize().background(Color.White)
                )
            }
        }
    )
}

@Composable
fun PortsTabContent(viewModel: TerminalViewModel) {
    val activeTunnels by viewModel.activeTunnels.collectAsState()
    var portInput by remember { mutableStateOf("") }
    val uiScale by viewModel.uiScale.collectAsState()
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0D1117)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            BasicTextField(
                value = portInput,
                onValueChange = { portInput = it.filter { char -> char.isDigit() } },
                textStyle = TextStyle(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = (13 * uiScale).sp),
                modifier = Modifier.weight(1f).background(Color(0xFF161B22)).padding(10.dp),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (portInput.isEmpty()) Text("Enter port (e.g. 3000)", color = Color.DarkGray, fontFamily = FontFamily.Monospace)
                        inner()
                    }
                }
            )
            Spacer(Modifier.width(16.dp))
            Button(
                onClick = { 
                    portInput.toIntOrNull()?.let { viewModel.startTunnel(it) }
                    portInput = ""
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF58A6FF)),
                enabled = portInput.isNotBlank()
            ) {
                Text("Forward Port", color = Color.White, fontSize = (12 * uiScale).sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = Color(0xFF30363D))
        Spacer(Modifier.height(8.dp))

        if (activeTunnels.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No active port forwards", color = Color.Gray, fontSize = (12 * uiScale).sp)
            }
        } else {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                activeTunnels.forEach { tunnel ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).background(Color(0xFF161B22)).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Port ${tunnel.port}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = (14 * uiScale).sp)
                            Spacer(Modifier.height(4.dp))
                            Text(tunnel.url, color = Color(0xFF58A6FF), fontFamily = FontFamily.Monospace, fontSize = (12 * uiScale).sp,
                                modifier = Modifier.clickable {
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    val clip = android.content.ClipData.newPlainText("URL", tunnel.url)
                                    clipboard.setPrimaryClip(clip)
                                    android.widget.Toast.makeText(context, "URL Copied", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                        Row {
                            IconButton(onClick = { viewModel.openInBrowser(tunnel.url) }) {
                                Icon(Icons.Default.OpenInNew, "Open in Internal Browser", tint = Color(0xFF58A6FF), modifier = Modifier.size((18 * uiScale).dp))
                            }
                            IconButton(onClick = { viewModel.openExternalBrowser(tunnel.url) }) {
                                Icon(Icons.Default.Launch, "Open External", tint = Color.Gray, modifier = Modifier.size((18 * uiScale).dp))
                            }
                            IconButton(onClick = { viewModel.stopTunnel(tunnel.port) }) {
                                Icon(Icons.Default.StopCircle, "Stop Tunnel", tint = Color(0xFFF85149), modifier = Modifier.size((18 * uiScale).dp))
                            }
                        }

                    }
                }
            }
        }
    }
}

@Composable
fun MarketplaceContent(viewModel: TerminalViewModel) {
    val uiScale by viewModel.uiScale.collectAsState()
    val status by viewModel.binaryUpdateStatus.collectAsState()
    val progress by viewModel.binaryUpdateProgress.collectAsState()
    val progressInfo by viewModel.binaryUpdateProgressInfo.collectAsState()
    val isUpdating by viewModel.isUpdatingBinaries.collectAsState()
    val nodeVersion by viewModel.nodeVersion.collectAsState()
    val gitVersion by viewModel.gitVersion.collectAsState()
    val extensions by viewModel.availableExtensions.collectAsState()
    val isScanning by viewModel.isScanningMarketplace.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0D1117))) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("MARKETPLACE", color = Color.White.copy(alpha = 0.7f), fontSize = (12 * uiScale).sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
            Spacer(Modifier.weight(1f))
            if (isScanning) {
                CircularProgressIndicator(modifier = Modifier.size((12 * uiScale).dp), color = Color(0xFF58A6FF), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = { viewModel.scanMarketplace() }, modifier = Modifier.size((24 * uiScale).dp)) {
                    Icon(Icons.Default.Refresh, null, tint = Color.Gray, modifier = Modifier.size((16 * uiScale).dp))
                }
            }
        }

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            // Extensions Section
            if (extensions.isEmpty() && !isScanning) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No extensions found in /marketplace folder", color = Color.Gray, fontSize = (12 * uiScale).sp)
                }
            } else {
                extensions.forEach { ext ->
                    ExtensionItem(
                        extension = ext, 
                        uiScale = uiScale, 
                        isEnabled = viewModel.isExtensionEnabled(ext.id),
                        onToggle = { viewModel.toggleExtensionEnabled(ext.id, !viewModel.isExtensionEnabled(ext.id)) }
                    ) {
                        viewModel.selectGithubExtension(ext)
                    }
                }
            }

            HorizontalDivider(color = Color(0xFF30363D), modifier = Modifier.padding(vertical = 8.dp))

            // System Runtimes Section
            Column(modifier = Modifier.padding(16.dp)) {
                Text("SYSTEM RUNTIMES", color = Color.White.copy(alpha = 0.5f), fontSize = (11 * uiScale).sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                
                BinaryInfoRow("Node.js Runtime", nodeVersion, Icons.Default.Code, uiScale)
                BinaryInfoRow("Git SCM", gitVersion, Icons.Default.AccountTree, uiScale)
                
                Spacer(Modifier.height(24.dp))
                
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF161B22), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .padding(16.dp)
                ) {
                    Text("Binary Update Service", color = Color.White, fontSize = (13 * uiScale).sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(status, color = if (status.contains("Error")) Color(0xFFF85149) else Color(0xFF58A6FF), fontSize = (11 * uiScale).sp, fontFamily = FontFamily.Monospace)
                    
                    if (isUpdating || progressInfo.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(progressInfo, color = Color.Gray, fontSize = (10 * uiScale).sp, modifier = Modifier.weight(1f))
                            if (isUpdating) {
                                Text("${(progress * 100).toInt()}%", color = Color(0xFF58A6FF), fontSize = (10 * uiScale).sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = Color(0xFF58A6FF),
                            trackColor = Color(0xFF30363D)
                        )
                    }
                    
                    Spacer(Modifier.height(16.dp))
                    
                    Row {
                        Button(
                            onClick = { viewModel.checkBinaryUpdates() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D)),
                            enabled = !isUpdating,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("Check", fontSize = (10 * uiScale).sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { viewModel.applyBinaryUpdate() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636)),
                            enabled = !isUpdating,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("Update", fontSize = (10 * uiScale).sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ExtensionItem(extension: com.kodrix.zohaib.bridge.Extension, uiScale: Float, isEnabled: Boolean = true, onToggle: () -> Unit, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable { onClick() }
            .background(Color(0xFF161B22), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size((40 * uiScale).dp)
                .background(Color(0xFF0D1117), androidx.compose.foundation.shape.RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (extension.iconUrl != null) {
                coil.compose.AsyncImage(
                    model = extension.iconUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Default.Extension, null, tint = Color.Gray, modifier = Modifier.size((20 * uiScale).dp))
            }
        }
        
        Spacer(Modifier.width(12.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(extension.name, color = Color.White, fontSize = (13 * uiScale).sp, fontWeight = FontWeight.Bold)
                if (!isEnabled) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .background(Color(0xFFF85149).copy(alpha = 0.2f), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text("DISABLED", color = Color(0xFFF85149), fontSize = (8 * uiScale).sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
            Text(extension.description, color = Color.Gray, fontSize = (10 * uiScale).sp, maxLines = 1)
            Text("By ${extension.author}", color = Color(0xFF58A6FF).copy(alpha = 0.7f), fontSize = (9 * uiScale).sp)
        }
        
        Spacer(Modifier.width(8.dp))
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (extension.isInstalled) {
                IconButton(
                    onClick = onToggle,
                    modifier = Modifier.size((28 * uiScale).dp)
                ) {
                    Icon(
                        if (isEnabled) Icons.Default.CheckCircle else Icons.Default.Cancel,
                        null,
                        tint = if (isEnabled) Color(0xFF3FB950) else Color(0xFFF85149),
                        modifier = Modifier.size((18 * uiScale).dp)
                    )
                }
                Spacer(Modifier.width(4.dp))
            }

            Button(
                onClick = onClick,
                enabled = !extension.isInstalled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (extension.isInstalled) Color.Transparent else Color(0xFF238636),
                    contentColor = if (extension.isInstalled) Color(0xFF3FB950) else Color.White
                ),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.height((28 * uiScale).dp)
            ) {
                Text(if (extension.isInstalled) "Installed" else "Install", fontSize = (10 * uiScale).sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}



@Composable
fun GithubExtensionDetailView(
    extension: com.kodrix.zohaib.bridge.Extension, 
    viewModel: TerminalViewModel,
    onImageClick: (String) -> Unit
) {
    val uiScale by viewModel.uiScale.collectAsState()
    var selectedVersion by remember { mutableStateOf(extension.versions.firstOrNull() ?: "Latest") }
    var showVersionPicker by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0D1117)).padding(24.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { viewModel.selectGithubExtension(null) }) {
                Icon(Icons.Default.ArrowBack, null, tint = Color.Gray)
            }
            Spacer(Modifier.width(8.dp))
            Text("Extension Details", color = Color.Gray, fontSize = (12 * uiScale).sp)
        }
        
        Spacer(Modifier.height(24.dp))
        
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size((96 * uiScale).dp)
                    .background(Color(0xFF161B22), androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (extension.iconUrl != null) {
                    coil.compose.AsyncImage(model = extension.iconUrl, contentDescription = null, modifier = Modifier.fillMaxSize().padding(16.dp))
                } else {
                    Icon(Icons.Default.Extension, null, tint = Color.Gray, modifier = Modifier.size((48 * uiScale).dp))
                }
            }
            
            Spacer(Modifier.width((24 * uiScale).dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(extension.name, color = Color.White, fontSize = (24 * uiScale).sp, fontWeight = FontWeight.Bold)
                Text("by ${extension.author}", color = Color(0xFF58A6FF), fontSize = (14 * uiScale).sp)
                Spacer(Modifier.height(8.dp))
                Text(extension.description, color = Color.Gray, fontSize = (13 * uiScale).sp)
                
                Spacer(Modifier.height(24.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { viewModel.installGithubExtension(extension, selectedVersion) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636)),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                        modifier = Modifier.height((36 * uiScale).dp)
                    ) {
                        val isInstalled = extension.isInstalled
                        Text(if (isInstalled) "Update / Reinstall" else "Install", fontWeight = FontWeight.Bold)
                    }
                    
                    Spacer(Modifier.width(12.dp))
                    
                    if (extension.isInstalled) {
                        val isDisabled = !viewModel.isExtensionEnabled(extension.id)
                        OutlinedButton(
                            onClick = { viewModel.toggleExtensionEnabled(extension.id, isDisabled) },
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                            modifier = Modifier.height((36 * uiScale).dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = if (isDisabled) Color(0xFF238636) else Color(0xFFF85149)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (isDisabled) Color(0xFF238636) else Color(0xFFF85149))
                        ) {
                            Text(if (isDisabled) "Enable" else "Disable", fontSize = (12 * uiScale).sp)
                        }
                        
                        Spacer(Modifier.width(12.dp))
                    }

                    // Version Selector
                    Box {
                        OutlinedButton(
                            onClick = { showVersionPicker = true },
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                            modifier = Modifier.height((36 * uiScale).dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF30363D))
                        ) {
                            Text(selectedVersion, fontSize = (12 * uiScale).sp)
                            Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size((16 * uiScale).dp))
                        }
                        
                        DropdownMenu(
                            expanded = showVersionPicker,
                            onDismissRequest = { showVersionPicker = false },
                            modifier = Modifier.background(Color(0xFF161B22)).border(1.dp, Color(0xFF30363D))
                        ) {
                            val versions = if (extension.versions.isEmpty()) listOf("Latest") else extension.versions
                            versions.forEach { version ->
                                DropdownMenuItem(
                                    text = { Text(version, color = Color.White) },
                                    onClick = {
                                        selectedVersion = version
                                        showVersionPicker = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
        
        if (extension.screenshots.isNotEmpty()) {
            Spacer(Modifier.height(32.dp))
            Text("Screenshots", color = Color.White, fontSize = (16 * uiScale).sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                extension.screenshots.forEach { screenshot ->
                    Box(
                        modifier = Modifier
                            .width((240 * uiScale).dp)
                            .height((135 * uiScale).dp)
                            .padding(end = 12.dp)
                            .background(Color(0xFF161B22), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                            .clickable { onImageClick(screenshot) }
                            .border(1.dp, Color(0xFF30363D), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    ) {
                        coil.compose.AsyncImage(
                            model = screenshot,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(48.dp))
        HorizontalDivider(color = Color(0xFF30363D))
        Spacer(Modifier.height(24.dp))
        
        Text("Information", color = Color.White, fontSize = (16 * uiScale).sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        
        InfoRowItem("Version", extension.version, uiScale)
        InfoRowItem("Identifier", extension.id, uiScale)
        InfoRowItem("Repository", "GitHub", uiScale)
    }
}

@Composable
fun NpmPackageItem(pkg: com.kodrix.zohaib.viewmodel.NpmPackage, uiScale: Float, onInstall: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size((40 * uiScale).dp)
                .background(Color(0xFF161B22), androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                .border(1.dp, Color(0xFF30363D), shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Layers, null, tint = Color(0xFFE3B341), modifier = Modifier.size((20 * uiScale).dp))
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(pkg.name, color = Color(0xFF58A6FF), fontSize = (14 * uiScale).sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text("v${pkg.version} • By ${pkg.author}", color = Color.Gray, fontSize = (11 * uiScale).sp)
                }
                Button(
                    onClick = onInstall,
                    modifier = Modifier.height((28 * uiScale).dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636)),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Text("Install", fontSize = (11 * uiScale).sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(pkg.description, color = Color.Gray, fontSize = (12 * uiScale).sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = Color(0xFF30363D).copy(alpha = 0.5f))
}

@Composable
fun NotificationBellButton(viewModel: TerminalViewModel) {
    val notifications by viewModel.binaryManager.notificationsList.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    val hasOngoing = notifications.any { it.isOngoing }
    val uiScale by viewModel.uiScale.collectAsState()

    Box(modifier = Modifier.wrapContentSize(Alignment.TopEnd)) {
        Box(modifier = Modifier.wrapContentSize()) {
            IconButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.size((28 * uiScale).dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = "Notifications",
                    tint = if (hasOngoing) Color(0xFF58A6FF) else Color(0xFF8B949E),
                    modifier = Modifier.size((18 * uiScale).dp)
                )
            }
            if (hasOngoing || notifications.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size((8 * uiScale).dp)
                        .background(
                            color = if (hasOngoing) Color(0xFF238636) else Color(0xFFD29922),
                            shape = RoundedCornerShape(50)
                        )
                        .align(Alignment.TopEnd)
                        .offset(x = (-2 * uiScale).dp, y = (2 * uiScale).dp)
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(Color(0xFF161B22))
                .width((300 * uiScale).dp)
                .border(1.dp, Color(0xFF30363D), RoundedCornerShape(8.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Installer Logs",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = (13 * uiScale).sp
                )
                if (notifications.isNotEmpty()) {
                    Text(
                        text = "Clear All",
                        color = Color(0xFF58A6FF),
                        fontSize = (11 * uiScale).sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable {
                            viewModel.binaryManager.clearNotifications()
                        }
                    )
                }
            }

            HorizontalDivider(color = Color(0xFF30363D))

            if (notifications.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No notifications",
                        color = Color.Gray,
                        fontSize = (12 * uiScale).sp
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = (250 * uiScale).dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        notifications.forEach { notif ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (notif.isOngoing) {
                                            Icons.Default.Download
                                        } else if (notif.title.contains("Failed", ignoreCase = true)) {
                                            Icons.Default.Error
                                        } else {
                                            Icons.Default.CheckCircle
                                        },
                                        contentDescription = null,
                                        tint = if (notif.isOngoing) {
                                            Color(0xFF58A6FF)
                                        } else if (notif.title.contains("Failed", ignoreCase = true)) {
                                            Color(0xFFF85149)
                                        } else {
                                            Color(0xFF3FB950)
                                        },
                                        modifier = Modifier.size((14 * uiScale).dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = notif.title,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = (12 * uiScale).sp,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = notif.text,
                                    color = Color.LightGray,
                                    fontSize = (11 * uiScale).sp,
                                    modifier = Modifier.padding(start = (22 * uiScale).dp)
                                )

                                if (notif.isOngoing && notif.progress != null) {
                                    Spacer(Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = (22 * uiScale).dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        LinearProgressIndicator(
                                            progress = { notif.progress ?: 0f },
                                            color = Color(0xFF58A6FF),
                                            trackColor = Color(0xFF30363D),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height((4 * uiScale).dp)
                                                .clip(RoundedCornerShape(2.dp))
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = "${(notif.progress * 100).toInt()}%",
                                            color = Color.Gray,
                                            fontSize = (10 * uiScale).sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                HorizontalDivider(color = Color(0xFF30363D).copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            }
        }
    }
}
