package com.kodrix.zohaib.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.kodrix.zohaib.viewmodel.TerminalViewModel
import com.kodrix.zohaib.bridge.Extension
import com.kodrix.zohaib.bridge.BinaryManager
import com.kodrix.zohaib.bridge.VersionChecker
import kotlinx.coroutines.launch

// ─── Tabs ────────────────────────────────────────────────────────────────────
private enum class MarketplaceTab { EXTENSIONS, RUNTIMES }

@Composable
fun MarketplaceView(viewModel: TerminalViewModel) {
    val uiScale by viewModel.uiScale.collectAsState()
    val extensions by viewModel.availableExtensions.collectAsState()
    val isScanning by viewModel.isScanningMarketplace.collectAsState()

    var selectedTab by remember { mutableStateOf(MarketplaceTab.EXTENSIONS) }
    var searchQuery by remember { mutableStateOf("") }
    val openRuntimes by viewModel.openRuntimesTab.collectAsState()
    LaunchedEffect(openRuntimes) {
        if (openRuntimes) {
            selectedTab = MarketplaceTab.RUNTIMES
            searchQuery = ""
            viewModel.consumeOpenRuntimesTab()
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
    ) {
        // ── Header ────────────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF161B22), Color(0xFF0D1117))
                    )
                )
                .padding(horizontal = (12 * uiScale).dp, vertical = (10 * uiScale).dp)
        ) {
            // Title row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.Storefront,
                    contentDescription = null,
                    tint = Color(0xFF58A6FF),
                    modifier = Modifier.size((16 * uiScale).dp)
                )
                Spacer(Modifier.width((6 * uiScale).dp))
                Text(
                    text = "MARKETPLACE",
                    color = Color.White,
                    fontSize = (13 * uiScale).sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.weight(1f))

                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size((14 * uiScale).dp),
                        color = Color(0xFF58A6FF),
                        strokeWidth = 2.dp
                    )
                } else {
                    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.GetContent()
                    ) { uri -> uri?.let { viewModel.installLocalExtension(it) } }

                    if (selectedTab == MarketplaceTab.EXTENSIONS) {
                        IconButton(
                            onClick = { launcher.launch("application/zip") },
                            modifier = Modifier.size((28 * uiScale).dp)
                        ) {
                            Icon(Icons.Default.FileUpload, null, tint = Color(0xFF58A6FF), modifier = Modifier.size((16 * uiScale).dp))
                        }
                        IconButton(
                            onClick = { viewModel.scanMarketplace() },
                            modifier = Modifier.size((28 * uiScale).dp)
                        ) {
                            Icon(Icons.Default.Refresh, null, tint = Color.Gray, modifier = Modifier.size((16 * uiScale).dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height((8 * uiScale).dp))

            // ── Search bar ────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape((6 * uiScale).dp))
                    .background(Color(0xFF21262D))
                    .border(1.dp, Color(0xFF30363D), RoundedCornerShape((6 * uiScale).dp))
                    .padding(horizontal = (10 * uiScale).dp, vertical = (6 * uiScale).dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size((14 * uiScale).dp)
                    )
                    Spacer(Modifier.width((6 * uiScale).dp))
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(color = Color.White, fontSize = (12 * uiScale).sp),
                        cursorBrush = SolidColor(Color(0xFF58A6FF)),
                        singleLine = true,
                        decorationBox = { inner ->
                            if (searchQuery.isEmpty()) {
                                Text(
                                    when (selectedTab) {
                                        MarketplaceTab.EXTENSIONS -> "Search extensions..."
                                        MarketplaceTab.RUNTIMES   -> "Search runtimes..."
                                    },
                                    color = Color(0xFF6E7681),
                                    fontSize = (12 * uiScale).sp
                                )
                            }
                            inner()
                        }
                    )
                }
            }

            Spacer(Modifier.height((8 * uiScale).dp))

            // ── Tab row ───────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape((6 * uiScale).dp))
                    .background(Color(0xFF161B22))
                    .padding((3 * uiScale).dp),
                horizontalArrangement = Arrangement.spacedBy((3 * uiScale).dp)
            ) {
                MarketplaceTab.values().forEach { tab ->
                    val selected = selectedTab == tab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape((4 * uiScale).dp))
                            .background(if (selected) Color(0xFF21262D) else Color.Transparent)
                            .clickable { selectedTab = tab; searchQuery = "" }
                            .padding(vertical = (5 * uiScale).dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = when (tab) {
                                    MarketplaceTab.EXTENSIONS -> Icons.Default.Extension
                                    MarketplaceTab.RUNTIMES   -> Icons.Default.SettingsEthernet
                                    },
                                    contentDescription = null,
                                    tint = if (selected) Color(0xFF58A6FF) else Color.Gray,
                                    modifier = Modifier.size((12 * uiScale).dp)
                                )
                                Spacer(Modifier.width((4 * uiScale).dp))
                                Text(
                                    when (tab) {
                                        MarketplaceTab.EXTENSIONS -> "Extensions"
                                        MarketplaceTab.RUNTIMES   -> "Runtimes"
                                    },
                                    color = if (selected) Color.White else Color.Gray,
                                    fontSize = (11 * uiScale).sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
        }

        // ── Content ───────────────────────────────────────────────────────────
        when (selectedTab) {
            MarketplaceTab.EXTENSIONS -> ExtensionsTab(viewModel, uiScale, searchQuery)
            MarketplaceTab.RUNTIMES   -> RuntimesTab(viewModel, uiScale, searchQuery)
        }
    }
}

// ─── Extensions Tab ──────────────────────────────────────────────────────────
@Composable
private fun ExtensionsTab(viewModel: TerminalViewModel, uiScale: Float, searchQuery: String) {
    val extensions by viewModel.availableExtensions.collectAsState()
    val isScanning by viewModel.isScanningMarketplace.collectAsState()

    val filtered = remember(extensions, searchQuery) {
        if (searchQuery.isBlank()) extensions
        else extensions.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.author.contains(searchQuery, ignoreCase = true) ||
            it.description.contains(searchQuery, ignoreCase = true)
        }
    }

    if (filtered.isEmpty() && !isScanning) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                if (searchQuery.isBlank()) "No extensions found in the repository"
                else "No results for \"$searchQuery\"",
                color = Color.Gray
            )
        }
    } else {
        var versionDialogExtension by remember { mutableStateOf<Extension?>(null) }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 140.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(filtered) { ext ->
                MarketplaceCard(ext, viewModel, uiScale, onInstallClick = {
                    if (ext.versions.size > 1) {
                        versionDialogExtension = ext
                    } else {
                        viewModel.installGithubExtension(ext)
                    }
                })
            }
        }

        if (versionDialogExtension != null) {
            VersionPickerDialog(
                extension = versionDialogExtension!!,
                onDismiss = { versionDialogExtension = null },
                onVersionSelected = { version ->
                    viewModel.installGithubExtension(versionDialogExtension!!, version)
                    versionDialogExtension = null
                }
            )
        }
    }
}

// ─── Runtimes Tab ─────────────────────────────────────────────────────────────
@Composable
private fun RuntimesTab(viewModel: TerminalViewModel, uiScale: Float, searchQuery: String) {
    val versions by viewModel.binaryManager.availableVersions.collectAsState()
    val toolMetas by viewModel.binaryManager.toolMetas.collectAsState()
    val isSyncing by viewModel.binaryManager.isSyncing.collectAsState()
    val downloadProgress by viewModel.binaryManager.downloadProgress.collectAsState()
    val installStates by viewModel.binaryManager.installStates.collectAsState()
    val installErrors by viewModel.binaryManager.installErrors.collectAsState()
    val verifiedVersions by viewModel.binaryManager.verifiedVersions.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.binaryManager.syncVersions()
    }

    val metaMap = remember(toolMetas) { toolMetas.associateBy { it.id } }
    val filtered = remember(versions, searchQuery, metaMap) {
        if (searchQuery.isBlank()) versions
        else versions.filter {
            val meta = metaMap[it.tool]
            it.version.contains(searchQuery, ignoreCase = true) ||
            it.tag.contains(searchQuery, ignoreCase = true) ||
            it.tool.contains(searchQuery, ignoreCase = true) ||
            (meta?.displayName?.contains(searchQuery, ignoreCase = true) == true) ||
            (meta?.extensions?.any { ext -> ext.equals(searchQuery.removePrefix("."), ignoreCase = true) } == true)
        }
    }

    if (isSyncing && versions.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color(0xFF58A6FF))
                Spacer(Modifier.height((12 * uiScale).dp))
                Text("Loading languages and runtimes…", color = Color.Gray, fontSize = (13 * uiScale).sp)
            }
        }
        return
    }

    if (filtered.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                if (searchQuery.isBlank()) "No runtimes found" else "No results for \"$searchQuery\"",
                color = Color.Gray,
                fontSize = (13 * uiScale).sp
            )
        }
        return
    }

    val grouped = remember(filtered) { filtered.groupBy { it.tool } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = (10 * uiScale).dp, vertical = (12 * uiScale).dp),
        verticalArrangement = Arrangement.spacedBy((10 * uiScale).dp)
    ) {
        grouped.forEach { (toolName, toolVersions) ->
            val meta = metaMap[toolName]
            val inUse = toolVersions.firstOrNull { it.isActive }
            item(key = "header_$toolName") {
                RuntimeHeader(meta, toolName, inUse, uiScale)
            }
            items(toolVersions, key = { "${it.tool}_${it.version}_${it.source}" }) { ver ->
                val key = "${ver.tool}_${ver.version}"
                val verified = verifiedVersions[ver.tool]
                val verifyError = verified?.takeIf {
                    !it.isVerified && it.version.trimStart('v') == ver.version.trimStart('v')
                }?.errorReason
                RuntimeCard(
                    ver = ver,
                    toolIconUrl = meta?.iconUrl ?: "",
                    progress = downloadProgress[ver.version],
                    stage = installStates[ver.version]?.stage,
                    error = installErrors[key] ?: verifyError,
                    uiScale = uiScale,
                    onDownload = {
                        scope.launch { viewModel.binaryManager.install(ver) }
                    },
                    onActivate = {
                        scope.launch {
                            try {
                                viewModel.binaryManager.setActiveVersion(ver.tool, ver.version)
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(
                                    viewModel.getApplication(),
                                    e.message ?: "Failed to switch version",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    onRemove = {
                        scope.launch { viewModel.binaryManager.uninstall(ver.tool, ver.version) }
                    }
                )
            }
        }
    }
}

@Composable
private fun RuntimeHeader(meta: BinaryManager.ToolMeta?, toolName: String, inUse: BinaryManager.RemoteVersion?, uiScale: Float) {
    Column(modifier = Modifier.padding(top = (8 * uiScale).dp, bottom = (2 * uiScale).dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size((22 * uiScale).dp)
                    .clip(RoundedCornerShape((5 * uiScale).dp))
                    .background(Color(0xFF21262D)),
                contentAlignment = Alignment.Center
            ) {
                if (meta?.iconUrl?.isNotEmpty() == true) {
                    AsyncImage(
                        model = meta.iconUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(Icons.Default.SettingsEthernet, null,
                        tint = Color(0xFF58A6FF),
                        modifier = Modifier.size((13 * uiScale).dp))
                }
            }
            Spacer(Modifier.width((6 * uiScale).dp))
            Text(
                meta?.displayName ?: toolName,
                color = Color.White,
                fontSize = (13 * uiScale).sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width((6 * uiScale).dp))
            if (meta?.category?.isNotEmpty() == true) {
                Chip(meta.category, Color(0xFF58A6FF), uiScale)
            }
            if (meta?.hasLanguageServer == true) {
                Spacer(Modifier.width((4 * uiScale).dp))
                Chip("Autocomplete", Color(0xFF3FB950), uiScale)
            }
        }
        val details = buildList {
            if (meta?.description?.isNotEmpty() == true) add(meta.description)
            if (meta?.extensions?.isNotEmpty() == true) add("Files: " + meta.extensions.joinToString(" ") { ".$it" })
            add(
                if (inUse == null) "Not installed"
                else "In use: v${inUse.version.removePrefix("v")}" + (if (inUse.source == "bundled") " (built into Kodrix)" else "")
            )
        }
        Spacer(Modifier.height((3 * uiScale).dp))
        Text(
            details.joinToString(" · "),
            color = Color(0xFF8B949E),
            fontSize = (10 * uiScale).sp,
            lineHeight = (14 * uiScale).sp
        )
    }
}

@Composable
private fun Chip(text: String, color: Color, uiScale: Float, dim: Boolean = false) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape((4 * uiScale).dp))
            .background(color.copy(alpha = if (dim) 0.08f else 0.16f))
            .padding(horizontal = (5 * uiScale).dp, vertical = (2 * uiScale).dp)
    ) {
        Text(
            text = text,
            color = if (dim) color.copy(alpha = 0.45f) else color,
            fontSize = (9 * uiScale).sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false
        )
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1_000_000 -> "${bytes / 1_000_000} MB"
    else -> "${maxOf(1, bytes / 1_000)} KB"
}

@Composable
private fun RuntimeCard(
    ver: BinaryManager.RemoteVersion,
    toolIconUrl: String,
    progress: Float?,
    stage: String?,
    error: String?,
    uiScale: Float,
    onDownload: () -> Unit,
    onActivate: () -> Unit,
    onRemove: () -> Unit
) {
    val label = ver.label.ifEmpty { BinaryManager.friendlyLabel(ver.tag, ver.source) }
    val labelColor = when (label) {
        "Built-in" -> Color(0xFF58A6FF)
        "Latest"   -> Color(0xFFA371F7)
        "LTS"      -> Color(0xFF1F6FEB)
        else       -> Color(0xFF6E7681)
    }
    var showFullError by remember(error) { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (ver.isUnavailable) Color(0xFF0D1117) else Color(0xFF161B22)
        ),
        shape = RoundedCornerShape((8 * uiScale).dp),
        border = BorderStroke(
            width = 1.dp,
            color = when {
                ver.isUnavailable -> Color(0xFF21262D)
                error != null     -> Color(0xFFDA3633).copy(alpha = 0.6f)
                ver.isActive      -> Color(0xFF238636)
                progress != null  -> Color(0xFF1F6FEB)
                else              -> Color(0xFF30363D)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = (10 * uiScale).dp, vertical = (10 * uiScale).dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size((34 * uiScale).dp)
                    .clip(RoundedCornerShape((6 * uiScale).dp))
                    .background(Color(0xFF21262D)),
                contentAlignment = Alignment.Center
            ) {
                if (toolIconUrl.isNotEmpty() && !ver.isUnavailable) {
                    AsyncImage(
                        model = toolIconUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape((6 * uiScale).dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text("⬡", color = if (ver.isUnavailable) Color(0xFF3D4047) else Color(0xFF68A063), fontSize = (14 * uiScale).sp)
                }
            }

            Spacer(Modifier.width((8 * uiScale).dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        ver.version.isEmpty() -> ver.tag
                        else -> "v${ver.version.removePrefix("v")}"
                    },
                    color = if (ver.isUnavailable) Color(0xFF484F58) else Color.White,
                    fontSize = (13 * uiScale).sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height((2 * uiScale).dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy((4 * uiScale).dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Chip(label, labelColor, uiScale, dim = ver.isUnavailable)
                    if (ver.isActive) Chip("IN USE", Color(0xFF3FB950), uiScale)
                }
                Spacer(Modifier.height((2 * uiScale).dp))
                val size = formatSize(ver.sizeBytes)
                Text(
                    text = when {
                        ver.isUnavailable -> ver.note.ifEmpty { "Not available" }
                        progress != null  -> when (stage) {
                            "resolving" -> "Preparing…"
                            "language server" -> "Installing the language server…"
                            "verifying" -> "Checking it runs…"
                            "extracting" -> "Unpacking…"
                            else -> "Downloading… ${(progress * 100).toInt()}%"
                        }
                        ver.isActive && ver.source == "bundled" -> "In use — built into Kodrix, always available"
                        ver.isActive      -> "In use in the terminal and editor"
                        ver.source == "bundled" -> "Built into Kodrix — tap Use to switch back to it"
                        ver.isInstalled   -> "Downloaded — tap Use to switch to it"
                        size.isNotEmpty() -> "Not downloaded · $size"
                        else              -> "Not downloaded"
                    },
                    color = if (ver.isUnavailable) Color(0xFF6E7681) else Color.Gray,
                    fontSize = (9 * uiScale).sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (error != null) {
                    Spacer(Modifier.height((4 * uiScale).dp))
                    Text(
                        text = if (showFullError) error else error.lineSequence().first() + if (error.contains('\n')) "  (tap for details)" else "",
                        color = Color(0xFFFF7B72),
                        fontSize = (9 * uiScale).sp,
                        lineHeight = (13 * uiScale).sp,
                        fontFamily = if (showFullError) androidx.compose.ui.text.font.FontFamily.Monospace else null,
                        modifier = Modifier.clickable { showFullError = !showFullError }
                    )
                }
                if (progress != null) {
                    Spacer(Modifier.height((6 * uiScale).dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height((3 * uiScale).dp).clip(RoundedCornerShape((2 * uiScale).dp)),
                        color = Color(0xFF58A6FF),
                        trackColor = Color(0xFF30363D)
                    )
                }
            }

            Spacer(Modifier.width((8 * uiScale).dp))

            when {
                ver.isUnavailable -> Icon(
                    Icons.Default.Lock,
                    contentDescription = "Unavailable",
                    tint = Color(0xFF3D4047),
                    modifier = Modifier.size((18 * uiScale).dp)
                )
                progress != null -> CircularProgressIndicator(
                    modifier = Modifier.size((20 * uiScale).dp),
                    color = Color(0xFF58A6FF),
                    strokeWidth = (2 * uiScale).dp
                )
                ver.isActive -> Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "In use",
                    tint = Color(0xFF3FB950),
                    modifier = Modifier.size((20 * uiScale).dp)
                )
                ver.isInstalled -> Row(verticalAlignment = Alignment.CenterVertically) {
                    if (ver.source != "bundled") {
                        IconButton(onClick = onRemove, modifier = Modifier.size((24 * uiScale).dp)) {
                            Icon(Icons.Default.Delete, "Remove", tint = Color(0xFF8B949E), modifier = Modifier.size((14 * uiScale).dp))
                        }
                    }
                    Button(
                        onClick = onActivate,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F6FEB)),
                        shape = RoundedCornerShape((4 * uiScale).dp),
                        contentPadding = PaddingValues(horizontal = (8 * uiScale).dp, vertical = 0.dp),
                        modifier = Modifier.height((24 * uiScale).dp)
                    ) {
                        Text("Use", fontSize = (10 * uiScale).sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
                else -> Button(
                    onClick = onDownload,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636)),
                    shape = RoundedCornerShape((4 * uiScale).dp),
                    contentPadding = PaddingValues(horizontal = (6 * uiScale).dp, vertical = 0.dp),
                    modifier = Modifier.height((24 * uiScale).dp)
                ) {
                    Icon(Icons.Default.Download, null, modifier = Modifier.size((10 * uiScale).dp), tint = Color.White)
                    Spacer(Modifier.width((3 * uiScale).dp))
                    Text(if (error != null) "Retry" else "Install", fontSize = (10 * uiScale).sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ─── Version Picker Dialog ────────────────────────────────────────────────────
@Composable
fun VersionPickerDialog(
    extension: Extension,
    onDismiss: () -> Unit,
    onVersionSelected: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Version", color = Color.White) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                extension.versions.forEach { version ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onVersionSelected(version) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(version, color = Color.White, fontSize = 16.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Color.Gray) }
        },
        containerColor = Color(0xFF161B22),
        shape = RoundedCornerShape(8.dp)
    )
}

// ─── Extension Card ───────────────────────────────────────────────────────────
@Composable
fun MarketplaceCard(extension: Extension, viewModel: TerminalViewModel, uiScale: Float, onInstallClick: () -> Unit) {
    val installingIds by viewModel.installingIds.collectAsState()
    val installingProgress by viewModel.installingProgress.collectAsState()

    val isInstalling = installingIds.contains(extension.id)
    val progress = installingProgress[extension.id] ?: 0f
    val isInstalled = extension.isInstalled

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clickable(enabled = !isInstalling) { viewModel.selectGithubExtension(extension) },
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(if (isInstalling) 0xFF58A6FF else 0xFF30363D))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF21262D)),
                contentAlignment = Alignment.Center
            ) {
                if (extension.iconUrl != null) {
                    AsyncImage(
                        model = extension.iconUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        Icons.Default.Extension,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(32.dp)
                    )
                }
                if (isInstalling) {
                    Box(Modifier.fillMaxSize().background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${(progress * 100).toInt()}%",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = progress,
                                modifier = Modifier.width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
                                color = Color(0xFF58A6FF),
                                trackColor = Color(0xFF30363D)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = extension.name,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = extension.author,
                color = Color.Gray,
                fontSize = 10.sp,
                maxLines = 1
            )

            Spacer(Modifier.weight(1f))

            Button(
                onClick = { if (!isInstalling) onInstallClick() },
                enabled = !isInstalling,
                modifier = Modifier.fillMaxWidth().height(32.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = when {
                        isInstalling -> Color(0xFF161B22)
                        isInstalled  -> Color(0xFF21262D)
                        else         -> Color(0xFF238636)
                    },
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(6.dp)
            ) {
                if (isInstalling) {
                    Text("Installing ${(progress * 100).toInt()}%", fontSize = 10.sp)
                } else if (isInstalled) {
                    Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Installed", fontSize = 11.sp)
                } else {
                    Text("Install", fontSize = 11.sp)
                }
            }
        }
    }
}
