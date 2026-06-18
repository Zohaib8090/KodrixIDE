package com.kodrix.zohaib.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kodrix.zohaib.viewmodel.TerminalViewModel

@Composable
fun SettingsContent(viewModel: TerminalViewModel) {
    val uiScale     by viewModel.uiScale.collectAsState()
    val fontSize    by viewModel.fontSize.collectAsState()
    val editorFontSize by viewModel.editorFontSize.collectAsState()
    val showLineNumbers by viewModel.showLineNumbers.collectAsState()
    val scalePercent = (uiScale * 100).toInt()
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0D1117))) {

        // ── Header ───────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0D1117))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Settings,
                contentDescription = null,
                tint = Color(0xFF58A6FF),
                modifier = Modifier.size((16 * uiScale).dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "SETTINGS",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = (12 * uiScale).sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.5.sp
            )
        }
        HorizontalDivider(color = Color(0xFF21262D), thickness = 1.dp)

        // ── Scrollable Body ───────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ── 1. APPEARANCE ──────────────────────────────────────────────
            SettingsSectionCard(
                title = "Appearance",
                icon = Icons.Default.Palette,
                iconTint = Color(0xFF7C6BFF),
                uiScale = uiScale
            ) {
                SettingsStepperRow(
                    label = "Terminal Font Size",
                    value = fontSize.toString(),
                    uiScale = uiScale,
                    onMinus = { viewModel.updateFontSize(-1) },
                    onPlus  = { viewModel.updateFontSize(1) }
                )
                SettingsDivider()
                SettingsStepperRow(
                    label = "Editor Font Size",
                    value = editorFontSize.toString(),
                    uiScale = uiScale,
                    onMinus = { viewModel.updateEditorFontSize(-1) },
                    onPlus  = { viewModel.updateEditorFontSize(1) }
                )
                SettingsDivider()
                SettingsStepperRow(
                    label = "UI Scale",
                    value = "$scalePercent%",
                    uiScale = uiScale,
                    onMinus = { viewModel.updateUIScale(-0.05f) },
                    onPlus  = { viewModel.updateUIScale(0.05f) }
                )
            }

            // ── 2. EDITOR ─────────────────────────────────────────────────
            SettingsSectionCard(
                title = "Editor",
                icon = Icons.Default.Code,
                iconTint = Color(0xFF3FB950),
                uiScale = uiScale
            ) {
                SettingsSwitchRow(
                    label = "Show Line Numbers",
                    description = "Display line numbers in the editor gutter",
                    checked = showLineNumbers,
                    uiScale = uiScale,
                    onCheckedChange = { viewModel.updateLineNumbers(it) }
                )
            }

            // ── 3. ENVIRONMENT ────────────────────────────────────────────
            SettingsSectionCard(
                title = "Environment",
                icon = Icons.Default.Memory,
                iconTint = Color(0xFFD29922),
                uiScale = uiScale
            ) {
                SettingsInfoRow(
                    label = "Architecture",
                    value = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
                    uiScale = uiScale
                )
                SettingsDivider()
                SettingsInfoRow(
                    label = "Android",
                    value = "API ${android.os.Build.VERSION.SDK_INT} (${android.os.Build.VERSION.RELEASE})",
                    uiScale = uiScale
                )
                SettingsDivider()
                SettingsInfoRow(
                    label = "Device",
                    value = android.os.Build.MODEL,
                    uiScale = uiScale
                )
                SettingsDivider()

                // ── Safe Mode toggle ──────────────────────────────────────────
                val isSafeMode by viewModel.isSafeMode.collectAsState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = (12 * uiScale).dp, vertical = (10 * uiScale).dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Safe Mode",
                                color = if (isSafeMode) Color(0xFFFF7B72) else Color.White,
                                fontSize = (13 * uiScale).sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (isSafeMode) {
                                Spacer(Modifier.width((6 * uiScale).dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape((4 * uiScale).dp))
                                        .background(Color(0xFFDA3633).copy(alpha = 0.2f))
                                        .padding(horizontal = (5 * uiScale).dp, vertical = (1 * uiScale).dp)
                                ) {
                                    Text(
                                        "ACTIVE",
                                        color = Color(0xFFFF7B72),
                                        fontSize = (8 * uiScale).sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height((2 * uiScale).dp))
                        Text(
                            text = if (isSafeMode)
                                "Terminal is using bundled runtimes only. Tap to disable after fixing the issue."
                            else
                                "Emergency rollback — routes terminal to bundled runtimes if a downloaded version breaks.",
                            color = Color(0xFF8B949E),
                            fontSize = (10 * uiScale).sp,
                            lineHeight = (14 * uiScale).sp
                        )
                    }
                    Spacer(Modifier.width((8 * uiScale).dp))
                    Switch(
                        checked = isSafeMode,
                        onCheckedChange = { viewModel.setSafeMode(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor    = Color(0xFFFF7B72),
                            checkedTrackColor    = Color(0xFFDA3633).copy(alpha = 0.4f),
                            uncheckedThumbColor  = Color(0xFF484F58),
                            uncheckedTrackColor  = Color(0xFF21262D)
                        )
                    )
                }
            }

            // ── 4. UPDATES ────────────────────────────────────────────────
            SettingsSectionCard(
                title = "Updates",
                icon = Icons.Default.Upgrade,
                iconTint = Color(0xFF58A6FF),
                uiScale = uiScale
            ) {
                val currentVersion = try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.1.2"
                } catch (e: Exception) {
                    "1.1.2"
                }
                SettingsInfoRow(label = "Current Version", value = currentVersion, uiScale = uiScale)
                SettingsDivider()
                SettingsActionRow(
                    label = "Check for Updates",
                    description = "Browse the latest releases on GitHub",
                    icon = Icons.Default.OpenInNew,
                    uiScale = uiScale,
                    onClick = {
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/Zohaib8090/KodrixIDE/releases")
                        )
                        intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(intent)
                    }
                )
                SettingsDivider()
                SettingsActionRow(
                    label = "View Changelog",
                    description = "See what's new in each release",
                    icon = Icons.Default.Article,
                    uiScale = uiScale,
                    onClick = {
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/Zohaib8090/KodrixIDE/blob/main/CHANGELOG.md")
                        )
                        intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(intent)
                    }
                )
            }

            // ── 5. DEVELOPER ──────────────────────────────────────────────
            val isBetaMode by viewModel.isBetaMode.collectAsState()
            SettingsSectionCard(
                title = "Developer",
                icon = Icons.Default.Science,
                iconTint = Color(0xFFF78166),
                uiScale = uiScale
            ) {
                // Beta Mode toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = (12 * uiScale).dp, vertical = (10 * uiScale).dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Beta Mode",
                                color = if (isBetaMode) Color(0xFFF78166) else Color.White,
                                fontSize = (13 * uiScale).sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (isBetaMode) {
                                Spacer(Modifier.width((6 * uiScale).dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape((4 * uiScale).dp))
                                        .background(Color(0xFFF78166).copy(alpha = 0.15f))
                                        .padding(horizontal = (5 * uiScale).dp, vertical = (1 * uiScale).dp)
                                ) {
                                    Text(
                                        "ON",
                                        color = Color(0xFFF78166),
                                        fontSize = (8 * uiScale).sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height((2 * uiScale).dp))
                        Text(
                            text = if (isBetaMode)
                                "Experimental features are visible. Things may break — you asked for it!"
                            else
                                "Enable to access features that are under active testing before they ship.",
                            color = Color(0xFF8B949E),
                            fontSize = (10 * uiScale).sp,
                            lineHeight = (14 * uiScale).sp
                        )
                    }
                    Spacer(Modifier.width((8 * uiScale).dp))
                    Switch(
                        checked = isBetaMode,
                        onCheckedChange = { viewModel.setBetaMode(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor   = Color(0xFFF78166),
                            checkedTrackColor   = Color(0xFFF78166).copy(alpha = 0.35f),
                            uncheckedThumbColor = Color(0xFF484F58),
                            uncheckedTrackColor = Color(0xFF21262D)
                        )
                    )
                }

                HorizontalDivider(color = Color(0xFF30363D), thickness = 1.dp)

                // Force Crash button for Crashlytics
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { throw RuntimeException("App is forced crashed") }
                        .padding(horizontal = (12 * uiScale).dp, vertical = (12 * uiScale).dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.BugReport,
                        contentDescription = "Force Crash",
                        tint = Color(0xFFF85149),
                        modifier = Modifier.size((18 * uiScale).dp)
                    )
                    Spacer(Modifier.width((12 * uiScale).dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Force Test Crash",
                            color = Color(0xFFF85149),
                            fontSize = (13 * uiScale).sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height((2 * uiScale).dp))
                        Text(
                            "Crash the app intentionally to verify Firebase Crashlytics reporting.",
                            color = Color(0xFF8B949E),
                            fontSize = (10 * uiScale).sp,
                            lineHeight = (14 * uiScale).sp
                        )
                    }
                }
            }

            // ── 6. ABOUT ──────────────────────────────────────────────────
            SettingsSectionCard(
                title = "About",
                icon = Icons.Default.Info,
                iconTint = Color(0xFF58A6FF),
                uiScale = uiScale
            ) {
                SettingsInfoRow(label = "App Name", value = "Kodrix IDE", uiScale = uiScale)
                SettingsDivider()
                SettingsInfoRow(label = "Author", value = "Zohaib", uiScale = uiScale)
                SettingsDivider()
                SettingsActionRow(
                    label = "Source Code",
                    description = "View the project on GitHub",
                    icon = Icons.Default.OpenInNew,
                    uiScale = uiScale,
                    onClick = {
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/Zohaib8090/KodrixIDE")
                        )
                        intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(intent)
                    }
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ─── Section Card Container ───────────────────────────────────────────────────
@Composable
fun SettingsSectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    uiScale: Float,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF21262D))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Section Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size((26 * uiScale).dp)
                        .background(iconTint.copy(alpha = 0.12f), RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size((14 * uiScale).dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    title,
                    color = Color.White,
                    fontSize = (12 * uiScale).sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            HorizontalDivider(color = Color(0xFF21262D), thickness = 1.dp)
            // Content rows
            Column(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
    }
}

// ─── Row Types ────────────────────────────────────────────────────────────────

@Composable
fun SettingsStepperRow(label: String, value: String, uiScale: Float, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = Color.White.copy(alpha = 0.9f),
            fontSize = (12 * uiScale).sp,
            modifier = Modifier.weight(1f)
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size((26 * uiScale).dp)
                    .background(Color(0xFF21262D), RoundedCornerShape(6.dp))
                    .clickable { onMinus() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Remove, null, tint = Color(0xFF8B949E), modifier = Modifier.size((14 * uiScale).dp))
            }
            Text(
                value,
                color = Color(0xFF58A6FF),
                fontSize = (12 * uiScale).sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 6.dp)
            )
            Box(
                modifier = Modifier
                    .size((26 * uiScale).dp)
                    .background(Color(0xFF21262D), RoundedCornerShape(6.dp))
                    .clickable { onPlus() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Add, null, tint = Color(0xFF8B949E), modifier = Modifier.size((14 * uiScale).dp))
            }
        }
    }
}

@Composable
fun SettingsSwitchRow(label: String, description: String, checked: Boolean, uiScale: Float, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = Color.White.copy(alpha = 0.9f), fontSize = (12 * uiScale).sp)
            if (description.isNotEmpty()) {
                Text(description, color = Color(0xFF8B949E), fontSize = (10 * uiScale).sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF238636),
                uncheckedThumbColor = Color(0xFF8B949E),
                uncheckedTrackColor = Color(0xFF21262D)
            )
        )
    }
}

@Composable
fun SettingsInfoRow(label: String, value: String, uiScale: Float) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color(0xFF8B949E), fontSize = (12 * uiScale).sp, modifier = Modifier.weight(1f))
        Text(value, color = Color.White.copy(alpha = 0.9f), fontSize = (11 * uiScale).sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun SettingsActionRow(label: String, description: String, icon: androidx.compose.ui.graphics.vector.ImageVector, uiScale: Float, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = Color.White.copy(alpha = 0.9f), fontSize = (12 * uiScale).sp)
            if (description.isNotEmpty()) {
                Text(description, color = Color(0xFF8B949E), fontSize = (10 * uiScale).sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Icon(icon, contentDescription = null, tint = Color(0xFF8B949E), modifier = Modifier.size((14 * uiScale).dp))
    }
}

@Composable
fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 14.dp),
        color = Color(0xFF21262D),
        thickness = 0.5.dp
    )
}

// ─── Legacy helpers (kept for backward compat) ────────────────────────────────
@Composable
fun SettingSwitchItem(label: String, checked: Boolean, uiScale: Float, onCheckedChange: (Boolean) -> Unit) {
    SettingsSwitchRow(label, "", checked, uiScale, onCheckedChange)
}

@Composable
fun SettingRowItem(label: String, value: String, uiScale: Float, onMinus: () -> Unit, onPlus: () -> Unit) {
    SettingsStepperRow(label, value, uiScale, onMinus, onPlus)
}

@Composable
fun InfoRowItem(label: String, value: String, uiScale: Float) {
    SettingsInfoRow(label, value, uiScale)
}

@Composable
fun BinaryInfoRow(name: String, version: String, icon: androidx.compose.ui.graphics.vector.ImageVector, uiScale: Float) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Color.Gray, modifier = Modifier.size((16 * uiScale).dp))
        Spacer(Modifier.width(8.dp))
        Text(name, color = Color.White, fontSize = (12 * uiScale).sp, modifier = Modifier.weight(1f))
        Text(version, color = Color(0xFF58A6FF), fontSize = (11 * uiScale).sp, fontFamily = FontFamily.Monospace)
    }
}
