package com.kodrix.zohaib.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kodrix.zohaib.agent.ActiveProvider
import com.kodrix.zohaib.agent.AutoAgent
import com.kodrix.zohaib.agent.ChatMessage
import com.kodrix.zohaib.agent.PendingApproval
import com.kodrix.zohaib.agent.ProviderInfo

/**
 * AgentPanel — the Compose UI for the new Hermes-style agent runtime.
 *
 * Sections (top to bottom):
 *   1. Status bar: server health + active provider/model
 *   2. Provider picker dropdown
 *   3. Messages list (scrollable)
 *   4. Streaming cursor / pending tool approval card
 *   5. Input row: text field + Send / Auto Run / Stop buttons
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentPanel(
    agent: AutoAgent,
    serverHealthy: Boolean,
    modifier: Modifier = Modifier,
) {
    val messages by agent.messages.collectAsState()
    val streaming by agent.streamingContent.collectAsState()
    val status by agent.status.collectAsState()
    val providers by agent.availableProviders.collectAsState()
    val activeProvider by agent.activeProvider.collectAsState()
    val pendingApproval by agent.pendingApproval.collectAsState()
    val activeSkill by agent.activeSkill.collectAsState()

    var input by remember { mutableStateOf("") }
    var showProviderMenu by remember { mutableStateOf(false) }
    var showAutoRunDialog by remember { mutableStateOf(false) }
    var autoRunGoal by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when messages change.
    LaunchedEffect(messages.size, streaming) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    LaunchedEffect(Unit) {
        agent.refreshProviders()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0E0E10))
            .padding(8.dp)
    ) {
        // ---- Status bar ----
        StatusBar(serverHealthy = serverHealthy, status = status, activeProvider = activeProvider)

        Spacer(Modifier.height(4.dp))

        // ---- Provider picker ----
        ProviderPicker(
            providers = providers,
            activeProvider = activeProvider,
            expanded = showProviderMenu,
            onToggle = { showProviderMenu = !showProviderMenu },
            onSelect = { id, model ->
                agent.setProvider(id, model)
                showProviderMenu = false
            },
        )

        Spacer(Modifier.height(4.dp))

        // ---- Active skill badge (if any) ----
        activeSkill?.let { skill ->
            Surface(
                color = Color(0xFF1F2A1F),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.padding(vertical = 2.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("🎯 ${skill.name}", color = Color(0xFF8BC34A), fontSize = 11.sp)
                }
            }
        }

        // ---- Messages list ----
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(messages) { msg -> MessageBubble(msg) }
            // Streaming in-progress cursor
            if (streaming != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1D)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = streaming ?: "",
                            color = Color(0xFFE0E0E0),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
            }
            // Tool approval card
            pendingApproval?.let { approval ->
                item {
                    ToolApprovalCard(
                        approval = approval,
                        onApprove = { agent.approveTool(approval) },
                        onReject = { agent.rejectTool(approval) },
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ---- Input row ----
        InputRow(
            input = input,
            onInputChange = { input = it },
            isBusy = status != "idle" && status != "stopped" && status != "error",
            onSend = {
                if (input.isNotBlank()) {
                    agent.send(input)
                    input = ""
                }
            },
            onAutoRun = { showAutoRunDialog = true },
            onStop = { agent.stop() },
            onClear = { agent.clear() },
        )
    }

    // ---- Auto-run dialog ----
    if (showAutoRunDialog) {
        AutoRunDialog(
            goal = autoRunGoal,
            onGoalChange = { autoRunGoal = it },
            onConfirm = {
                if (autoRunGoal.isNotBlank()) {
                    agent.autoRun(autoRunGoal)
                    autoRunGoal = ""
                    showAutoRunDialog = false
                }
            },
            onDismiss = { showAutoRunDialog = false },
        )
    }
}

@Composable
private fun StatusBar(serverHealthy: Boolean, status: String, activeProvider: ActiveProvider?) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1D), RoundedCornerShape(6.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (serverHealthy) Icons.Default.CheckCircle else Icons.Default.Cancel,
            contentDescription = null,
            tint = if (serverHealthy) Color(0xFF4CAF50) else Color(0xFFEF5350),
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (serverHealthy) "agent server ready" else "agent server: ${status.take(30)}",
            color = Color(0xFFB0B0B0),
            fontSize = 11.sp,
        )
        Spacer(Modifier.weight(1f))
        if (activeProvider != null) {
            Text(
                text = "${activeProvider.id}/${activeProvider.model}",
                color = Color(0xFF8AB4F8),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderPicker(
    providers: List<ProviderInfo>,
    activeProvider: ActiveProvider?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelect: (id: String, model: String) -> Unit,
) {
    if (providers.isEmpty()) {
        Text(
            text = "No providers enabled. Enable one in providers.json.",
            color = Color(0xFF666666),
            fontSize = 11.sp,
        )
        return
    }
    Box {
        AssistChip(
            onClick = onToggle,
            label = {
                Text(
                    text = activeProvider?.let { "${it.id} / ${it.model}" } ?: "Select provider",
                    fontSize = 11.sp,
                )
            },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(16.dp)) },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = Color(0xFF1F2A3F),
                labelColor = Color(0xFFE0E0E0),
            ),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onToggle,
        ) {
            for (p in providers) {
                for (model in p.models) {
                    DropdownMenuItem(
                        text = { Text("${p.label} / $model", fontSize = 12.sp) },
                        onClick = { onSelect(p.id, model) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val (bg, fg, role) = when (msg.role) {
        "user" -> Triple(Color(0xFF1F3F5F), Color(0xFFE0E0E0), "You")
        "assistant" -> Triple(Color(0xFF1F1F22), Color(0xFFE0E0E0), "Agent")
        "tool" -> Triple(Color(0xFF2A2A1A), Color(0xFFFFD180), "Tool")
        "system" -> Triple(Color(0xFF222222), Color(0xFF888888), "System")
        else -> Triple(Color(0xFF1A1A1A), Color(0xFFCCCCCC), msg.role)
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = bg),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(8.dp)) {
            Text(
                text = role,
                color = fg.copy(alpha = 0.6f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(2.dp))
            // Truncate tool outputs at ~1.5KB in the bubble.
            val display = (msg.content ?: "").let {
                if (msg.role == "tool" && it.length > 1500) it.take(1500) + "\n…[truncated]" else it
            }
            Text(
                text = display.ifEmpty { "(empty)" },
                color = fg,
                fontSize = 12.sp,
                fontFamily = if (msg.role == "tool" || msg.role == "assistant") FontFamily.Monospace else FontFamily.Default,
            )
        }
    }
}

@Composable
private fun ToolApprovalCard(
    approval: PendingApproval,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF3F2A1A)),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PlayArrow, null, tint = Color(0xFFFFB74D), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Tool call: ${approval.tool.name}", color = Color(0xFFFFB74D), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = approval.args.toString().take(500),
                color = Color(0xFFE0E0E0),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = onApprove,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) { Text("Approve", fontSize = 11.sp) }
                Button(
                    onClick = onReject,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350)),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) { Text("Reject", fontSize = 11.sp) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputRow(
    input: String,
    onInputChange: (String) -> Unit,
    isBusy: Boolean,
    onSend: () -> Unit,
    onAutoRun: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
) {
    Column {
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            placeholder = { Text("Ask the agent, or describe a goal for Auto Run…", fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = Color(0xFFE0E0E0)),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF1A1A1D),
                unfocusedContainerColor = Color(0xFF1A1A1D),
                focusedTextColor = Color(0xFFE0E0E0),
                unfocusedTextColor = Color(0xFFE0E0E0),
            ),
        )
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onSend,
                enabled = !isBusy && input.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isBusy) Color(0xFF444444) else Color(0xFF1F3F5F),
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(Icons.Default.Send, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Send", fontSize = 11.sp)
            }
            Button(
                onClick = onAutoRun,
                enabled = !isBusy,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A2A5F)),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(Icons.Default.AutoMode, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Auto Run", fontSize = 11.sp)
            }
            if (isBusy) {
                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350)),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Default.Stop, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Stop", fontSize = 11.sp)
                }
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFF888888),
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClear) {
                Text("Clear", fontSize = 11.sp, color = Color(0xFF888888))
            }
        }
    }
}

@Composable
private fun AutoRunDialog(
    goal: String,
    onGoalChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Auto Run") },
        text = {
            Column {
                Text(
                    text = "Give the agent a goal. It will keep working — calling tools, " +
                        "approving safe ones automatically, and looping — until it says the goal " +
                        "is met, or the iteration cap (15) is reached.",
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = goal,
                    onValueChange = onGoalChange,
                    placeholder = { Text("e.g. Add a logout button to the Settings screen", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 4,
                )
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Start") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// Convenience alias since Material3 doesn't have a simple TextButton in this scope
@Composable
private fun TextButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) { content() }
}
