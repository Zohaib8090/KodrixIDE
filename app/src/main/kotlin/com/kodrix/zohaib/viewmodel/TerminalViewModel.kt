package com.kodrix.zohaib.viewmodel

import android.app.Application
import android.util.Log
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.kodrix.zohaib.beta.BetaFlags
import com.kodrix.zohaib.bridge.GitBridge
import com.kodrix.zohaib.bridge.PtyBridge
import com.kodrix.zohaib.bridge.VersionChecker
import com.kodrix.zohaib.bridge.ZipUtils
import com.kodrix.zohaib.model.TerminalInstance
import com.kodrix.zohaib.lsp.LspClient
import com.kodrix.zohaib.lsp.PublishDiagnosticsParams
import com.kodrix.zohaib.lsp.Diagnostic
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableStateListOf
import android.content.Intent
import android.os.Build
import com.kodrix.zohaib.service.KodrixService
import org.json.JSONObject
import org.json.JSONArray

data class IDEProblem(
    val file: java.io.File,
    val line: Int,
    val column: Int,
    val message: String,
    val severity: String // ERROR, WARNING
)

data class GitCommit(
    val hash: String,
    val message: String,
    val author: String,
    val date: String
)

data class GitChange(
    val path: String,
    val status: String, // M, A, D, ??
    val isStaged: Boolean
)

data class NpmPackage(
    val name: String,
    val version: String,
    val description: String,
    val author: String,
    val date: String
)

data class ForwardedPort(val port: Int, val url: String, val process: Process, val pid: Long = -1L)

class TerminalViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("kodrix_settings", android.content.Context.MODE_PRIVATE)
    // Node/git version strings — populated by VersionChecker, not hardcoded.
    // Start as "Checking…" so the UI shows something while VersionChecker runs.
    private val _nodeVersion = MutableStateFlow("Checking…")
    val nodeVersion = _nodeVersion.asStateFlow()
    private val _gitVersion = MutableStateFlow("2.x (bundled)")
    val gitVersion = _gitVersion.asStateFlow()
    private val _instances = MutableStateFlow<List<TerminalInstance>>(emptyList())
    val instances = _instances.asStateFlow()

    private fun manageBackgroundService() {
        val hasTerminals = _instances.value.isNotEmpty()
        val isInstalling = _installingIds.value.isNotEmpty()
        val isUpdating = _isUpdatingBinaries.value
        
        if (hasTerminals || isInstalling || isUpdating) {
            val intent = Intent(getApplication(), KodrixService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getApplication<Application>().startForegroundService(intent)
            } else {
                getApplication<Application>().startService(intent)
            }
        } else {
            val intent = Intent(getApplication(), KodrixService::class.java)
            getApplication<Application>().stopService(intent)
        }
    }
    private val _activeInstanceIndex = MutableStateFlow(0)
    val activeInstanceIndex = _activeInstanceIndex.asStateFlow()
    private val _isReady = MutableStateFlow(false)
    val isReady = _isReady.asStateFlow()
    private val _isCtrlActive = MutableStateFlow(false)
    val isCtrlActive = _isCtrlActive.asStateFlow()

    private val _installingIds = MutableStateFlow<Set<String>>(emptySet())
    val installingIds = _installingIds.asStateFlow()

    private val _installingProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val installingProgress = _installingProgress.asStateFlow()

    // ── LSP State ─────────────────────────────────────────────────────────────
    private val activeLSPs = mutableMapOf<String, LspClient>() // key = file extension
    private val _lspDiagnostics = MutableStateFlow<List<Diagnostic>>(emptyList())
    val lspDiagnostics = _lspDiagnostics.asStateFlow()
    private val _completionItems = MutableStateFlow<List<com.kodrix.zohaib.lsp.CompletionItem>>(emptyList())
    val completionItems = _completionItems.asStateFlow()
    private var completionJob: kotlinx.coroutines.Job? = null
    private var completionDebounceJob: Job? = null
    private var lspDocVersion = 0
    private var isInstallingCpp = false

    private val aiManager = com.kodrix.zohaib.ai.AIBackendManager(application)
    val binaryManager = com.kodrix.zohaib.bridge.BinaryManager(application)
    val agentOrchestrator = com.kodrix.zohaib.ai.AgentOrchestrator(aiManager, binaryManager)
    
    private val _aiChatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val aiChatMessages = _aiChatMessages.asStateFlow()
    private val _isAiThinking = MutableStateFlow(false)
    val isAiThinking = _isAiThinking.asStateFlow()

    data class ChatMessage(val role: String, val content: String, val timestamp: Long = System.currentTimeMillis())

    data class ChatSession(
        val id: String = java.util.UUID.randomUUID().toString(),
        var title: String = "New Chat",
        val timestamp: Long = System.currentTimeMillis(),
        var messages: List<ChatMessage> = emptyList()
    )

    private val _chatSessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val chatSessions = _chatSessions.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId = _activeSessionId.asStateFlow()

    private val _showThirdPartyDialog = MutableStateFlow(false)
    val showThirdPartyDialog = _showThirdPartyDialog.asStateFlow()

    private fun saveAiHistory() {
        try {
            val file = java.io.File(getApplication<Application>().filesDir, "ai_history_v2.json")
            val array = org.json.JSONArray()
            _chatSessions.value.forEach { session ->
                val sessionObj = org.json.JSONObject()
                sessionObj.put("id", session.id)
                sessionObj.put("title", session.title)
                sessionObj.put("timestamp", session.timestamp)
                val msgArray = org.json.JSONArray()
                session.messages.forEach { msg ->
                    val msgObj = org.json.JSONObject()
                    msgObj.put("role", msg.role)
                    msgObj.put("content", msg.content)
                    msgObj.put("timestamp", msg.timestamp)
                    msgArray.put(msgObj)
                }
                sessionObj.put("messages", msgArray)
                array.put(sessionObj)
            }
            file.writeText(array.toString())
        } catch (e: Exception) {
            android.util.Log.e("TerminalViewModel", "Failed to save AI history", e)
        }
    }

    private fun loadAiHistory() {
        try {
            val fileV2 = java.io.File(getApplication<Application>().filesDir, "ai_history_v2.json")
            if (fileV2.exists()) {
                val array = org.json.JSONArray(fileV2.readText())
                val list = mutableListOf<ChatSession>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val msgArray = obj.getJSONArray("messages")
                    val msgs = mutableListOf<ChatMessage>()
                    for (j in 0 until msgArray.length()) {
                        val msgObj = msgArray.getJSONObject(j)
                        msgs.add(ChatMessage(
                            role = msgObj.getString("role"),
                            content = msgObj.getString("content"),
                            timestamp = msgObj.optLong("timestamp", System.currentTimeMillis())
                        ))
                    }
                    list.add(ChatSession(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        timestamp = obj.getLong("timestamp"),
                        messages = msgs
                    ))
                }
                _chatSessions.value = list
                
                if (list.isNotEmpty()) {
                    val latest = list.maxByOrNull { it.timestamp }
                    if (latest != null) {
                        _activeSessionId.value = latest.id
                        _aiChatMessages.value = latest.messages
                    }
                } else {
                    createNewSession()
                }
            } else {
                // Try migrating old ai_history.json
                val fileV1 = java.io.File(getApplication<Application>().filesDir, "ai_history.json")
                if (fileV1.exists()) {
                    val array = org.json.JSONArray(fileV1.readText())
                    val msgs = mutableListOf<ChatMessage>()
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        msgs.add(ChatMessage(
                            role = obj.getString("role"),
                            content = obj.getString("content"),
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                        ))
                    }
                    if (msgs.isNotEmpty()) {
                        val session = ChatSession(
                            title = msgs.firstOrNull { it.role == "user" }?.content?.take(30)?.replace("\n", " ") ?: "Migrated Chat",
                            messages = msgs
                        )
                        _chatSessions.value = listOf(session)
                        _activeSessionId.value = session.id
                        _aiChatMessages.value = msgs
                        saveAiHistory()
                    } else {
                        createNewSession()
                    }
                    fileV1.delete()
                } else {
                    createNewSession()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("TerminalViewModel", "Failed to load AI history", e)
            createNewSession()
        }
    }

    fun createNewSession() {
        val newSession = ChatSession()
        _chatSessions.value = _chatSessions.value + newSession
        _activeSessionId.value = newSession.id
        _aiChatMessages.value = emptyList()
        saveAiHistory()
        aiManager.resetChat()
    }

    fun switchSession(sessionId: String) {
        val session = _chatSessions.value.find { it.id == sessionId }
        if (session != null) {
            _activeSessionId.value = sessionId
            _aiChatMessages.value = session.messages
            aiManager.resetChat()
        }
    }

    fun clearAiChat() {
        createNewSession()
    }

    fun deleteChatSession(sessionId: String) {
        val updatedSessions = _chatSessions.value.filter { it.id != sessionId }
        _chatSessions.value = updatedSessions
        
        // If we deleted the active session, switch to the most recent one or create a new one
        if (_activeSessionId.value == sessionId) {
            if (updatedSessions.isNotEmpty()) {
                val latest = updatedSessions.maxByOrNull { it.timestamp }
                latest?.let { switchSession(it.id) }
            } else {
                createNewSession()
            }
        }
        saveAiHistory()
    }
    
    private fun updateActiveSession(messages: List<ChatMessage>) {
        val currentId = _activeSessionId.value ?: return
        val updatedSessions = _chatSessions.value.map {
            if (it.id == currentId) {
                var newTitle = it.title
                if (it.title == "New Chat" && messages.isNotEmpty()) {
                    val firstUser = messages.firstOrNull { m -> m.role == "user" }
                    if (firstUser != null) {
                        newTitle = firstUser.content.take(30).replace("\n", " ") + "..."
                    }
                }
                it.copy(messages = messages, title = newTitle)
            } else it
        }
        _chatSessions.value = updatedSessions
        saveAiHistory()
    }

    init {
        loadAiHistory()
        
        
        // Show third-party dialog on first run
        if (!prefs.getBoolean("third_party_accepted", false)) {
            _showThirdPartyDialog.value = true
        }
        
        // AI Status Notifications (Custom User Messages)
        viewModelScope.launch {
            aiManager.status.collect { status ->
                val message = when {
                    status.contains("Native Launch") -> "Starting AI system..."
                    status.contains("Ready") -> "AI system started"
                    status.contains("Error") -> "Failed to start"
                    else -> null
                }
                if (message != null) {
                    android.widget.Toast.makeText(application, message, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Keep _nodeVersion and _gitVersion in sync with VersionChecker verified results.
        viewModelScope.launch {
            binaryManager.verifiedVersions.collect { verified ->
                // Node
                val nodeVerified = verified["node"]
                if (nodeVerified != null) {
                    _nodeVersion.value = if (nodeVerified.isVerified) {
                        nodeVerified.version
                    } else {
                        "${nodeVerified.version} (unverified)"
                    }
                } else {
                    // VersionChecker hasn't run yet — use the active label from BinaryManager if available
                    val active = binaryManager.availableVersions.value.find { it.isActive && it.tool == "node" }
                    _nodeVersion.value = if (active != null && active.version != "25.8.2") {
                        active.version
                    } else {
                        "Checking…"
                    }
                }

                // Git
                val gitVerified = verified["git"]
                if (gitVerified != null) {
                    _gitVersion.value = if (gitVerified.isVerified) {
                        gitVerified.version
                    } else {
                        "${gitVerified.version} (unverified)"
                    }
                } else {
                    _gitVersion.value = "Checking…"
                }

                // Python
                val pythonVerified = verified["python"]
                if (pythonVerified != null && pythonVerified.isVerified) {
                    val pythonDir = findPythonInstallDir(application.filesDir)
                    if (pythonDir != null) {
                        val pylspBin = java.io.File(pythonDir, "bin/pylsp")
                        if (!pylspBin.exists()) {
                            Log.d("Kodrix", "Python verified, but pylsp not found. Triggering auto-install...")
                            installPylspIfNeeded(application.filesDir, null)
                        }
                    }
                }
            }
        }

        // Dynamically run version check on startup for the active binaries instead of hardcoding
        viewModelScope.launch {
            // Node check
            val nodeBin = findBinary(application.filesDir, "node")
            val activeNodeVer = binaryManager.getActiveVersion("node") ?: "25.8.2"
            if (nodeBin != null) {
                VersionChecker.check("node", expectedVersion = activeNodeVer, binaryPath = nodeBin.absolutePath, context = application)
            } else {
                val bundledNode = java.io.File(application.filesDir, "usr/bin/node")
                if (bundledNode.exists()) {
                    VersionChecker.check("node", expectedVersion = "25.8.2", binaryPath = bundledNode.absolutePath, context = application)
                }
            }

            // Git check
            val gitBin = findBinary(application.filesDir, "git")
            val activeGitVer = binaryManager.getActiveVersion("git") ?: "2.34.0"
            if (gitBin != null) {
                VersionChecker.check("git", expectedVersion = activeGitVer, binaryPath = gitBin.absolutePath, context = application)
            } else {
                val bundledGit = java.io.File(application.filesDir, "usr/bin/git")
                if (bundledGit.exists()) {
                    VersionChecker.check("git", expectedVersion = "2.34.0", binaryPath = bundledGit.absolutePath, context = application)
                }
            }

            // Python check
            val pythonDir = findPythonInstallDir(application.filesDir)
            val activePythonVer = binaryManager.getActiveVersion("python") ?: "3.13.13"
            if (pythonDir != null) {
                val pythonBin = java.io.File(pythonDir, "bin/python3")
                if (pythonBin.exists()) {
                    VersionChecker.check("python", expectedVersion = activePythonVer, binaryPath = pythonBin.absolutePath, context = application)
                }
            }
        }
    }

    fun acceptThirdPartyServices() {
        prefs.edit().putBoolean("third_party_accepted", true).apply()
        _showThirdPartyDialog.value = false
    }

    fun sendAiMessage(prompt: String, attachedFileName: String? = null, attachedFileContent: String? = null, attachedFileUri: android.net.Uri? = null) {
        val fullPrompt = if (attachedFileName != null && attachedFileContent != null) {
            "Context from $attachedFileName:\n```\n$attachedFileContent\n```\n\n$prompt"
        } else if (attachedFileName != null && attachedFileUri != null) {
            "Attached image: $attachedFileName\n\n$prompt"
        } else prompt

        val userMsg = ChatMessage("user", prompt)
        _aiChatMessages.value = _aiChatMessages.value + userMsg
        updateActiveSession(_aiChatMessages.value)
        _isAiThinking.value = true

        viewModelScope.launch {
            val response = aiManager.ask(fullPrompt, attachedFileUri)
            _isAiThinking.value = false
            if (response == "NEEDS_LOGIN") {
                _aiChatMessages.value = _aiChatMessages.value + ChatMessage("assistant", "⚠️ Please log in to Gemini. I've opened the login window for you.")
                updateActiveSession(_aiChatMessages.value)
                val intent = android.content.Intent(getApplication(), com.kodrix.zohaib.ai.GeminiLoginActivity::class.java).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                getApplication<Application>().startActivity(intent)
            } else if (response != null) {
                _aiChatMessages.value = _aiChatMessages.value + ChatMessage("assistant", response)
                updateActiveSession(_aiChatMessages.value)
            } else {
                _aiChatMessages.value = _aiChatMessages.value + ChatMessage("assistant", "❌ Error: Could not reach the AI engine.")
                updateActiveSession(_aiChatMessages.value)
            }
        }
    }

    private val _showLineNumbers = MutableStateFlow(prefs.getBoolean("show_line_numbers", true))
    val showLineNumbers = _showLineNumbers.asStateFlow()

    fun updateLineNumbers(show: Boolean) {
        _showLineNumbers.value = show
        prefs.edit().putBoolean("show_line_numbers", show).apply()
    }

    // ── Safe Mode (Emergency Rollback) ─────────────────────────────────────────
    private val _isSafeMode = MutableStateFlow(prefs.getBoolean("safe_mode", false))
    val isSafeMode = _isSafeMode.asStateFlow()

    /**
     * Enables or disables Safe Mode.
     *
     * Safe Mode routes the terminal's $PATH to usr/bin_safe/ — a static directory of
     * bundled-only wrappers that is NEVER touched by WrapperManager. This gives the user
     * a guaranteed recovery path if a downloaded runtime corrupts the dynamic wrappers.
     *
     * The flag is written as a physical file (filesDir/safe_mode) so PtyBridge can
     * read it with a shell `[ -f ... ]` check at terminal session startup — no JVM call needed.
     */
    fun setSafeMode(enabled: Boolean) {
        _isSafeMode.value = enabled
        prefs.edit().putBoolean("safe_mode", enabled).apply()
        val flagFile = java.io.File(getApplication<Application>().filesDir, "safe_mode")
        if (enabled) {
            flagFile.writeText("1")
        } else {
            flagFile.takeIf { it.exists() }?.delete()
        }
    }

    // ── Beta Mode ─────────────────────────────────────────────────────────────
    //
    // Unlocks features listed in BetaFlags.kt that are under active testing.
    // Off by default — users opt in from Settings → Developer → Beta Mode.

    private val _isBetaMode = MutableStateFlow(prefs.getBoolean("beta_mode", false))
    val isBetaMode = _isBetaMode.asStateFlow()

    /** Returns true if [flag] is enabled AND the user has turned on Beta Mode. */
    fun isBetaFeatureEnabled(flag: Boolean): Boolean = _isBetaMode.value && flag

    fun setBetaMode(enabled: Boolean) {
        _isBetaMode.value = enabled
        prefs.edit().putBoolean("beta_mode", enabled).apply()
        android.util.Log.i("BetaMode", "Beta Mode ${if (enabled) "ENABLED" else "DISABLED"}")
    }

    private fun getLanguageId(extension: String) = when (extension.lowercase()) {
        "html", "htm" -> "html"
        "css" -> "css"
        "json" -> "json"
        "js", "javascript" -> "javascript"
        "ts", "typescript" -> "typescript"
        // Native language servers (clangd / pylsp) — handled by startNativeLsp()
        "c" -> "c"
        "cpp", "cc", "cxx", "h", "hpp" -> "cpp"
        "py" -> "python"
        else -> null
    }

    private fun isLspSupported(extension: String) = getLanguageId(extension) != null

    private fun startLsp(file: java.io.File) {
        val ext = file.extension.lowercase()
        val langId = getLanguageId(ext) ?: return
        if (activeLSPs.containsKey(ext)) return // already running

        // Route native-binary LSPs (clangd for C/C++, pylsp for Python) away from the Node path
        if (langId == "c" || langId == "cpp" || langId == "python") {
            startNativeLsp(langId, ext, file)
            return
        }

        val proj = _activeProject.value ?: return
        val projDir = java.io.File(projectsRoot, proj)
        val filesDir = getApplication<Application>().filesDir.absolutePath
        val nativeLibPath = getApplication<Application>().applicationInfo.nativeLibraryDir
        val libLinksDir = java.io.File(filesDir, "lib").absolutePath

        // vscode-langservers-extracted uses "vscode-*" prefix; fall back to legacy names
        val binaryNames = when (langId) {
            "html" -> listOf("vscode-html-language-server", "html-languageserver")
            "css"  -> listOf("vscode-css-language-server",  "css-languageserver")
            "json" -> listOf("vscode-json-language-server", "json-languageserver")
            "javascript", "typescript" -> listOf("typescript-language-server")
            else   -> listOf("$langId-languageserver")
        }

        // Check global lsp dir first, then project node_modules — try all name variants
        val lspBin = binaryNames.flatMap { name ->
            listOf(
                java.io.File(filesDir, "lsp/node_modules/.bin/$name"),
                java.io.File(projDir.absolutePath, "node_modules/.bin/$name")
            )
        }.firstOrNull { it.exists() }

        Log.d("Kodrix", "LSP: Searching for ${binaryNames.joinToString("/")} — found=${lspBin?.absolutePath}")

        if (lspBin == null) {
            Log.w("Kodrix", "LSP binary not found for $langId, triggering on-demand install...")
            installSpecificLSP(langId, file)
            return
        }

        // Ensure the bin file is executable
        lspBin.setExecutable(true)

        // Use findBinary to locate node dynamically
        val nodeBin = findBinary(getApplication<Application>().filesDir, "node")?.absolutePath
            ?: run {
                Log.e("Kodrix", "LSP: node binary not found, cannot start LSP")
                return
            }
        Log.d("Kodrix", "LSP: nodeBin=$nodeBin")

        val dnsOverridePath = java.io.File(filesDir, "dns-override.js").absolutePath

        // Inject the active Node.js version paths for LSP execution
        val activeNodeVersion = binaryManager.getActiveVersion("node")
        val activeNodeBinDir = if (activeNodeVersion != null)
            java.io.File(filesDir, "versions/node/$activeNodeVersion/bin").absolutePath else null
        val activeNodeLibDir = if (activeNodeVersion != null)
            java.io.File(filesDir, "versions/node/$activeNodeVersion/lib").absolutePath else null

        val ldPath = buildString {
            if (activeNodeLibDir != null) append("$activeNodeLibDir:")
            append("$libLinksDir")
        }
        val pathEnv = buildString {
            if (activeNodeBinDir != null) append("$activeNodeBinDir:")
            append("$filesDir/usr/bin:$filesDir/bin:$nativeLibPath:/system/bin:/system/xbin")
        }
        val env = mapOf(
            "HOME" to filesDir,
            "USER" to "kodrix",
            "TMPDIR" to java.io.File(filesDir, "tmp").apply { mkdirs() }.absolutePath,
            "LD_LIBRARY_PATH" to ldPath,
            "NODE_PATH" to "$filesDir/lsp/node_modules:$filesDir/npm_pkg/node_modules",
            "PATH" to pathEnv,
            "OPENSSL_CONF" to "/dev/null",
            "NODE_OPTIONS" to "--require $dnsOverridePath",
            "APP_FILES_DIR" to filesDir
        )

        // The npm .bin entries are shell scripts (#!/usr/bin/env node).
        // We run them via sh so the shebang is interpreted, and our PATH
        // ensures the sh shebang resolves 'node' from filesDir/bin/node.
        val lspBinPath = lspBin.absolutePath
        val cmd = "export PATH='$filesDir/bin:$nativeLibPath:/system/bin:/system/xbin'; " +
                  "export LD_LIBRARY_PATH='$libLinksDir'; " +
                  "export OPENSSL_CONF=/dev/null; " +
                  "export NODE_OPTIONS='--require $dnsOverridePath'; " +
                  "exec '$nodeBin' '$lspBinPath' --stdio"

        Log.d("Kodrix", "LSP command: $cmd")

        val client = LspClient(
            command = listOf("/system/bin/sh", "-c", cmd),
            workingDir = projDir,
            env = env
        )

        client.onDiagnosticsReceived = { params ->
            viewModelScope.launch(Dispatchers.Main) {
                _lspDiagnostics.value = params.diagnostics
                Log.d("Kodrix", "LSP Diagnostics: ${params.diagnostics.size} issues")
            }
        }

        val friendlyLang = when (langId) {
            "html", "htm" -> "HTML"
            "css" -> "CSS"
            "json" -> "JSON"
            "javascript" -> "JavaScript"
            "typescript" -> "TypeScript"
            else -> langId.uppercase()
        }

        viewModelScope.launch(Dispatchers.Main) {
            android.widget.Toast.makeText(
                getApplication(), "🔄 Starting $friendlyLang Language Server...",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        client.start()
        activeLSPs[ext] = client

        // Send initialize + didOpen
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val initParams = com.kodrix.zohaib.lsp.InitializeParams(
                    processId = android.os.Process.myPid(),
                    rootUri = "file://${projDir.absolutePath}"
                )
                val resp = client.request("initialize", initParams)
                if (resp == null) {
                    Log.e("Kodrix", "LSP: initialize request TIMED OUT for .$ext")
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(getApplication(), "LSP failed to initialize (Timeout)", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Log.d("Kodrix", "LSP: initialize response received for .$ext")
                }
                client.notify("initialized", emptyMap<String, String>())

                val text = file.readText()
                val openParams = com.kodrix.zohaib.lsp.DidOpenTextDocumentParams(
                    textDocument = com.kodrix.zohaib.lsp.TextDocumentItem(
                        uri = "file://${file.absolutePath}",
                        languageId = langId,
                        version = ++lspDocVersion,
                        text = text
                    )
                )
                client.notify("textDocument/didOpen", openParams)
                Log.d("Kodrix", "LSP started and initialized for .$ext files")

                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        getApplication(), "✅ $friendlyLang Language Server ready!",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.e("Kodrix", "LSP initialization failed", e)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        getApplication(), "❌ $friendlyLang LSP failed to start",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    fun stopAllLsps() {
        activeLSPs.values.forEach { it.stop() }
        activeLSPs.clear()
        _lspDiagnostics.value = emptyList()
        _completionItems.value = emptyList() // Clear completions too
    }

    private fun installSpecificLSP(langId: String, fileToOpenAfter: java.io.File) {
        // Map language ID to npm package(s) needed
        val packages = when (langId) {
            "javascript", "typescript" -> "typescript typescript-language-server"
            "html", "css", "json" -> "vscode-langservers-extracted"
            "bash", "sh" -> "bash-language-server"
            else -> return
        }

        val friendlyName = when (langId) {
            "javascript", "typescript" -> "TypeScript Language Server"
            "html" -> "HTML Language Server"
            "css" -> "CSS Language Server"
            "json" -> "JSON Language Server"
            "bash", "sh" -> "Bash Language Server"
            else -> "$langId Language Server"
        }

        viewModelScope.launch(Dispatchers.IO) {
            val filesDir = getApplication<android.app.Application>().filesDir
            val lspDir = java.io.File(filesDir, "lsp")
            lspDir.mkdirs()

            val nativeLibPath = getApplication<android.app.Application>().applicationInfo.nativeLibraryDir
            val libLinksDir = java.io.File(filesDir, "lib").absolutePath
            val usrBinDir = "${filesDir.absolutePath}/usr/bin"
            val nodeBin = "$usrBinDir/node"
            val npmCli = "${filesDir.absolutePath}/npm_pkg/lib/node_modules/npm/bin/npm-cli.js"
            val dnsOverridePath = java.io.File(filesDir, "dns-override.js").absolutePath

            if (!java.io.File(nodeBin).exists() || !java.io.File(npmCli).exists()) {
                Log.e("Kodrix", "Node/NPM not found, cannot install $friendlyName")
                return@launch
            }

            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    getApplication(),
                    "Installing $friendlyName...",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }

            val cmd = listOf(
                "/system/bin/sh", "-c",
                "export PATH='$usrBinDir:${filesDir.absolutePath}/bin:$nativeLibPath:/system/bin:/system/xbin'; " +
                "export LD_LIBRARY_PATH='$libLinksDir'; " +
                "export OPENSSL_CONF=/dev/null; " +
                "export NODE_OPTIONS='--require $dnsOverridePath'; " +
                "exec '$nodeBin' '$npmCli' install $packages --no-fund --no-audit"
            )

            try {
                val pb = ProcessBuilder(cmd)
                pb.directory(lspDir)
                pb.environment()["HOME"] = filesDir.absolutePath
                pb.environment()["USER"] = "kodrix"
                pb.environment()["TMPDIR"] = java.io.File(filesDir, "tmp").apply { mkdirs() }.absolutePath
                pb.redirectErrorStream(true)

                val process = pb.start()
                process.inputStream.bufferedReader().forEachLine { Log.d("Kodrix", "LSP Install: $it") }
                val exitCode = process.waitFor()

                if (exitCode == 0) {
                    Log.d("Kodrix", "$friendlyName installed successfully")
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            getApplication(),
                            "$friendlyName ready! Re-opening file...",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        // Re-trigger openFile so LSP now starts properly
                        openFile(fileToOpenAfter)
                    }
                } else {
                    Log.e("Kodrix", "$friendlyName install failed with exit $exitCode")
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            getApplication(),
                            "Failed to install $friendlyName",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("Kodrix", "Failed to install $friendlyName", e)
            }
        }
    }

    // ── Native LSP (clangd for C/C++, pylsp for Python) ──────────────────────

    /**
     * Entry-point for C, C++, and Python LSP sessions.
     * Ensures the required binary (clangd / pylsp) is installed before starting
     * an [LspClient] connected to it.
     */
    private fun startNativeLsp(langId: String, ext: String, file: java.io.File) {
        viewModelScope.launch(Dispatchers.IO) {
            val filesDir = getApplication<android.app.Application>().filesDir
            val proj = _activeProject.value ?: return@launch
            val projDir = java.io.File(projectsRoot, proj)

            when (langId) {
                "c", "cpp" -> {
                    // Find clangd in the installed clang toolchain
                    val clangDir = findClangInstallDir(filesDir)
                    // Clang .deb packages extract into usr/ subdirectory (Termux layout)
                    val clangdBin = when {
                        clangDir == null -> null
                        java.io.File(clangDir, "usr/bin/clangd").exists() -> java.io.File(clangDir, "usr/bin/clangd")
                        java.io.File(clangDir, "bin/clangd").exists()     -> java.io.File(clangDir, "bin/clangd")
                        else -> null
                    }

                    if (clangdBin == null || !clangdBin.exists()) {
                        // Toolchain not installed — kick off on-demand download
                        installCppToolchain(filesDir, file)
                        return@launch
                    }

                    val binDir  = clangdBin.parentFile!!.absolutePath
                    val libDir  = java.io.File(clangDir!!, "usr/lib").takeIf { it.isDirectory }?.absolutePath
                               ?: java.io.File(clangDir, "lib").absolutePath
                    val sysrootInclude = java.io.File(clangDir, "sysroot/usr/include").absolutePath
                    val ldPath = "$libDir:${java.io.File(filesDir, "lib").absolutePath}"
                    val nativeLibPath = getApplication<android.app.Application>().applicationInfo.nativeLibraryDir
                    val cmd = "export PATH='$binDir:$filesDir/bin:$filesDir/usr/bin:$nativeLibPath:/system/bin:/system/xbin'; " +
                              "export LD_LIBRARY_PATH='$ldPath'; " +
                              "export CPATH='$sysrootInclude'; " +
                              "exec '${clangdBin.absolutePath}' --stdio"

                    val command = listOf("/system/bin/sh", "-c", cmd)
                    val env = mapOf(
                        "LD_LIBRARY_PATH" to ldPath,
                        "CPATH" to sysrootInclude,
                        "HOME" to filesDir.absolutePath,
                        "TMPDIR" to java.io.File(filesDir, "tmp").apply { mkdirs() }.absolutePath
                    )
                    launchLspClient(ext, langId, file, projDir, command, env)
                }
                "python" -> {
                    val pythonDir = findPythonInstallDir(filesDir)
                    val pylspBin = if (pythonDir != null) java.io.File(pythonDir, "bin/pylsp") else null

                    if (pythonDir == null || pylspBin == null || !pylspBin.exists()) {
                        installPylspIfNeeded(filesDir, file)
                        return@launch
                    }

                    val pythonLibDir = java.io.File(pythonDir, "lib").absolutePath
                    val pythonBin = java.io.File(pythonDir, "bin/python3").absolutePath
                    val nativeLibPath = getApplication<android.app.Application>().applicationInfo.nativeLibraryDir
                    val cmd = "export PATH='${pythonDir.absolutePath}/bin:$filesDir/bin:$filesDir/usr/bin:$nativeLibPath:/system/bin:/system/xbin'; " +
                              "export LD_LIBRARY_PATH='$pythonLibDir'; " +
                              "export PYTHONHOME='${pythonDir.absolutePath}'; " +
                              "exec '$pythonBin' '${pylspBin.absolutePath}'"

                    val command = listOf("/system/bin/sh", "-c", cmd)
                    val env = mapOf(
                        "LD_LIBRARY_PATH" to pythonLibDir,
                        "PYTHONHOME" to pythonDir.absolutePath,
                        "HOME" to filesDir.absolutePath,
                        "TMPDIR" to java.io.File(filesDir, "tmp").apply { mkdirs() }.absolutePath
                    )
                    launchLspClient(ext, langId, file, projDir, command, env)
                }
            }
        }
    }

    /** Starts an [LspClient] with the given command + env and sends initialize/didOpen. */
    private suspend fun launchLspClient(
        ext: String,
        langId: String,
        file: java.io.File,
        projDir: java.io.File,
        command: List<String>,
        env: Map<String, String>
    ) {
        val client = LspClient(command = command, workingDir = projDir, env = env)
        client.onDiagnosticsReceived = { params ->
            viewModelScope.launch(Dispatchers.Main) {
                _lspDiagnostics.value = params.diagnostics
            }
        }
        client.start()
        activeLSPs[ext] = client

        val friendlyLang = when (langId) {
            "c"      -> "C"
            "cpp"    -> "C++"
            "python" -> "Python"
            else     -> langId.uppercase()
        }
        withContext(Dispatchers.Main) {
            android.widget.Toast.makeText(
                getApplication(), "🔄 Starting $friendlyLang Language Server...",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        try {
            val initParams = com.kodrix.zohaib.lsp.InitializeParams(
                processId = android.os.Process.myPid(),
                rootUri = "file://${projDir.absolutePath}"
            )
            client.request("initialize", initParams)
            client.notify("initialized", emptyMap<String, String>())

            val text = file.readText()
            val openParams = com.kodrix.zohaib.lsp.DidOpenTextDocumentParams(
                textDocument = com.kodrix.zohaib.lsp.TextDocumentItem(
                    uri = "file://${file.absolutePath}",
                    languageId = langId,
                    version = ++lspDocVersion,
                    text = text
                )
            )
            client.notify("textDocument/didOpen", openParams)
            Log.d("Kodrix", "Native LSP ($langId) started for ${file.name}")

            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    getApplication(), "✅ $friendlyLang Language Server ready!",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        } catch (e: Exception) {
            Log.e("Kodrix", "Native LSP ($langId) initialization failed", e)
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    getApplication(), "❌ $friendlyLang LSP failed to start",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** Returns the clang install dir (versions/clang/<version>) if any version is active. */
    private fun findClangInstallDir(filesDir: java.io.File): java.io.File? {
        val clangRoot = java.io.File(filesDir, "versions/clang")
        if (!clangRoot.isDirectory) return null
        return clangRoot.listFiles()
            ?.filter { it.isDirectory &&
                // clangd lives at usr/bin/clangd (Termux .deb layout after --strip-components=5)
                (java.io.File(it, "usr/bin/clangd").exists() ||
                 java.io.File(it, "bin/clangd").exists()) }
            ?.maxByOrNull { it.name }
    }

    /** Returns the Python install dir (versions/python/<version>) if any version is active. */
    private fun findPythonInstallDir(filesDir: java.io.File): java.io.File? {
        val pythonRoot = java.io.File(filesDir, "versions/python")
        if (!pythonRoot.isDirectory) return null
        return pythonRoot.listFiles()
            ?.filter { it.isDirectory && java.io.File(it, "bin/python3").exists() }
            ?.maxByOrNull { it.name }
    }

    // ── C/C++ Toolchain installer ─────────────────────────────────────────────

    /**
     * Downloads the Clang toolchain from official Termux package repositories and installs it
     * into [filesDir]/versions/clang/21.1.8/.
     *
     * Packages downloaded (for the active device ABI):
     *  - clang (compiler + clangd LSP binary)
     *  - libllvm (libLLVM.so, libclang-cpp.so — required at runtime)
     *  - libffi, zstd (libzstd.so), libxml2, liblzma — libLLVM.so dependencies
     *  - ndk-sysroot — C/C++ system headers (stdio.h, stdlib.h, …) and stub libraries
     *
     * Total download is approximately 120-150 MB. A progress Toast is shown every 10 s.
     *
     * After extraction the [BinaryManager] is notified so it can create shell wrappers for
     * clang, clang++, and clangd in usr/bin/.
     */
    private fun installCppToolchain(filesDir: java.io.File, fileToOpenAfter: java.io.File) {
        // Guard against concurrent installs (e.g. user switching C++ files mid-download)
        synchronized(this) {
            if (isInstallingCpp) {
                Log.d("Kodrix", "installCppToolchain: already in progress, ignoring duplicate request")
                return
            }
            isInstallingCpp = true
        }
        viewModelScope.launch(Dispatchers.IO) {
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "x86_64"
            val termuxAbi = when {
                abi.startsWith("arm64") -> "aarch64"
                abi.startsWith("armeabi") -> "arm"
                abi == "x86_64" -> "x86_64"
                abi == "x86" -> "i686"
                else -> "aarch64"
            }

            val BASE = "https://packages-cf.termux.dev/apt/termux-main/pool/main"
            val packages = listOf(
                "$BASE/c/clang/clang_21.1.8-2_${termuxAbi}.deb",
                "$BASE/libl/libllvm/libllvm_21.1.8-2_${termuxAbi}.deb",
                "$BASE/libf/libffi/libffi_3.5.2_${termuxAbi}.deb",
                "$BASE/z/zstd/zstd_1.5.7-1_${termuxAbi}.deb",
                "$BASE/libx/libxml2/libxml2_2.15.3_${termuxAbi}.deb",
                "$BASE/libl/liblzma/liblzma_5.8.3_${termuxAbi}.deb",
                "$BASE/n/ndk-sysroot/ndk-sysroot_29-2_${termuxAbi}.deb"
            )

            val installRoot = java.io.File(filesDir, "versions/clang/21.1.8")
            val tmpDir = java.io.File(filesDir, "tmp/clang_install").also {
                it.deleteRecursively(); it.mkdirs()
            }

            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    getApplication(),
                    "⚙️ Downloading C/C++ toolchain (~150 MB) — this may take a minute...",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }

            try {
                binaryManager.showProgressNotification("clang", "21.1.8", "downloading", 0f)
                for ((index, url) in packages.withIndex()) {
                    val debName = url.substringAfterLast("/")
                    Log.d("Kodrix", "CppInstall: downloading $debName")
                    val debFile = java.io.File(tmpDir, debName)

                    // Download .deb with redirect following and progress reporting
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 10000
                    conn.readTimeout = 30000
                    var finalConn = conn
                    var redirects = 0
                    while (redirects < 5) {
                        val code = finalConn.responseCode
                        if (code in 301..308) {
                            val loc = finalConn.getHeaderField("Location") ?: break
                            finalConn.disconnect()
                            finalConn = java.net.URL(loc).openConnection() as java.net.HttpURLConnection
                            finalConn.connectTimeout = 10000
                            finalConn.readTimeout = 30000
                            redirects++
                        } else {
                            break
                        }
                    }

                    val totalBytes = finalConn.contentLength.toLong()
                    var downloaded = 0L
                    finalConn.inputStream.use { input ->
                        debFile.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var read = input.read(buffer)
                            var lastUpdate = System.currentTimeMillis()
                            while (read != -1) {
                                output.write(buffer, 0, read)
                                downloaded += read
                                val now = System.currentTimeMillis()
                                if (now - lastUpdate > 300) {
                                    val fileProgress = if (totalBytes > 0) downloaded.toFloat() / totalBytes else 0f
                                    val overallProgress = (index + fileProgress) / packages.size
                                    binaryManager.showProgressNotification("clang", "21.1.8", "downloading", overallProgress)
                                    lastUpdate = now
                                }
                                read = input.read(buffer)
                            }
                        }
                    }

                    // Step 1: Extract the raw data.tar.xz member from the .deb (ar archive)
                    binaryManager.showProgressNotification("clang", "21.1.8", "extracting $debName", (index + 0.9f) / packages.size)
                    val dataTarXz = java.io.File(tmpDir, "${debName}.data.tar.xz")
                    extractDataTarFromDeb(debFile, dataTarXz)

                    // Step 2: Decompress .xz → plain .tar in pure Java (no system xz needed)
                    val dataTar = java.io.File(tmpDir, "${debName}.data.tar")
                    java.io.FileInputStream(dataTarXz).use { fis ->
                        org.tukaani.xz.XZInputStream(fis).use { xzis ->
                            java.io.FileOutputStream(dataTar).use { fos ->
                                xzis.copyTo(fos)
                            }
                        }
                    }
                    dataTarXz.delete()

                    // Step 3: Unpack the plain tar (no -J flag needed; already decompressed)
                    binaryManager.showProgressNotification("clang", "21.1.8", "unpacking $debName", (index + 0.95f) / packages.size)
                    val stagingDir = java.io.File(tmpDir, "${debName}_staging").also { it.mkdirs() }
                    val tarProc = ProcessBuilder(
                        "/system/bin/tar", "-xf", dataTar.absolutePath,
                        "-C", stagingDir.absolutePath,
                        "--strip-components=5"   // removes ./data/data/com.termux/files/
                    ).redirectErrorStream(true).start()
                    val tarOutput = tarProc.inputStream.bufferedReader().readText()
                    val tarExit = tarProc.waitFor()
                    if (tarExit != 0) {
                        throw RuntimeException("tar failed (exit $tarExit) for $debName: $tarOutput")
                    }

                    // Step 4: Merge staging → installRoot
                    copyDirContents(stagingDir, installRoot)

                    debFile.delete()
                    dataTar.delete()
                    stagingDir.deleteRecursively()

                    Log.d("Kodrix", "CppInstall: installed $debName")
                }

                binaryManager.showProgressNotification("clang", "21.1.8", "verifying", 1.0f)

                // Move ndk-sysroot's headers and libs into installRoot/sysroot/
                // We intentionally do NOT rename the entire usr/ directory because
                // clangd (and other clang binaries) live at usr/bin/ and must stay there.
                val sysrootDest = java.io.File(installRoot, "sysroot").also { it.mkdirs() }
                val ndkInclude = java.io.File(installRoot, "usr/include")
                val ndkLib     = java.io.File(installRoot, "usr/lib")
                if (ndkInclude.isDirectory) ndkInclude.renameTo(java.io.File(sysrootDest, "usr/include").also { it.parentFile?.mkdirs() })
                if (ndkLib.isDirectory)     ndkLib.renameTo(java.io.File(sysrootDest, "usr/lib").also { it.parentFile?.mkdirs() })

                // Make binaries executable
                java.io.File(installRoot, "usr/bin").listFiles()?.forEach { it.setExecutable(true) }
                java.io.File(installRoot, "bin").listFiles()?.forEach { it.setExecutable(true) }

                // Mark clang installed without running the binary — Clang is a cross-compiled
                // Termux/Bionic binary and cannot self-execute inside the Android app sandbox,
                // so VersionChecker would always fail and roll back the installation.
                binaryManager.markToolInstalled("clang", "21.1.8")

                tmpDir.deleteRecursively()
                binaryManager.cancelProgressNotification()
                binaryManager.showCompletionNotification("clang", "21.1.8", true)

                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        getApplication(),
                        "✅ C/C++ toolchain installed! Opening file...",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }

                // Now start the LSP for the file that triggered the install
                val ext = fileToOpenAfter.extension.lowercase()
                val langId = getLanguageId(ext) ?: return@launch
                startNativeLsp(langId, ext, fileToOpenAfter)

            } catch (e: Exception) {
                Log.e("Kodrix", "CppInstall: failed", e)
                binaryManager.cancelProgressNotification()
                binaryManager.showCompletionNotification("clang", "21.1.8", false, e.message)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        getApplication(),
                        "❌ C/C++ toolchain install failed: ${e.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                tmpDir.deleteRecursively()
            } finally {
                // Always release the guard so a retry is possible after failure
                synchronized(this@TerminalViewModel) { isInstallingCpp = false }
            }
        }
    }

    /**
     * Extracts the [data.tar.*] member from a Debian .deb file (which is an ar archive)
     * without relying on any external `ar` binary — pure Kotlin byte-level parsing.
     */
    private fun extractDataTarFromDeb(debFile: java.io.File, outputFile: java.io.File) {
        debFile.inputStream().buffered().use { input ->
            // Verify ar magic
            val magic = ByteArray(8)
            input.read(magic)
            check(String(magic) == "!<arch>\n") { "Not a valid .deb (ar) file: ${debFile.name}" }

            while (true) {
                val header = ByteArray(60)
                val read = input.read(header)
                if (read < 60) throw java.io.EOFException("Unexpected end of ar archive")

                val memberName = String(header, 0, 16).trim()
                val memberSize = String(header, 48, 10).trim().toLong()

                if (memberName.startsWith("data.tar")) {
                    // Found — stream it out to outputFile
                    var remaining = memberSize
                    val buf = ByteArray(65536)
                    outputFile.outputStream().buffered().use { out ->
                        while (remaining > 0) {
                            val toRead = minOf(remaining, buf.size.toLong()).toInt()
                            val n = input.read(buf, 0, toRead)
                            if (n == -1) break
                            out.write(buf, 0, n)
                            remaining -= n
                        }
                    }
                    return
                } else {
                    // Skip this member (pad to even byte boundary)
                    var skipped = 0L
                    while (skipped < memberSize) {
                        val n = input.skip(memberSize - skipped)
                        if (n <= 0) break
                        skipped += n
                    }
                    if (memberSize % 2L != 0L) input.read() // padding byte
                }
            }
        }
        throw java.io.FileNotFoundException("data.tar.* not found in ${debFile.name}")
    }

    /** Recursively copies the contents of [src] into [dst], creating dirs as needed.
     *  Symlinks (common in Termux .deb packages) are recreated as symlinks rather than
     *  copied as plain files, which avoids "source file doesn't exist" errors on broken links. */
    private fun copyDirContents(src: java.io.File, dst: java.io.File) {
        if (!src.exists()) return
        dst.mkdirs()
        val srcPath = src.toPath()
        val dstPath = dst.toPath()
        src.walkTopDown().onFail { _, _ -> /* skip unreadable entries */ }.forEach { file ->
            val filePath = file.toPath()
            val rel     = srcPath.relativize(filePath)
            val target  = dstPath.resolve(rel)
            when {
                java.nio.file.Files.isSymbolicLink(filePath) -> {
                    // Recreate the symlink at the destination (don't try to copy it as a file)
                    val linkTarget = java.nio.file.Files.readSymbolicLink(filePath)
                    try {
                        java.nio.file.Files.deleteIfExists(target)
                        target.parent?.toFile()?.mkdirs()
                        java.nio.file.Files.createSymbolicLink(target, linkTarget)
                    } catch (e: Exception) {
                        Log.w("Kodrix", "copyDirContents: skipping symlink $rel → $linkTarget: ${e.message}")
                    }
                }
                file.isDirectory -> target.toFile().mkdirs()
                file.isFile -> {
                    target.parent?.toFile()?.mkdirs()
                    file.copyTo(target.toFile(), overwrite = true)
                }
            }
        }
    }

    // ── Python pylsp installer ────────────────────────────────────────────────

    /**
     * Installs `python-lsp-server` (pylsp) into the IDE-managed Python environment
     * using the bundled pip. After installation restarts the LSP for [fileToOpenAfter].
     */
    private fun installPylspIfNeeded(filesDir: java.io.File, fileToOpenAfter: java.io.File?) {
        viewModelScope.launch(Dispatchers.IO) {
            Log.d("Kodrix", "installPylspIfNeeded coroutine started")
            val pythonDir = findPythonInstallDir(filesDir)
            Log.d("Kodrix", "installPylspIfNeeded: pythonDir = ${pythonDir?.absolutePath}")
            if (pythonDir == null) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        getApplication(),
                        "⚠️ Python runtime not found — install it from the Marketplace first",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                return@launch
            }

            val python3 = java.io.File(pythonDir, "bin/python3")
            val pythonLib = java.io.File(pythonDir, "lib").absolutePath

            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    getApplication(),
                    "⚙️ Installing Python Language Server (pylsp)...",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }

            try {
                // Step 1: Install pylsp and jsonrpc without dependencies to bypass ujson C-compilation
                binaryManager.showProgressNotification("pylsp", "latest", "Installing core packages (Step 1/2)...", 0.1f)
                Log.d("Kodrix", "installPylspIfNeeded: Starting step 1...")
                val shellCmd1 = "LD_LIBRARY_PATH=\"$pythonLib\" PYTHONHOME=\"${pythonDir.absolutePath}\" HOME=\"${filesDir.absolutePath}\" TMPDIR=\"${java.io.File(filesDir, "tmp").apply { mkdirs() }.absolutePath}\" \"${python3.absolutePath}\" -m pip install python-lsp-server python-lsp-jsonrpc --force-reinstall --no-deps --no-warn-script-location -q"
                val pb1 = ProcessBuilder("/system/bin/sh", "-c", shellCmd1)
                pb1.redirectErrorStream(true)
                val proc1 = pb1.start()
                proc1.inputStream.bufferedReader().forEachLine { Log.d("Kodrix", "pylsp-install-step1: $it") }
                val exitCode1 = proc1.waitFor()
                Log.d("Kodrix", "installPylspIfNeeded: Step 1 finished with exit code $exitCode1")

                if (exitCode1 != 0) {
                    binaryManager.cancelProgressNotification()
                    binaryManager.showCompletionNotification("pylsp", "latest", false, "Core installation failed (exit $exitCode1)")
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            getApplication(), "❌ pylsp install failed at step 1 (exit $exitCode1)",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }

                // Step 2: Install pure-python dependencies (jedi, pluggy, docstring-to-markdown, pytoolconfig)
                binaryManager.showProgressNotification("pylsp", "latest", "Installing dependencies (Step 2/2)...", 0.5f)
                Log.d("Kodrix", "installPylspIfNeeded: Starting step 2...")
                val shellCmd2 = "LD_LIBRARY_PATH=\"$pythonLib\" PYTHONHOME=\"${pythonDir.absolutePath}\" HOME=\"${filesDir.absolutePath}\" TMPDIR=\"${java.io.File(filesDir, "tmp").apply { mkdirs() }.absolutePath}\" \"${python3.absolutePath}\" -m pip install jedi pluggy docstring-to-markdown pytoolconfig --no-warn-script-location -q"
                val pb2 = ProcessBuilder("/system/bin/sh", "-c", shellCmd2)
                pb2.redirectErrorStream(true)
                val proc2 = pb2.start()
                proc2.inputStream.bufferedReader().forEachLine { Log.d("Kodrix", "pylsp-install-step2: $it") }
                val exitCode2 = proc2.waitFor()
                Log.d("Kodrix", "installPylspIfNeeded: Step 2 finished with exit code $exitCode2")

                binaryManager.cancelProgressNotification()
                if (exitCode2 == 0) {
                    binaryManager.showCompletionNotification("pylsp", "latest", true)
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            getApplication(), "✅ pylsp installed successfully!",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    if (fileToOpenAfter != null) {
                        startNativeLsp("python", "py", fileToOpenAfter)
                    }
                } else {
                    binaryManager.showCompletionNotification("pylsp", "latest", false, "Dependency installation failed (exit $exitCode2)")
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            getApplication(), "❌ pylsp install failed at step 2 (exit $exitCode2)",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("Kodrix", "pylsp install failed", e)
                binaryManager.cancelProgressNotification()
                binaryManager.showCompletionNotification("pylsp", "latest", false, e.message)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        getApplication(), "❌ pylsp install error: ${e.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }


    fun requestCompletion(file: java.io.File, text: String, cursorOffset: Int) {
        completionJob?.cancel()
        completionJob = viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(150) // Debounce
            doRequestCompletion(file, text, cursorOffset)
        }
    }

    private suspend fun doRequestCompletion(file: java.io.File, text: String, cursorOffset: Int) {
        val ext = file.extension.lowercase()
        val client = activeLSPs[ext] ?: return

        var line = 0
        var character = 0
        var currentOffset = 0
        val lines = text.split('\n')
        for (i in lines.indices) {
            val l = lines[i]
            if (currentOffset + l.length + 1 > cursorOffset) {
                line = i
                character = cursorOffset - currentOffset
                break
            }
            currentOffset += l.length + 1
            line = i
        }

        try {
            // Detect if last char is a trigger character for HTML/TS/CSS/etc.
            val triggerChars = setOf('<', '.', ':', '@', '(', '"', '\'', '/', '>', ' ', '#', '!')
            val lastChar = if (cursorOffset > 0 && cursorOffset <= text.length) text[cursorOffset - 1] else null
            val context = if (lastChar != null && lastChar in triggerChars) {
                com.kodrix.zohaib.lsp.CompletionContext(
                    triggerKind = 2, // TriggerCharacter
                    triggerCharacter = lastChar.toString()
                )
            } else {
                com.kodrix.zohaib.lsp.CompletionContext(triggerKind = 1) // Invoked
            }

            val params = com.kodrix.zohaib.lsp.CompletionParams(
                textDocument = com.kodrix.zohaib.lsp.TextDocumentIdentifier("file://${file.absolutePath}"),
                position = com.kodrix.zohaib.lsp.Position(line, character),
                context = context
            )
            val response = client.request("textDocument/completion", params)
            if (response != null && response.has("result") && !response.get("result").isJsonNull) {
                val result = response.get("result")
                val items = mutableListOf<com.kodrix.zohaib.lsp.CompletionItem>()
                val gson = com.google.gson.Gson()
                
                if (result.isJsonObject && result.asJsonObject.has("items")) {
                    val list = gson.fromJson(result, com.kodrix.zohaib.lsp.CompletionList::class.java)
                    items.addAll(list.items)
                } else if (result.isJsonArray) {
                    val arr = result.asJsonArray
                    arr.forEach { 
                        items.add(gson.fromJson(it, com.kodrix.zohaib.lsp.CompletionItem::class.java))
                    }
                }
                _completionItems.value = items
                Log.d("Kodrix", "LSP: Success! Received ${items.size} completion items for .$ext")
                if (items.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(getApplication(), "Found ${items.size} suggestions", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Log.d("Kodrix", "LSP: Server returned 0 items for .$ext at $line:$character")
                }
            } else {
                _completionItems.value = emptyList()
                Log.d("Kodrix", "LSP: No result/null returned for .$ext at $line:$character")
            }
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException) {
                Log.e("Kodrix", "LSP: Completion request FAILED", e)
            }
            _completionItems.value = emptyList()
        }
    }
    
    private fun getOffsetAt(text: String, line: Int, character: Int): Int {
        val lines = text.split('\n')
        var currentOffset = 0
        for (i in 0 until line) {
            if (i < lines.size) {
                currentOffset += lines[i].length + 1
            }
        }
        return (currentOffset + character).coerceIn(0, text.length)
    }

    fun clearCompletions() {
        _completionItems.value = emptyList()
    }

    fun toggleCtrl() { _isCtrlActive.value = !_isCtrlActive.value }
    fun setCtrl(active: Boolean) { _isCtrlActive.value = active }

    private val _isAltActive = MutableStateFlow(false)
    val isAltActive = _isAltActive.asStateFlow()
    fun toggleAlt() { _isAltActive.value = !_isAltActive.value }
    fun setAlt(active: Boolean) { _isAltActive.value = active }


    private val _setupStatus = MutableStateFlow("Initializing...")
    val setupStatus = _setupStatus.asStateFlow()

    private val _cloneProgress = MutableStateFlow(0)
    val cloneProgress = _cloneProgress.asStateFlow()



    private val _githubUser = MutableStateFlow(prefs.getString("github_user", null))
    val githubUser = _githubUser.asStateFlow()
    private val _githubToken = MutableStateFlow(prefs.getString("github_token", null))
    val githubToken = _githubToken.asStateFlow()

    // Emits the one-time code when gh auth device flow is detected in terminal output
    private val _ghDeviceCode = MutableStateFlow<String?>(null)
    val ghDeviceCode = _ghDeviceCode.asStateFlow()
    private val GH_CODE_REGEX = Regex("""First copy your one-time code: ([A-Z0-9]{4}-[A-Z0-9]{4})""")
    private var lastDetectedGhCode: String? = null

    fun handleGhDeviceCode(code: String) {
        val context = getApplication<Application>().applicationContext
        // 1. Copy the code to clipboard
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("GitHub Code", code))
        // 2. Open the device activation page in the system browser
        val intent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("https://github.com/login/device")
        ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(intent)
        // 3. Clear the event after handling so UI doesn't re-trigger
        _ghDeviceCode.value = null
    }

    private var oauthStateToken: String? = null

    fun loginGithub() {
        val clientId = "Ov23liGDwcWLayi70rk2"
        val redirectUri = "kodrix://github-auth"
        oauthStateToken = java.util.UUID.randomUUID().toString()
        val url = "https://github.com/login/oauth/authorize?client_id=$clientId&scope=repo,user&redirect_uri=$redirectUri&state=$oauthStateToken"
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        getApplication<Application>().startActivity(intent)
    }

    fun verifyAuthState(state: String): Boolean {
        val isValid = state == oauthStateToken && oauthStateToken != null
        oauthStateToken = null // Prevent replay
        return isValid
    }

    fun handleGithubCallback(code: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _setupStatus.value = "Exchanging GitHub code..."
                val clientId = "Ov23liGDwcWLayi70rk2"
                val clientSecret = "961d371f7bd737f4d3de71f13f6b9dfebfed118c"
                val url = java.net.URL("https://github.com/login/oauth/access_token")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Accept", "application/json")
                conn.doOutput = true
                val params = "client_id=$clientId&client_secret=$clientSecret&code=$code"
                conn.outputStream.write(params.toByteArray())
                val response = conn.inputStream.bufferedReader().readText()
                Log.d("Kodrix", "OAuth Response: $response")
                val json = org.json.JSONObject(response)
                val token = json.optString("access_token")
                if (token.isNotEmpty()) {
                    val userUrl = java.net.URL("https://api.github.com/user")
                    val userConn = userUrl.openConnection() as java.net.HttpURLConnection
                    userConn.setRequestProperty("Authorization", "token $token")
                    userConn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                    val userResponse = userConn.inputStream.bufferedReader().readText()
                    Log.d("Kodrix", "User Response: $userResponse")
                    val userJson = org.json.JSONObject(userResponse)
                    val login = userJson.getString("login")
                    withContext(Dispatchers.Main) {
                        saveGithubAuth(login, token)
                        android.widget.Toast.makeText(getApplication(), "Logged in as $login", android.widget.Toast.LENGTH_LONG).show()
                        _setupStatus.value = "Ready"
                    }
                } else {
                    val error = json.optString("error_description", "Unknown error")
                    Log.e("Kodrix", "OAuth failed: $response")
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(getApplication(), "Login Failed: $error", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("Kodrix", "GitHub callback failed", e)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(getApplication(), "Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            } finally {
                _setupStatus.value = "Ready"
                manageBackgroundService()
            }
        }
        manageBackgroundService()
    }

    fun logoutGithub() {
        _githubUser.value = null
        _githubToken.value = null
        prefs.edit().remove("github_user").remove("github_token").apply()
    }

    fun saveGithubAuth(user: String, token: String) {
        _githubUser.value = user
        _githubToken.value = token
        prefs.edit().putString("github_user", user).putString("github_token", token).apply()
    }

    private val _fontSize = MutableStateFlow(prefs.getInt("font_size", 12))
    val fontSize = _fontSize.asStateFlow()

    private val _editorFontSize = MutableStateFlow(prefs.getInt("editor_font_size", 14))
    val editorFontSize = _editorFontSize.asStateFlow()
    private val _uiScale = MutableStateFlow(prefs.getFloat("ui_scale", 1.0f))
    val uiScale = _uiScale.asStateFlow()
    private val _panelHeight = MutableStateFlow(250.dp)
    val panelHeight = _panelHeight.asStateFlow()

    private val _disabledExtensions = MutableStateFlow(prefs.getStringSet("disabled_extensions", emptySet()) ?: emptySet())
    val disabledExtensions = _disabledExtensions.asStateFlow()

    fun toggleExtensionEnabled(id: String, enabled: Boolean) {
        val current = _disabledExtensions.value.toMutableSet()
        if (enabled) {
            current.remove(id)
        } else {
            current.add(id)
        }
        _disabledExtensions.value = current
        prefs.edit().putStringSet("disabled_extensions", current).apply()
    }

    fun isExtensionEnabled(id: String): Boolean {
        return !_disabledExtensions.value.contains(id)
    }
    private val _isPanelVisible = MutableStateFlow(true)
    val isPanelVisible = _isPanelVisible.asStateFlow()
    private val _autoScroll = MutableStateFlow(true)
    val autoScroll = _autoScroll.asStateFlow()

    // ── Project Management ────────────────────────────────────────────────────
    private val projectsRoot get() = java.io.File(getApplication<Application>().filesDir, "projects")
    private val _projects = MutableStateFlow<List<String>>(emptyList())
    val projects = _projects.asStateFlow()
    private val _activeProject = MutableStateFlow<String?>(prefs.getString("active_project", null))
    val activeProject = _activeProject.asStateFlow()
    private val _sidebarOpen = MutableStateFlow(false)
    val sidebarOpen = _sidebarOpen.asStateFlow()

    private val _fileTree = MutableStateFlow<Map<String, List<java.io.File>>>(emptyMap())
    val fileTree = _fileTree.asStateFlow()
    private val _expandedDirs = MutableStateFlow<Set<String>>(emptySet())
    val expandedDirs = _expandedDirs.asStateFlow()

    enum class SidebarMode { PROJECTS, EXPLORER, SEARCH, GIT, EXTENSIONS, MARKETPLACE, DEBUG, BROWSER, SETTINGS, AI }
    private val _sidebarMode = MutableStateFlow(SidebarMode.EXPLORER)
    val sidebarMode = _sidebarMode.asStateFlow()

    enum class MarketMode { EXTENSIONS, PACKAGES }
    private val _marketMode = MutableStateFlow(MarketMode.EXTENSIONS)
    val marketMode = _marketMode.asStateFlow()

    fun setMarketMode(mode: MarketMode) { _marketMode.value = mode }

    private val _npmResults = MutableStateFlow<List<NpmPackage>>(emptyList())
    val npmResults = _npmResults.asStateFlow()

    private val _isNpmSearching = MutableStateFlow(false)
    val isNpmSearching = _isNpmSearching.asStateFlow()
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    fun openExternalBrowser(url: String) {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            getApplication<Application>().startActivity(intent)
        } catch (e: Exception) {
            Log.e("Kodrix", "Failed to open external browser", e)
        }
    }

    private val _logcatOutput = MutableStateFlow<List<String>>(listOf("Initializing Debug Console..."))
    val logcatOutput = _logcatOutput.asStateFlow()

    private val _logcatFilter = MutableStateFlow("")
    val logcatFilter = _logcatFilter.asStateFlow()

    data class UpdateInfo(val version: String, val downloadUrl: String, val releaseNotes: String)
    private val _availableUpdate = MutableStateFlow<UpdateInfo?>(null)
    val availableUpdate = _availableUpdate.asStateFlow()

    fun updateLogcatFilter(query: String) {
        _logcatFilter.value = query
    }

    private val _isLogsPaused = MutableStateFlow(false)
    val isLogsPaused = _isLogsPaused.asStateFlow()

    fun toggleLogsPause() {
        _isLogsPaused.value = !_isLogsPaused.value
    }

    init {
        ensureDnsFix()
        refreshProjects()
        startLogcatStream()
        checkUpdate()
        testNetwork()
        
        // Sync active project to agent (Safe here)
        viewModelScope.launch {
            _activeProject.collect { proj ->
                agentOrchestrator.updateActiveProject(proj)
            }
        }
    }

    private fun testNetwork() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.i("Kodrix", "Network Test: Starting...")
                
                // Test 1: TCP to 8.8.8.8:53 (Google DNS)
                Log.i("Kodrix", "Network Test: TCP to 8.8.8.8:53...")
                val socket = java.net.Socket()
                try {
                    socket.connect(java.net.InetSocketAddress("8.8.8.8", 53), 5000)
                    Log.i("Kodrix", "Network Test: TCP to 8.8.8.8:53 SUCCESS")
                    socket.close()
                } catch (e: Exception) {
                    Log.e("Kodrix", "Network Test: TCP to 8.8.8.8:53 FAILED: ${e.message}")
                }

                // Test 2: DNS lookup for google.com
                Log.i("Kodrix", "Network Test: DNS lookup for google.com...")
                try {
                    val addr = java.net.InetAddress.getByName("google.com")
                    Log.i("Kodrix", "Network Test: DNS lookup SUCCESS: ${addr.hostAddress}")
                } catch (e: Exception) {
                    Log.e("Kodrix", "Network Test: DNS lookup FAILED: ${e.message}")
                }

                // Test 3: HTTPS request
                Log.i("Kodrix", "Network Test: HTTPS to google.com...")
                try {
                    val url = java.net.URL("https://www.google.com")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    val responseCode = conn.responseCode
                    Log.i("Kodrix", "Network Test: HTTPS SUCCESS, code: $responseCode")
                    conn.disconnect()
                } catch (e: Exception) {
                    Log.e("Kodrix", "Network Test: HTTPS FAILED: ${e.message}")
                }
                
            } catch (e: Exception) {
                Log.e("Kodrix", "Network Test: GLOBAL FAILURE", e)
            }
        }
    }

    /**
     * Dynamically searches for a binary under filesDir using a depth-first scan.
     * Checks common known paths first for speed, then falls back to a full `find` search.
     */
    private fun findBinary(filesDir: java.io.File, binaryName: String): java.io.File? {
        Log.d("Kodrix", "findBinary: searching for $binaryName in ${filesDir.absolutePath}")
        
        // Check BinaryManager first (versioned binaries)
        val managedPath = binaryManager.getBinaryPath(binaryName)
        if (managedPath != null) {
            val f = java.io.File(managedPath)
            if (f.exists()) {
                Log.d("Kodrix", "findBinary: $binaryName found in managed versions at $managedPath")
                return f
            }
        }

        // Check common locations first (fast path)
        val commonPaths = listOf(
            "usr/bin/$binaryName",
            "bin/$binaryName",
            "usr/local/bin/$binaryName",
            "npm_pkg/bin/$binaryName",
            "usr/share/npm/bin/$binaryName",
            "usr/lib/node_modules/npm/bin/$binaryName"
        )
        for (path in commonPaths) {
            val f = java.io.File(filesDir, path)
            Log.v("Kodrix", "findBinary check: ${f.absolutePath} exists=${f.exists()} canRead=${f.canRead()}")
            if (f.exists()) {
                Log.d("Kodrix", "findBinary: $binaryName found at ${f.absolutePath} (fast path)")
                return f
            }
        }
        // Slow fallback: recursive scan up to 6 levels deep
        return try {
            val findCmd = listOf("/system/bin/find", filesDir.absolutePath,
                "-maxdepth", "6", "-name", binaryName, "-type", "f")
            Log.d("Kodrix", "findBinary: running fallback find: ${findCmd.joinToString(" ")}")
            val result = ProcessBuilder(findCmd).redirectErrorStream(true).start().inputStream.bufferedReader().lineSequence()
                .map { it.trim() }
                .firstOrNull { line ->
                    line.isNotEmpty() && !line.contains("usr/bin_safe")
                }
            if (!result.isNullOrBlank()) {
                val f = java.io.File(result)
                Log.d("Kodrix", "findBinary: $binaryName found at ${f.absolutePath} (find scan)")
                f
            } else {
                Log.w("Kodrix", "findBinary: $binaryName not found in ${filesDir.absolutePath}")
                null
            }
        } catch (e: Exception) {
            Log.e("Kodrix", "findBinary: search failed for $binaryName", e)
            null
        }
    }

    private fun installLSPsIfNeeded() {
        viewModelScope.launch(Dispatchers.IO) {
            val filesDir = getApplication<android.app.Application>().filesDir
            val lspDir = java.io.File(filesDir, "lsp")
            
            // Marker file to track successful installation
            val successMarker = java.io.File(lspDir, ".install_success")
            val tsLspBin = java.io.File(lspDir, "node_modules/.bin/typescript-language-server")
            
            if (tsLspBin.exists() && successMarker.exists()) {
                Log.d("Kodrix", "LSPs already installed and verified")
                return@launch
            }

            Log.d("Kodrix", "LSP missing or incomplete, starting install/retry...")
            lspDir.mkdirs()
            
            val packageJson = java.io.File(lspDir, "package.json")
            if (!packageJson.exists()) {
                packageJson.writeText("""
                    {
                      "name": "codeoss-lsp",
                      "version": "1.0.0",
                      "description": "L
                    }
                """.trimIndent())
            }

            val nativeLibPath = getApplication<android.app.Application>().applicationInfo.nativeLibraryDir
            val libLinksDir = java.io.File(filesDir, "lib").absolutePath
            val dnsOverridePath = java.io.File(filesDir, "dns-override.js").absolutePath
            val binDir = filesDir.absolutePath

            // Resolve active Node.js version
            val activeNodeVersion = binaryManager.getActiveVersion("node")
            val activeNodeBinDir = if (activeNodeVersion != null)
                java.io.File(filesDir, "versions/node/$activeNodeVersion/bin").absolutePath else null
            val activeNodeLibDir = if (activeNodeVersion != null)
                java.io.File(filesDir, "versions/node/$activeNodeVersion/lib").absolutePath else null
            val activeNpmCli = if (activeNodeVersion != null) {
                val f = java.io.File(filesDir, "versions/node/$activeNodeVersion/lib/node_modules/npm/bin/npm-cli.js")
                if (f.exists()) f else null
            } else null

            // Dynamically detect node and npm-cli.js regardless of version/layout
            val nodeBin = findBinary(filesDir, "node")
            val npmScript = activeNpmCli ?: findBinary(filesDir, "npm-cli.js")

            if (nodeBin == null) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(getApplication(), "⚠️ Node.js not found — LSP install skipped", android.widget.Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            if (npmScript == null) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(getApplication(), "⚠️ npm-cli.js not found — LSP install skipped", android.widget.Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            Log.d("Kodrix", "LSP install using: node=${nodeBin.absolutePath}, npm=${npmScript.absolutePath}")

            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(getApplication(), "⚙️ Installing Language Servers...", android.widget.Toast.LENGTH_LONG).show()
            }

            // ProcessBuilder with exact same env as runNpm()
            val cmd = listOf(
                nodeBin.absolutePath,
                npmScript.absolutePath,
                "install",
                "typescript",
                "typescript-language-server",
                "vscode-langservers-extracted",
                "bash-language-server",
                "--no-fund",
                "--no-audit",
                "--prefix", lspDir.absolutePath
            )

            try {
                val pb = ProcessBuilder(cmd)
                pb.directory(lspDir)
                val env = pb.environment()
                env["HOME"] = binDir
                env["USER"] = "kodrix"
                env["TMPDIR"] = java.io.File(filesDir, "tmp").apply { mkdirs() }.absolutePath
                val ldPath = buildString {
                    if (activeNodeLibDir != null) append("$activeNodeLibDir:")
                    append("$libLinksDir")
                }
                val pathEnv = buildString {
                    if (activeNodeBinDir != null) append("$activeNodeBinDir:")
                    append("${filesDir.absolutePath}/usr/bin:${filesDir.absolutePath}/bin:$nativeLibPath:/system/bin:/system/xbin")
                }
                env["LD_LIBRARY_PATH"] = ldPath
                env["NODE_PATH"] = ".:$binDir/npm_pkg/node_modules"
                env["PATH"] = pathEnv
                env["OPENSSL_CONF"] = "/dev/null"
                env["NODE_OPTIONS"] = "--require $dnsOverridePath"
                env["APP_FILES_DIR"] = binDir
                pb.redirectErrorStream(true)
                
                val process = pb.start()
                val reader = process.inputStream.bufferedReader()
                var line: String?
                var lastToastTime = 0L
                while (reader.readLine().also { line = it } != null) {
                    Log.d("Kodrix", "LSP Install: $line")
                    // Show periodic progress toasts without spamming
                    val now = System.currentTimeMillis()
                    val currentLine = line ?: ""
                    if (now - lastToastTime > 4000 && currentLine.isNotBlank()) {
                        lastToastTime = now
                        val shortMsg = when {
                            currentLine.contains("typescript-language-server") -> "📦 Installing TypeScript LSP..."
                            currentLine.contains("vscode-langservers") -> "📦 Installing HTML/CSS/JSON LSP..."
                            currentLine.contains("bash-language-server") -> "📦 Installing Bash LSP..."
                            currentLine.contains("added") -> "✅ ${currentLine.trim()}"
                            else -> null
                        }
                        if (shortMsg != null) {
                            withContext(Dispatchers.Main) {
                                android.widget.Toast.makeText(getApplication(), shortMsg, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                
                val exitCode = process.waitFor()
                withContext(Dispatchers.Main) {
                    if (exitCode == 0) {
                        successMarker.writeText(System.currentTimeMillis().toString())
                        android.widget.Toast.makeText(getApplication(), "✅ Language Servers ready! Open any .ts, .html, .css, .json file.", android.widget.Toast.LENGTH_LONG).show()
                    } else {
                        android.widget.Toast.makeText(getApplication(), "❌ Language Server install failed (exit $exitCode)", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("Kodrix", "Failed to run LSP install", e)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(getApplication(), "❌ LSP install error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }


    private fun ensureDnsFix() {
        val filesDir = getApplication<Application>().filesDir
        val dnsOverrideFile = java.io.File(filesDir, "dns-override.js")
        val script = """
            (function() {
                const dns = require('dns');
                const { Resolver } = require('dns');
                const promises = dns.promises;

                const resolver = new Resolver();
                resolver.setServers(['8.8.8.8', '1.1.1.1', '8.8.4.4']);

                console.log('[DNS Patch] Initialized with servers:', resolver.getServers());

                const originalLookup = dns.lookup;
                dns.lookup = function(hostname, options, callback) {
                    if (typeof options === 'function') {
                        callback = options;
                        options = {};
                    }
                    if (typeof options === 'number') {
                        options = { family: options };
                    }

                    const family = options ? (options.family || 0) : 0;
                    const all = options ? (options.all || false) : false;

                    if (!hostname || hostname === 'localhost' || /^\d+\.\d+\.\d+\.\d+$/.test(hostname)) {
                        return originalLookup(hostname, options, callback);
                    }

                    console.log('[DNS Patch] Looking up ' + hostname + ' (family: ' + family + ', all: ' + all + ')');

                    const resolveMethod = family === 6 ? 'resolve6' : 'resolve4';
                    
                    resolver[resolveMethod](hostname, (err, addresses) => {
                        if (err) {
                            console.warn('[DNS Patch] Failed to resolve ' + hostname + ' via ' + resolveMethod + ': ' + err.message + '. Trying fallback.');
                            if (family === 0 && resolveMethod === 'resolve4') {
                                return resolver.resolve6(hostname, (err6, addresses6) => {
                                    if (err6) return originalLookup(hostname, options, callback);
                                    finish(addresses6, 6);
                                });
                            }
                            return originalLookup(hostname, options, callback);
                        }
                        finish(addresses, family === 6 ? 6 : 4);
                    });

                    function finish(addresses, detectedFamily) {
                        console.log('[DNS Patch] Resolved ' + hostname + ' to ' + addresses);
                        if (all) {
                            const results = addresses.map(addr => ({
                                address: addr,
                                family: detectedFamily
                            }));
                            return callback(null, results);
                        } else {
                            return callback(null, addresses[0], detectedFamily);
                        }
                    }
                };

                // Override promises.lookup if available
                if (promises && promises.lookup) {
                    promises.lookup = function(hostname, options) {
                        return new Promise((resolve, reject) => {
                            dns.lookup(hostname, options, (err, address, family) => {
                                if (err) return reject(err);
                                if (options && options.all) {
                                    resolve(address);
                                } else {
                                    resolve({ address, family });
                                }
                            });
                        });
                    };
                }
                
                // Override direct resolve methods
                dns.resolve4 = (hostname, callback) => resolver.resolve4(hostname, callback);
                dns.resolve6 = (hostname, callback) => resolver.resolve6(hostname, callback);
                if (promises) {
                    promises.resolve4 = (hostname) => new Promise((resolve, reject) => resolver.resolve4(hostname, (err, addr) => err ? reject(err) : resolve(addr)));
                    promises.resolve6 = (hostname) => new Promise((resolve, reject) => resolver.resolve6(hostname, (err, addr) => err ? reject(err) : resolve(addr)));
                }
            })();
        """.trimIndent()
        dnsOverrideFile.writeText(script)
    }

    private fun checkUpdate() {
        viewModelScope.launch(Dispatchers.IO) {
            var connection: java.net.HttpURLConnection? = null
            try {
                val context = getApplication<Application>()
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                val currentVersion = packageInfo.versionName ?: "1.0"
                
                val url = java.net.URL("https://api.github.com/repos/Zohaib8090/KodrixIDE/releases/latest")
                connection = url.openConnection() as java.net.HttpURLConnection
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                connection.setRequestProperty("User-Agent", "Kodrix-IDE-App")
                
                val conn = connection ?: return@launch
                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    val latestTag = json.getString("tag_name").replace("v", "")
                    
                    val isNewer = try {
                        val latestParts = latestTag.split(".").map { it.toInt() }
                        val currentParts = currentVersion.split(".").map { it.toInt() }
                        var newer = false
                        for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
                            val l = latestParts.getOrNull(i) ?: 0
                            val c = currentParts.getOrNull(i) ?: 0
                            if (l > c) {
                                newer = true
                                break
                            } else if (l < c) {
                                break
                            }
                        }
                        newer
                    } catch (e: Exception) {
                        latestTag != currentVersion
                    }

                    if (isNewer) {
                        val assets = json.getJSONArray("assets")
                        var downloadUrl = json.getString("html_url")
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            if (asset.getString("name").endsWith(".apk")) {
                                downloadUrl = asset.getString("browser_download_url")
                                break
                            }
                        }
                        _availableUpdate.value = UpdateInfo(
                            version = latestTag,
                            downloadUrl = downloadUrl,
                            releaseNotes = json.optString("body", "No release notes provided.")
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("Kodrix", "Update check failed", e)
            } finally {
                connection?.disconnect()
            }
        }
    }

    fun dismissUpdate() { _availableUpdate.value = null }

    fun appendLogcat(line: String) {
        val currentList = _logcatOutput.value.toMutableList()
        currentList.add(line)
        if (currentList.size > 500) currentList.removeAt(0)
        _logcatOutput.value = currentList
    }

    private var logcatProcess: Process? = null

    private fun startLogcatStream() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Runtime.getRuntime().exec("logcat -c").waitFor()
                val process = Runtime.getRuntime().exec("logcat -v time")
                logcatProcess = process
                val reader = process.inputStream.bufferedReader()
                var line: String?
                while (isActive) {
                    line = reader.readLine()
                    if (line != null) {
                        // Security Mitigation: Drop noisy and sensitive Firebase/Google Services logs
                        if (line.contains("Firebase", ignoreCase = true) || line.contains("google-services", ignoreCase = true)) {
                            continue
                        }
                        
                        // Redact any stray Google API keys from the logcat stream
                        val sanitizedLine = line.replace(Regex("AIza[0-9A-Za-z-_]{35}"), "[REDACTED_API_KEY]")

                        val currentList = _logcatOutput.value.toMutableList()
                        currentList.add(sanitizedLine)
                        if (currentList.size > 500) currentList.removeAt(0)
                        _logcatOutput.value = currentList
                    } else {
                        delay(500)
                    }
                }
            } catch (e: Exception) {
                _logcatOutput.value = _logcatOutput.value + "Logcat stream error: ${e.message}"
            } finally {
                logcatProcess?.destroy()
                logcatProcess = null
            }
        }
    }

    data class MarketplaceExtension(
        val name: String,
        val displayName: String,
        val publisher: String,
        val description: String,
        val version: String,
        val iconUrl: String?,
        val downloadCount: Int,
        val namespace: String,
        val versions: List<String> = emptyList()
    )

    private val _marketplaceExtensions = MutableStateFlow<List<MarketplaceExtension>>(emptyList())
    val marketplaceExtensions = _marketplaceExtensions.asStateFlow()
    private val _isSearchingExtensions = MutableStateFlow(false)
    val isSearchingExtensions = _isSearchingExtensions.asStateFlow()

    private val _activeExtensionDetail = MutableStateFlow<MarketplaceExtension?>(null)
    val activeExtensionDetail = _activeExtensionDetail.asStateFlow()

    fun setActiveExtensionDetail(extension: MarketplaceExtension?) {
        if (extension == null) {
            _activeExtensionDetail.value = null
            return
        }
        
        viewModelScope.launch(Dispatchers.IO) {
            var connection: java.net.HttpURLConnection? = null
            try {
                val url = java.net.URL("https://open-vsx.org/api/${extension.namespace}/${extension.name}")
                val conn = url.openConnection() as java.net.HttpURLConnection
                connection = conn
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val json = org.json.JSONObject(text)
                val versionsObj = json.optJSONObject("allVersions")
                val versionList = mutableListOf<String>()
                if (versionsObj != null) {
                    val keys = versionsObj.keys()
                    while (keys.hasNext()) {
                        versionList.add(keys.next())
                    }
                }
                
                withContext(Dispatchers.Main) {
                    _activeExtensionDetail.value = extension.copy(versions = versionList.sortedDescending())
                    _sidebarOpen.value = false
                    _activeGithubExtensionDetail.value = null
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _activeExtensionDetail.value = extension
                    _sidebarOpen.value = false
                    _activeGithubExtensionDetail.value = null
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    fun searchExtensions(query: String) {
        if (query.isBlank()) {
            _marketplaceExtensions.value = emptyList()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _isSearchingExtensions.value = true
            var connection: java.net.HttpURLConnection? = null
            try {
                val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
                val url = java.net.URL("https://open-vsx.org/api/-/search?text=$encodedQuery&size=50")
                val conn = url.openConnection() as java.net.HttpURLConnection
                connection = conn
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val json = org.json.JSONObject(text)
                val results = json.getJSONArray("extensions")
                val extensions = mutableListOf<MarketplaceExtension>()
                val lowerQuery = query.lowercase()
                for (i in 0 until results.length()) {
                    val ext = results.getJSONObject(i)
                    val dName = ext.optString("displayName", "").lowercase()
                    val mName = ext.optString("name", "").lowercase()
                    val desc = ext.optString("description", "").lowercase()
                    val pub = ext.optString("namespace", "").lowercase()
                    if (dName.contains(lowerQuery) || mName.contains(lowerQuery) ||
                        desc.contains(lowerQuery) || pub.contains(lowerQuery)) {
                        val files = ext.optJSONObject("files")
                        val iconUrl = files?.optString("icon", "")
                        extensions.add(MarketplaceExtension(
                            name = ext.optString("name", "unknown"),
                            displayName = ext.optString("displayName", ext.optString("name", "Unknown")),
                            publisher = ext.optString("namespace", "unknown"),
                            description = ext.optString("description", "No description"),
                            version = ext.optString("version", "0.0.1"),
                            iconUrl = iconUrl,
                            downloadCount = ext.optInt("downloadCount", 0),
                            namespace = ext.optString("namespace", "unknown")
                        ))
                    }
                }
                if (lowerQuery.contains("gemini")) {
                    extensions.sortByDescending { it.publisher.lowercase() == "google" }
                }
                _marketplaceExtensions.value = extensions
            } catch (e: Exception) {
                Log.e("Kodrix", "Marketplace search failed", e)
            } finally {
                _isSearchingExtensions.value = false
                connection?.disconnect()
            }
        }
    }

    fun setSidebarMode(mode: SidebarMode) {
        if (_sidebarMode.value == mode && _sidebarOpen.value) {
            _sidebarOpen.value = false
            return
        }
        _sidebarMode.value = mode
        if (mode == SidebarMode.MARKETPLACE) {
            scanMarketplace()
        }
        _sidebarOpen.value = true
        if (mode == SidebarMode.PROJECTS) refreshProjects()
        if (mode == SidebarMode.EXPLORER) _activeProject.value?.let { refreshFileTree(java.io.File(projectsRoot, it)) }
        if (mode == SidebarMode.GIT) refreshGitStatus()
        if (mode == SidebarMode.AI) aiManager.startEngine()
    }

    private val _gitBranch = MutableStateFlow("main")
    val gitBranch = _gitBranch.asStateFlow()
    private val _gitChanges = MutableStateFlow<List<GitChange>>(emptyList())
    val gitChanges = _gitChanges.asStateFlow()
    private val _gitLog = MutableStateFlow<List<GitCommit>>(emptyList())
    val gitLog = _gitLog.asStateFlow()
    private val _gitBranches = MutableStateFlow<List<String>>(emptyList())
    val gitBranches = _gitBranches.asStateFlow()
    private val _gitRemoteUrl = MutableStateFlow<String?>(null)
    val gitRemoteUrl = _gitRemoteUrl.asStateFlow()
    // Marketplace Extensions
    private val _availableExtensions = MutableStateFlow<List<com.kodrix.zohaib.bridge.Extension>>(emptyList())
    val availableExtensions = _availableExtensions.asStateFlow()
    private val _isScanningMarketplace = MutableStateFlow(false)
    val isScanningMarketplace = _isScanningMarketplace.asStateFlow()
    private val _activeGithubExtensionDetail = MutableStateFlow<com.kodrix.zohaib.bridge.Extension?>(null)
    val activeGithubExtensionDetail = _activeGithubExtensionDetail.asStateFlow()

    fun scanMarketplace() {
        viewModelScope.launch(Dispatchers.IO) {
            _isScanningMarketplace.value = true
            val list = com.kodrix.zohaib.bridge.ExtensionManager.scanMarketplace(getApplication(), _githubToken.value, _activeProject.value)
            _availableExtensions.value = list
            _isScanningMarketplace.value = false
        }
    }

    fun selectGithubExtension(extension: com.kodrix.zohaib.bridge.Extension?) {
        _activeGithubExtensionDetail.value = extension
        if (extension != null) {
            _sidebarOpen.value = false
            _activeExtensionDetail.value = null // Clear OpenVSX detail if any
        }
    }

    private fun runNpm(workingDir: java.io.File? = null, vararg args: String): Process? {
        val proj = _activeProject.value
        val projDir = if (proj != null) java.io.File(projectsRoot, proj) else projectsRoot
        val targetDir = workingDir ?: projDir
        val filesDir = getApplication<Application>().filesDir
        val filesDirPath = filesDir.absolutePath
        val nativeLibPath = getApplication<Application>().applicationInfo.nativeLibraryDir
        val libLinksDir = java.io.File(filesDirPath, "lib").absolutePath

        // Resolve active Node.js version from BinaryManager
        val activeNodeVersion = binaryManager.getActiveVersion("node")
        val activeNodeBinDir = if (activeNodeVersion != null)
            java.io.File(filesDir, "versions/node/$activeNodeVersion/bin").absolutePath else null
        val activeNodeLibDir = if (activeNodeVersion != null)
            java.io.File(filesDir, "versions/node/$activeNodeVersion/lib").absolutePath else null
        val activeNpmCli = if (activeNodeVersion != null) {
            val f = java.io.File(filesDir, "versions/node/$activeNodeVersion/lib/node_modules/npm/bin/npm-cli.js")
            if (f.exists()) f.absolutePath else null
        } else null

        val nodeBin = activeNodeBinDir?.let { "$it/node" }
            ?: java.io.File(filesDir, "usr/bin/node").absolutePath
        val npmCli = activeNpmCli
            ?: java.io.File(filesDir, "npm_pkg/bin/npm-cli.js").absolutePath

        Log.d("Kodrix", "NPM EXEC: node=$nodeBin npm=$npmCli cwd=${targetDir.absolutePath}")

        val pb = ProcessBuilder(nodeBin, npmCli, *args)
        pb.directory(targetDir)

        val dnsOverridePath = java.io.File(filesDir, "dns-override.js").absolutePath
        val ldPath = buildString {
            if (activeNodeLibDir != null) append("$activeNodeLibDir:")
            append("$libLinksDir")
        }
        val pathEnv = buildString {
            if (activeNodeBinDir != null) append("$activeNodeBinDir:")
            append("${filesDir.absolutePath}/usr/bin:${filesDir.absolutePath}/bin:$nativeLibPath:/system/bin:/system/xbin")
        }
        val env = pb.environment()
        env["HOME"] = filesDirPath
        env["USER"] = "kodrix"
        env["TMPDIR"] = java.io.File(filesDir, "tmp").apply { mkdirs() }.absolutePath
        env["LD_LIBRARY_PATH"] = ldPath
        env["APP_FILES_DIR"] = filesDirPath
        env["NODE_PATH"] = ".:$filesDirPath/npm_pkg/node_modules"
        env["PATH"] = pathEnv
        env["OPENSSL_CONF"] = "/dev/null"
        env["NODE_OPTIONS"] = "--require $dnsOverridePath"

        pb.redirectErrorStream(true)

        return try {
            pb.start()
        } catch (e: Exception) {
            Log.e("Kodrix", "NPM start failed", e)
            null
        }
    }

    fun installGithubExtension(extension: com.kodrix.zohaib.bridge.Extension, version: String? = null) {
        val proj = _activeProject.value
        // Note: Global extensions (npm) don't strictly require an active project, but others might.
        // We will allow npm installation even without an active project.
        if (proj == null && extension.type != "npm") {
            Log.w("Kodrix", "Install aborted: No active project")
            viewModelScope.launch(Dispatchers.Main) {
                android.widget.Toast.makeText(getApplication(), "Please open a project first!", android.widget.Toast.LENGTH_LONG).show()
            }
            return
        }

        // Update state on Main thread for instant UI feedback
        _installingIds.value = _installingIds.value + extension.id
        _installingProgress.value = _installingProgress.value + (extension.id to 0f)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (extension.type == "npm") {
                    val pkgName = extension.packageName ?: extension.id
                    val targetPkg = if (version != null && version != "Latest") "$pkgName@$version" else pkgName
                    _binaryUpdateStatus.value = "Installing $targetPkg..."
                    _isUpdatingBinaries.value = true
                    
                    try {
                        val lspDir = java.io.File(getApplication<Application>().filesDir, "lsp")
                        lspDir.mkdirs()
                        val pkgJson = java.io.File(lspDir, "package.json")
                        if (!pkgJson.exists()) {
                            pkgJson.writeText("{}")
                        }
                        
                        val proc = runNpm(lspDir, "install", targetPkg)
                        proc?.let { p ->
                            val reader = p.inputStream.bufferedReader()
                            var line: String?
                            var simulatedProgress = 0.1f
                            while (reader.readLine().also { line = it } != null) {
                                Log.d("Kodrix", "NPM Out: $line")
                                val status = line?.take(40) ?: ""
                                _binaryUpdateStatus.value = "NPM: $status"
                                // Simulating progress for NPM since it doesn't give clean percentages
                                simulatedProgress = (simulatedProgress + 0.05f).coerceAtMost(0.95f)
                                _installingProgress.value = _installingProgress.value + (extension.id to simulatedProgress)
                            }
                            p.waitFor()
                        }
                    } finally {
                        _isUpdatingBinaries.value = false
                        _binaryUpdateStatus.value = "Ready"
                    }
                } else {
                    com.kodrix.zohaib.bridge.ExtensionManager.installExtension(getApplication(), extension, version) { progress ->
                        _installingProgress.value = _installingProgress.value + (extension.id to progress)
                    }
                }
                _installingProgress.value = _installingProgress.value + (extension.id to 1f)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(getApplication(), "${extension.name} installed successfully!", android.widget.Toast.LENGTH_SHORT).show()
                }
                scanMarketplace() // Refresh list to show installed state
            } finally {
                _installingIds.value = _installingIds.value - extension.id
                // Delay clearing progress so user sees 100% for a moment
                delay(1000)
                _installingProgress.value = _installingProgress.value - extension.id
            }
        }
    }

    fun installLocalExtension(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            try {
                _binaryUpdateStatus.value = "Installing local extension..."
                val inputStream = context.contentResolver.openInputStream(uri) ?: return@launch
                val tempZip = java.io.File(context.cacheDir, "manual_ext.zip")
                tempZip.outputStream().use { inputStream.copyTo(it) }
                
                // We'll extract the ID from the folder name inside the zip or ask for it?
                // For now, let's just unzip it and refresh. 
                // We assume the ZIP contains a folder with the extension ID.
                val targetDir = java.io.File(context.filesDir, "extensions")
                targetDir.mkdirs()
                
                java.util.zip.ZipInputStream(java.io.FileInputStream(tempZip)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val newFile = java.io.File(targetDir, entry.name)
                        if (entry.isDirectory) {
                            newFile.mkdirs()
                        } else {
                            newFile.parentFile?.mkdirs()
                            newFile.outputStream().use { zis.copyTo(it) }
                        }
                        entry = zis.nextEntry
                    }
                }
                
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Local extension installed!", android.widget.Toast.LENGTH_SHORT).show()
                    scanMarketplace()
                }
            } catch (e: Exception) {
                Log.e("Kodrix", "Local install failed", e)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Install failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            } finally {
                _binaryUpdateStatus.value = "Ready"
            }
        }
    }
    private val _activeTunnels = MutableStateFlow<List<ForwardedPort>>(emptyList())
    val activeTunnels = _activeTunnels.asStateFlow()

    // Binary Update System
    private val _binaryUpdateStatus = MutableStateFlow("Up to date")
    val binaryUpdateStatus = _binaryUpdateStatus.asStateFlow()
    private val _binaryUpdateProgress = MutableStateFlow(0f)
    val binaryUpdateProgress = _binaryUpdateProgress.asStateFlow()
    private val _binaryUpdateProgressInfo = MutableStateFlow("")
    val binaryUpdateProgressInfo = _binaryUpdateProgressInfo.asStateFlow()
    private val _isUpdatingBinaries = MutableStateFlow(false)
    val isUpdatingBinaries = _isUpdatingBinaries.asStateFlow()


    fun checkBinaryUpdates() {
        viewModelScope.launch(Dispatchers.IO) {
            _binaryUpdateStatus.value = "Checking..."
            val registryUrl = "https://raw.githubusercontent.com/Zohaib8090/KodrixMarketplace/main/binaries.json"
            try {
                val update = com.kodrix.zohaib.bridge.BinaryUpdater.checkUpdates(registryUrl)
                if (update != null) {
                    if (update.nodeVersion != _nodeVersion.value || update.gitVersion != _gitVersion.value) {
                        _binaryUpdateStatus.value = "Update Available: Node ${update.nodeVersion}"
                    } else {
                        _binaryUpdateStatus.value = "Binaries are up to date"
                    }
                } else {
                    _binaryUpdateStatus.value = "Check failed (Registry missing/404)"
                }
            } catch (e: Exception) {
                _binaryUpdateStatus.value = "Network error: ${e.message}"
            }
        }
    }

    fun applyBinaryUpdate() {
        if (_isUpdatingBinaries.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isUpdatingBinaries.value = true
            try {
                _binaryUpdateStatus.value = "Downloading Node.js..."
                val registryUrl = "https://raw.githubusercontent.com/Zohaib8090/KodrixMarketplace/main/binaries.json"
                val update = com.kodrix.zohaib.bridge.BinaryUpdater.checkUpdates(registryUrl) ?: return@launch
                
                val successNode = com.kodrix.zohaib.bridge.BinaryUpdater.downloadAndInstall(getApplication(), update.nodeUrl, "usr") { p, d, t ->
                    _binaryUpdateProgress.value = p * 0.5f
                    _binaryUpdateProgressInfo.value = "Node.js: ${formatBytes(d)} / ${formatBytes(t)}"
                }
                
                if (successNode) {
                    _binaryUpdateStatus.value = "Downloading Git..."
                    val successGit = com.kodrix.zohaib.bridge.BinaryUpdater.downloadAndInstall(getApplication(), update.gitUrl, "usr") { p, d, t ->
                        _binaryUpdateProgress.value = 0.5f + (p * 0.5f)
                        _binaryUpdateProgressInfo.value = "Git: ${formatBytes(d)} / ${formatBytes(t)}"
                    }
                    
                    if (successGit) {
                        _nodeVersion.value = update.nodeVersion
                        _gitVersion.value = update.gitVersion
                        prefs.edit().putString("node_version", update.nodeVersion).putString("git_version", update.gitVersion).apply()
                        _binaryUpdateStatus.value = "Binaries updated! Please restart the app."
                        _binaryUpdateProgressInfo.value = "Installation Complete"
                    } else {
                        _binaryUpdateStatus.value = "Git update failed"
                    }
                } else {
                    _binaryUpdateStatus.value = "Node update failed"
                }
            } catch (e: Exception) {
                _binaryUpdateStatus.value = "Error: ${e.message}"
            } finally {
                _isUpdatingBinaries.value = false
                _binaryUpdateProgress.value = 0f
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val i = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, 3)
        return String.format("%.2f %s", bytes / Math.pow(1024.0, i.toDouble()), units[i])
    }

    fun startTunnel(port: Int) {
        if (_activeTunnels.value.any { it.port == port }) return
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val nativeLibPath = context.applicationInfo.nativeLibraryDir
            val filesDir = context.filesDir.absolutePath
            val boreBin = "$nativeLibPath/libbore.so"
            val logFile = java.io.File(filesDir, "tunnel.log")
            val env = arrayOf(
                "HOME=$filesDir",
                "LD_LIBRARY_PATH=$filesDir/lib"
            )
            try {
                logFile.writeText("--- Bore Tunnel Starting ---\n")

                // Use ProcessBuilder to launch bore in its own process group so that
                // calling destroy() on this process does NOT send signals to the app.
                val pb = ProcessBuilder(boreBin, "local", port.toString(), "--to", "bore.pub")
                pb.environment().apply {
                    put("HOME", filesDir)
                    put("LD_LIBRARY_PATH", "$filesDir/lib")
                }
                pb.redirectErrorStream(false) // keep stdout and stderr separate
                val process = pb.start()

                // Capture the PID so we can kill only this bore process later.
                // Process.pid() is only available in Java 9+ (API 26+), so use reflection for safety.
                val pid: Long = try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        val pidMethod = process.javaClass.getDeclaredMethod("pid")
                        pidMethod.isAccessible = true
                        (pidMethod.invoke(process) as? Long) ?: -1L
                    } else {
                        -1L
                    }
                } catch (e: Exception) {
                    Log.w("CodeOSS-Bore", "Could not get bore PID: ${e.message}")
                    -1L
                }

                val newTunnel = ForwardedPort(port, "Starting...", process, pid)
                _activeTunnels.value = _activeTunnels.value + newTunnel

                // Read stdout to detect the tunnel URL
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val reader = process.inputStream.bufferedReader()
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            val l = line ?: continue
                            Log.d("CodeOSS-Bore", l)
                            logFile.appendText("OUT: $l\n")
                            if (l.contains("listening at ")) {
                                val remotePort = l.substringAfter("listening at ").trim().substringAfter(":")
                                val url = "http://bore.pub:$remotePort"
                                _activeTunnels.value = _activeTunnels.value.map {
                                    if (it.port == port) it.copy(url = url) else it
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.d("CodeOSS-Bore", "stdout closed for port $port")
                    }
                }

                // Read stderr to surface errors
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val errReader = process.errorStream.bufferedReader()
                        var errLine: String?
                        while (errReader.readLine().also { errLine = it } != null) {
                            val l = errLine ?: continue
                            Log.e("CodeOSS-Bore", "ERR: $l")
                            logFile.appendText("ERR: $l\n")
                            if (l.contains("error") || l.contains("failed")) {
                                _activeTunnels.value = _activeTunnels.value.map {
                                    if (it.port == port && it.url == "Starting...") it.copy(url = "Error: ${l.take(50)}") else it
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.d("CodeOSS-Bore", "stderr closed for port $port")
                    }
                }

                // Watch for unexpected process exit and clean up UI state
                viewModelScope.launch(Dispatchers.IO) {
                    val exitCode = process.waitFor()
                    Log.d("CodeOSS-Bore", "Bore exited for port $port with code $exitCode")
                    _activeTunnels.value = _activeTunnels.value.filter { it.port != port }
                }

            } catch (e: Exception) {
                Log.e("Kodrix", "Bore failed for port $port", e)
                _activeTunnels.value = _activeTunnels.value.map {
                    if (it.port == port) it.copy(url = "Error: ${e.message}") else it
                }
            }
        }
    }

    fun stopTunnel(port: Int) {
        val tunnel = _activeTunnels.value.find { it.port == port } ?: return
        // Remove from UI immediately so the user sees it gone right away
        _activeTunnels.value = _activeTunnels.value.filter { it.port != port }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && tunnel.pid > 0) {
                    // Kill only the specific bore PID — does NOT affect the app's process group
                    Runtime.getRuntime().exec(arrayOf("/system/bin/kill", "-9", tunnel.pid.toString())).waitFor()
                    Log.d("Kodrix", "Killed bore PID=${tunnel.pid} for port $port")
                } else {
                    // Fallback for older APIs: destroy the process handle directly
                    // This is generally safe since ProcessBuilder gives us an isolated handle
                    tunnel.process.destroy()
                }
            } catch (e: Exception) {
                Log.e("Kodrix", "Failed to stop tunnel for port $port", e)
            }
        }
    }

    private fun runGit(vararg args: String): Process? {
        val proj = _activeProject.value ?: return null
        val projDir = java.io.File(projectsRoot, proj)
        val nativeLibPath = getApplication<Application>().applicationInfo.nativeLibraryDir
        val gitBin = "$nativeLibPath/libgit_bin.so"
        val libLinksDir = java.io.File(getApplication<Application>().filesDir, "lib").absolutePath
        val env = arrayOf(
            "GIT_EXEC_PATH=$nativeLibPath",
            "GIT_TEMPLATE_DIR=${getApplication<Application>().filesDir}/git_templates",
            "GIT_CONFIG_NOSYSTEM=1",
            "HOME=${getApplication<Application>().filesDir}",
            "LD_LIBRARY_PATH=$libLinksDir"
        )
        return try {
            Runtime.getRuntime().exec(arrayOf(gitBin) + args, env, projDir)
        } catch (e: Exception) {
            Log.e("Kodrix", "Git run failed: ${args.joinToString(" ")}", e)
            null
        }
    }

    fun refreshGitStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            val proj = _activeProject.value ?: return@launch
            val projDir = java.io.File(projectsRoot, proj)
            if (!java.io.File(projDir, ".git").exists()) {
                _gitChanges.value = emptyList()
                _gitLog.value = emptyList()
                _gitBranch.value = "Not a Git repo"
                return@launch
            }
            try {
                val branchProc = runGit("rev-parse", "--abbrev-ref", "HEAD")
                val branch = branchProc?.let { proc ->
                    try { proc.inputStream.bufferedReader().readText().trim() }
                    finally { proc.inputStream.close(); proc.errorStream.close(); proc.destroy() }
                } ?: ""
                _gitBranch.value = if (branch.isEmpty()) "Detached" else branch

                val statusProc = runGit("status", "--porcelain")
                val statusLines = statusProc?.let { proc ->
                    try { proc.inputStream.bufferedReader().readLines() }
                    finally { proc.inputStream.close(); proc.errorStream.close(); proc.destroy() }
                } ?: emptyList()
                _gitChanges.value = statusLines.map { line ->
                    val stagedStatus = line.take(1)
                    val unstagedStatus = line.substring(1, 2)
                    val path = line.substring(3).trim()
                    val isStaged = stagedStatus != " " && stagedStatus != "?"
                    val status = if (isStaged) stagedStatus.trim() else unstagedStatus.trim()
                    GitChange(path, if (status.isEmpty()) stagedStatus.trim() else status, isStaged)
                }

                val branchesProc = runGit("branch", "-a")
                val branches = branchesProc?.let { proc ->
                    try { proc.inputStream.bufferedReader().readLines() }
                    finally { proc.inputStream.close(); proc.errorStream.close(); proc.destroy() }
                } ?: emptyList()
                _gitBranches.value = branches.mapNotNull { line ->
                    val clean = line.replace("*", "").trim()
                    if (clean.contains("HEAD ->")) null else clean
                }.filter { it.isNotEmpty() }.distinct()

                val logProc = runGit("log", "--pretty=format:%h|%s|%an|%ar", "--max-count=30")
                val logLines = logProc?.let { proc ->
                    try { proc.inputStream.bufferedReader().readLines() }
                    finally { proc.inputStream.close(); proc.errorStream.close(); proc.destroy() }
                } ?: emptyList()
                _gitLog.value = logLines.mapNotNull { line ->
                    val parts = line.split("|")
                    if (parts.size >= 4) GitCommit(parts[0], parts[1], parts[2], parts[3]) else null
                }

                val remoteProc = runGit("remote", "get-url", "origin")
                val remoteUrl = remoteProc?.let { proc ->
                    try { proc.inputStream.bufferedReader().readText().trim() }
                    finally { proc.inputStream.close(); proc.errorStream.close(); proc.destroy() }
                } ?: ""
                _gitRemoteUrl.value = if (remoteUrl.isEmpty()) "No Remote" else remoteUrl
            } catch (e: Exception) {
                Log.e("Kodrix", "Git refresh failed", e)
            }

        }
    }

    fun gitStage(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runGit("add", path)?.waitFor()
            refreshGitStatus()
        }
    }

    fun gitUnstage(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runGit("reset", "HEAD", "--", path)?.waitFor()
            refreshGitStatus()
        }
    }

    fun gitDiscard(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runGit("checkout", "--", path)?.waitFor()
            refreshGitStatus()
        }
    }

    fun gitCommit(message: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _setupStatus.value = "Committing..."
            runGit("commit", "-m", message)?.waitFor()
            refreshGitStatus()
            _setupStatus.value = "Ready"
        }
    }

    fun gitPush() {
        viewModelScope.launch(Dispatchers.IO) {
            _setupStatus.value = "Pushing to origin..."
            runGit("push")?.waitFor()
            refreshGitStatus()
            _setupStatus.value = "Ready"
        }
    }

    fun gitFetch() {
        viewModelScope.launch(Dispatchers.IO) {
            _setupStatus.value = "Fetching from remote..."
            runGit("fetch")?.waitFor()
            refreshGitStatus()
            _setupStatus.value = "Ready"
        }
    }

    fun gitPull() {
        viewModelScope.launch(Dispatchers.IO) {
            _setupStatus.value = "Pulling from origin..."
            runGit("pull")?.waitFor()
            refreshGitStatus()
            _activeProject.value?.let { refreshFileTree(java.io.File(projectsRoot, it)) }
            _setupStatus.value = "Ready"
        }
    }

    fun gitCheckout(branch: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _setupStatus.value = "Switching to $branch..."
            runGit("stash")?.waitFor()
            val proc = runGit("checkout", branch)
            val exitCode = proc?.waitFor() ?: -1
            if (exitCode == 0) {
                runGit("stash", "pop")?.waitFor()
                refreshGitStatus()
                _activeProject.value?.let { refreshFileTree(java.io.File(projectsRoot, it)) }
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(getApplication(), "Switched to $branch", android.widget.Toast.LENGTH_SHORT).show()
                }
            } else {
                val error = proc?.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                runGit("stash", "pop")?.waitFor()
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(getApplication(), "Checkout failed: $error", android.widget.Toast.LENGTH_LONG).show()
                }
            }
            _setupStatus.value = "Ready"
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    private val _clipboardFile = MutableStateFlow<java.io.File?>(null)
    val clipboardFile = _clipboardFile.asStateFlow()
    private val _isCutMode = MutableStateFlow(false)
    val isCutMode = _isCutMode.asStateFlow()

    data class EditorTab(
        val file: java.io.File,
        var text: TextFieldValue,
        var isModified: Boolean = false
    )

    private val _openTabs = MutableStateFlow<List<EditorTab>>(emptyList())
    val openTabs = _openTabs.asStateFlow()
    private val _activeTabIndices = MutableStateFlow<Map<Int, Int>>(mapOf(0 to -1))
    val activeTabIndices = _activeTabIndices.asStateFlow()
    private val _focusedViewportId = MutableStateFlow(0)
    val focusedViewportId = _focusedViewportId.asStateFlow()
    private val _browserUrl = MutableStateFlow("https://google.com")
    val browserUrl = _browserUrl.asStateFlow()

    fun openInBrowser(url: String) {
        _browserUrl.value = url
        _sidebarMode.value = SidebarMode.BROWSER
    }

    fun stopDebugProcess() {
        val index = activeInstanceIndex.value
        instances.value.getOrNull(index)?.let { instance ->
            instance.sendInput("\u0003")
            appendLogcat("SYSTEM: Stop command sent (Ctrl+C) to session ${instance.id}")
        }
    }

    fun startDebugProcess() {
        togglePanel(true)
        val index = activeInstanceIndex.value
        instances.value.getOrNull(index)?.let { instance ->
            var command = "npm run dev\n"
            val proj = _activeProject.value
            if (proj != null) {
                val packageJson = java.io.File(projectsRoot, "$proj/package.json")
                if (packageJson.exists()) {
                    try {
                        val content = packageJson.readText()
                        if (content.contains("\"next\"")) {
                            command = "npm run dev -- --no-turbo\n"
                        }
                    } catch (e: Exception) {
                        Log.e("Kodrix", "Failed to read package.json", e)
                    }
                }
            }
            instance.sendInput(command)
            appendLogcat("SYSTEM: Executing debug command in terminal...")
        } ?: run {
            appendLogcat("SYSTEM: No active terminal to run debug process. Please open a terminal first.")
        }
    }

    fun closeBrowser() {
        _sidebarMode.value = SidebarMode.EXPLORER
    }

    fun updateFocusedViewport(id: Int) {
        _focusedViewportId.value = id
    }

    private val _previewFile = MutableStateFlow<java.io.File?>(null)
    val previewFile = _previewFile.asStateFlow()

    fun previewFile(file: java.io.File) {
        _previewFile.value = file
    }

    fun closePreview() {
        _previewFile.value = null
    }

    private val _problems = MutableStateFlow<List<IDEProblem>>(emptyList())
    val problems = _problems.asStateFlow()
    private val _outputLogs = MutableStateFlow("")
    val outputLogs = _outputLogs.asStateFlow()

    fun clearProblems() { _problems.value = emptyList() }
    fun clearOutput() { _outputLogs.value = "" }

    fun runTask(name: String, command: String) {
        val proj = _activeProject.value ?: return
        val projDir = java.io.File(projectsRoot, proj)
        val maxLogSize = 50000
        _outputLogs.value = (_outputLogs.value + "\n>>> Running task: $name [$command]\n").takeLast(maxLogSize)
        togglePanel(true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", command), null, projDir)
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        _outputLogs.value = (_outputLogs.value + line + "\n").takeLast(maxLogSize)
                        parseProblem(line, projDir)
                    }
                }
                proc.waitFor()
                _outputLogs.value += "<<< Task completed.\n"
            } catch (e: Exception) {
                _outputLogs.value += "ERROR: ${e.message}\n"
            } finally {
                manageBackgroundService()
            }
        }
        manageBackgroundService()
    }

    private fun parseProblem(line: String, root: java.io.File) {
        val regex = Regex("(.*?):(\\d+):(\\d+):\\s*(error|warning):\\s*(.*)", RegexOption.IGNORE_CASE)
        regex.find(line)?.let { match ->
            val path = match.groupValues[1]
            val lineNum = match.groupValues[2].toIntOrNull() ?: 0
            val colNum = match.groupValues[3].toIntOrNull() ?: 0
            val severity = match.groupValues[4].uppercase()
            val msg = match.groupValues[5]
            val file = if (path.startsWith("/")) java.io.File(path) else java.io.File(root, path)
            if (file.exists()) {
                val problem = IDEProblem(file, lineNum, colNum, msg, severity)
                _problems.value = _problems.value + problem
            }
        }
    }

    fun refreshProjects() {
        projectsRoot.mkdirs()
        _projects.value = (projectsRoot.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList())
        val current = _activeProject.value
        if (current != null && !java.io.File(projectsRoot, current).exists()) {
            _activeProject.value = null
            prefs.edit().remove("active_project").apply()
        }
    }

    fun toggleSidebar() {
        _sidebarOpen.value = !_sidebarOpen.value
        if (_sidebarOpen.value) {
            refreshProjects()
            _activeProject.value?.let { refreshFileTree(java.io.File(projectsRoot, it)) }
        }
    }

    fun openProject(name: String) {
        _activeProject.value = name
        prefs.edit().putString("active_project", name).apply()
        val projDir = java.io.File(projectsRoot, name)
        refreshFileTree(projDir)
        viewModelScope.launch(Dispatchers.IO) {
            _instances.value.forEach { it.sendInput("\r\ncd \"${projDir.absolutePath}\"\n") }
        }
        refreshGitStatus()
    }

    fun openFile(file: java.io.File, targetViewportId: Int? = null) {
        if (file.isDirectory) return
        val vid = targetViewportId ?: _focusedViewportId.value
        val existingIndex = _openTabs.value.indexOfFirst { it.file.absolutePath == file.absolutePath }
        if (existingIndex != -1) {
            switchTab(vid, existingIndex)
            // Notify LSP of focus change
            if (isLspSupported(file.extension)) startLsp(file)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val content = file.readText()
                withContext(Dispatchers.Main) {
                    val newTab = EditorTab(file, TextFieldValue(content, TextRange(0)))
                    _openTabs.value = _openTabs.value + newTab
                    switchTab(vid, _openTabs.value.size - 1)
                    // Start LSP for supported file types
                    if (isLspSupported(file.extension)) startLsp(file)
                }
            } catch (e: Exception) {
                Log.e("Kodrix", "Failed to read file", e)
            }
        }
    }

    fun switchTab(viewportId: Int, tabIndex: Int) {
        val current = _activeTabIndices.value.toMutableMap()
        current[viewportId] = tabIndex
        _activeTabIndices.value = current
        clearCompletions()
    }

    fun closeTab(index: Int) {
        val currentTabs = _openTabs.value.toMutableList()
        if (index in currentTabs.indices) {
            currentTabs.removeAt(index)
            _openTabs.value = currentTabs
            val currentIndices = _activeTabIndices.value.toMutableMap()
            currentIndices.forEach { (vid, tid) ->
                when {
                    tid == index -> {
                        currentIndices[vid] = if (currentTabs.isEmpty()) -1 else (tid - 1).coerceAtLeast(0)
                    }
                    tid > index -> {
                        currentIndices[vid] = tid - 1
                    }
                }
            }
            _activeTabIndices.value = currentIndices
            clearCompletions()
        }
    }

    fun splitViewport(sourceId: Int, horizontal: Boolean = true) {
        val newId = (_activeTabIndices.value.keys.maxOrNull() ?: 0) + 1
        val sourceTab = _activeTabIndices.value[sourceId] ?: -1
        val current = _activeTabIndices.value.toMutableMap()
        current[newId] = sourceTab
        _activeTabIndices.value = current
    }

    fun removeViewport(id: Int) {
        if (_activeTabIndices.value.size > 1) {
            val current = _activeTabIndices.value.toMutableMap()
            current.remove(id)
            _activeTabIndices.value = current
        }
    }

    fun updateEditorText(viewportId: Int, value: TextFieldValue) {
        val tabIndex = _activeTabIndices.value[viewportId] ?: return
        val currentTabs = _openTabs.value.toMutableList()
        if (tabIndex !in currentTabs.indices) return

        val oldText = currentTabs[tabIndex].text.text
        var finalValue = value

        // Smart Indentation Logic
        if (value.text.length == oldText.length + 1) {
            val cursorIdx = value.selection.end
            if (cursorIdx > 0 && value.text[cursorIdx - 1] == '\n') {
                // User pressed Enter
                val prevLineEnd = oldText.lastIndexOf('\n', cursorIdx - 2)
                val prevLine = if (prevLineEnd == -1) {
                    oldText.substring(0, cursorIdx - 1)
                } else {
                    oldText.substring(prevLineEnd + 1, cursorIdx - 1)
                }

                val indent = prevLine.takeWhile { it.isWhitespace() }
                var extraIndent = ""
                if (prevLine.trimEnd().endsWith("{") || prevLine.trimEnd().endsWith(":") || prevLine.trimEnd().endsWith(">")) {
                    extraIndent = "    " // 4 spaces
                }

                if (indent.isNotEmpty() || extraIndent.isNotEmpty()) {
                    val insertion = indent + extraIndent
                    val resultText = value.text.substring(0, cursorIdx) + insertion + value.text.substring(cursorIdx)
                    finalValue = value.copy(
                        text = resultText,
                        selection = androidx.compose.ui.text.TextRange(cursorIdx + insertion.length)
                    )
                }
            }
        }

        currentTabs[tabIndex] = currentTabs[tabIndex].copy(text = finalValue, isModified = true)
        _openTabs.value = currentTabs
        
        // Notify LSP of text change
        val file = currentTabs[tabIndex].file
        val ext = file.extension.lowercase()
        activeLSPs[ext]?.let { client ->
            viewModelScope.launch(Dispatchers.IO) {
                val changeParams = com.kodrix.zohaib.lsp.DidChangeTextDocumentParams(
                    textDocument = com.kodrix.zohaib.lsp.VersionedTextDocumentIdentifier(
                        uri = "file://${file.absolutePath}",
                        version = ++lspDocVersion
                    ),
                    contentChanges = listOf(
                        com.kodrix.zohaib.lsp.TextDocumentContentChangeEvent(text = finalValue.text)
                    )
                )
                client.notify("textDocument/didChange", changeParams)
                requestCompletion(file, finalValue.text, finalValue.selection.start)
            }
        }
    }

    fun applyCompletion(viewportId: Int, item: com.kodrix.zohaib.lsp.CompletionItem) {
        val tabIndex = _activeTabIndices.value[viewportId] ?: return
        val currentTabs = _openTabs.value.toMutableList()
        if (tabIndex in currentTabs.indices) {
            val tab = currentTabs[tabIndex]
            val currentText = tab.text.text
            
            val (newText, newCursor) = if (item.textEdit != null) {
                val start = getOffsetAt(currentText, item.textEdit!!.range.start.line, item.textEdit!!.range.start.character)
                val end = getOffsetAt(currentText, item.textEdit!!.range.end.line, item.textEdit!!.range.end.character)
                val replacement = item.textEdit!!.newText
                val t = currentText.substring(0, start) + replacement + currentText.substring(end)
                Pair(t, start + replacement.length)
            } else {
                val cursorOffset = tab.text.selection.start
                val insertText = item.insertText ?: item.label
                // Backtrack to find word start to avoid prefix duplication
                var start = cursorOffset
                while (start > 0 && (currentText[start - 1].isLetterOrDigit() || currentText[start - 1] == '_')) {
                    start--
                }
                val t = currentText.substring(0, start) + insertText + currentText.substring(cursorOffset)
                Pair(t, start + insertText.length)
            }
            
            val newValue = TextFieldValue(newText, TextRange(newCursor))
            currentTabs[tabIndex] = tab.copy(text = newValue, isModified = true)
            _openTabs.value = currentTabs
            
            _completionItems.value = emptyList() // Clear completions
            
            val file = tab.file
            val ext = file.extension.lowercase()
            activeLSPs[ext]?.let { client ->
                viewModelScope.launch(Dispatchers.IO) {
                    val changeParams = com.kodrix.zohaib.lsp.DidChangeTextDocumentParams(
                        textDocument = com.kodrix.zohaib.lsp.VersionedTextDocumentIdentifier(
                            uri = "file://${file.absolutePath}",
                            version = ++lspDocVersion
                        ),
                        contentChanges = listOf(
                            com.kodrix.zohaib.lsp.TextDocumentContentChangeEvent(text = newText)
                        )
                    )
                    client.notify("textDocument/didChange", changeParams)
                }
            }
        }
    }

    fun saveCurrentFile(viewportId: Int) {
        val tabIndex = _activeTabIndices.value[viewportId] ?: return
        val tab = _openTabs.value.getOrNull(tabIndex) ?: return
        val file = tab.file
        viewModelScope.launch(Dispatchers.IO) {
            try {
                file.writeText(tab.text.text)
                withContext(Dispatchers.Main) {
                    val updatedTabs = _openTabs.value.toMutableList()
                    updatedTabs[tabIndex] = updatedTabs[tabIndex].copy(isModified = false)
                    _openTabs.value = updatedTabs
                    android.widget.Toast.makeText(getApplication(), "Saved: ${file.name}", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("Kodrix", "Failed to save file", e)
            }
        }
    }

    fun closeFile() {
        // Legacy compat
    }

    fun createProject(name: String) {
        val trimmed = name.trim().replace(" ", "-")
        if (trimmed.isEmpty()) return
        java.io.File(projectsRoot, trimmed).mkdirs()
        refreshProjects()
        openProject(trimmed)
        refreshGitStatus()
    }

    fun deleteProject(name: String) {
        java.io.File(projectsRoot, name).deleteRecursively()
        if (_activeProject.value == name) _activeProject.value = null
        refreshProjects()
    }

    private val CLONE_NOTIF_ID = 1001
    private val CLONE_CHANNEL_ID = "kodrix_clone"
    private var lastCloneNotifTime = 0L

    private fun updateCloneNotification(progress: Int, title: String) {
        val context = getApplication<android.app.Application>()
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(CLONE_CHANNEL_ID, "Git Clone", android.app.NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }
        
        if (progress < 0) {
            manager.cancel(CLONE_NOTIF_ID)
            return
        }

        val now = System.currentTimeMillis()
        if (progress in 0..99 && now - lastCloneNotifTime < 500) return
        lastCloneNotifTime = now

        val builder = androidx.core.app.NotificationCompat.Builder(context, CLONE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (progress >= 100) "Clone Successful ✅" else "Cloning Project")
            .setContentText(title)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)

        if (progress >= 100) {
            builder.setProgress(0, 0, false)
            builder.setOngoing(false)
            builder.setAutoCancel(true)
        } else {
            builder.setProgress(100, progress, false)
            builder.setOngoing(true)
        }
            
        manager.notify(CLONE_NOTIF_ID, builder.build())
    }

    fun cloneProject(url: String, name: String, user: String, token: String) {
        val trimmedName = name.trim().replace(" ", "-")
        if (trimmedName.isEmpty() || url.isEmpty()) return
        val projDir = java.io.File(projectsRoot, trimmedName)
        if (projDir.exists()) {
            android.widget.Toast.makeText(getApplication(), "Project already exists", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        projDir.mkdirs()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _cloneProgress.value = 0
                _setupStatus.value = "Preparing clone..."
                val context = getApplication<android.app.Application>()
                com.kodrix.zohaib.bridge.PtyBridge().setupEnvironment(context)
                val filesDir = context.filesDir.absolutePath
                val logFile = java.io.File(filesDir, "git_clone.log")
                logFile.writeText("Clone started at ${java.util.Date()}\n")
                val usrDir = "$filesDir/usr"
                val usrBinDir = "$usrDir/bin"
                val usrEtcDir = "$usrDir/etc"
                val nativeLibPath = context.applicationInfo.nativeLibraryDir
                val gitBinary = "$usrBinDir/git"
                logFile.appendText("Binary: $gitBinary (exists: ${java.io.File(gitBinary).exists()})\n")
                val authUrl = if (user.isNotEmpty() && token.isNotEmpty()) {
                    val encodedToken = java.net.URLEncoder.encode(token, "UTF-8")
                    url.replace("https://", "https://$user:$encodedToken@")
                } else url
                logFile.appendText("URL: $url\n")
                val pb = ProcessBuilder(
                    if (java.io.File(gitBinary).exists()) gitBinary else "$nativeLibPath/libgit_bin.so",
                    "clone", "--progress", authUrl, projDir.absolutePath
                )
                val env = pb.environment()
                val libLinksDir = java.io.File(context.filesDir, "lib").absolutePath
                env["PATH"] = "$usrBinDir:/system/bin:/system/xbin"
                env["LD_LIBRARY_PATH"] = "$libLinksDir"
                env["HOME"] = filesDir
                env["GIT_TEMPLATE_DIR"] = "$filesDir/git_templates"
                env["GIT_CONFIG_NOSYSTEM"] = "1"
                env["GIT_CONFIG_GLOBAL"] = "$usrEtcDir/gitconfig"
                env["GIT_EXEC_PATH"] = usrBinDir
                env["GIT_SSL_NO_VERIFY"] = "true"
                pb.redirectErrorStream(true)
                val process = pb.start()
                val reader = process.inputStream.bufferedReader()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val currentLine = line ?: ""
                    logFile.appendText("> $currentLine\n")
                    val match = Regex("(\\d+)%").find(currentLine)
                    if (match != null) {
                        val percent = match.groupValues[1]
                        val phase = if (currentLine.contains("Receiving")) "Receiving" else if (currentLine.contains("Resolving")) "Resolving" else "Cloning"
                        _setupStatus.value = "$phase: $percent%"
                        _cloneProgress.value = percent.toIntOrNull() ?: 0
                        updateCloneNotification(_cloneProgress.value, _setupStatus.value)
                    } else {
                        _setupStatus.value = "Cloning: $currentLine"
                        updateCloneNotification(0, _setupStatus.value)
                    }
                }
                val exitCode = process.waitFor()
                withContext(Dispatchers.Main) {
                    if (exitCode == 0) {
                        android.widget.Toast.makeText(getApplication(), "Clone Successful!", android.widget.Toast.LENGTH_LONG).show()
                        refreshProjects()
                        openProject(trimmedName)
                        updateCloneNotification(100, "Successfully cloned $trimmedName")
                    } else {
                        projDir.deleteRecursively()
                        android.widget.Toast.makeText(getApplication(), "Clone Failed (Exit $exitCode)", android.widget.Toast.LENGTH_LONG).show()
                        updateCloneNotification(-1, "")
                    }
                    _setupStatus.value = "Ready"
                    _cloneProgress.value = 0
                }
            } catch (e: Exception) {
                Log.e("Kodrix", "Clone failed", e)
                withContext(Dispatchers.Main) {
                    projDir.deleteRecursively()
                    android.widget.Toast.makeText(getApplication(), "Clone Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                    _setupStatus.value = "Ready"
                    _cloneProgress.value = 0
                    updateCloneNotification(-1, "")
                }
            }
        }
    }

    fun refreshFileTree(root: java.io.File) {
        val tree = mutableMapOf<String, List<java.io.File>>()
        fun scan(dir: java.io.File) {
            val children = dir.listFiles()
                ?.filter { !it.name.startsWith(".") && it.name != "node_modules" }
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                ?: emptyList()
            tree[dir.absolutePath] = children
            children.filter { it.isDirectory }.forEach { scan(it) }
        }
        scan(root)
        _fileTree.value = tree
    }

    fun toggleDir(path: String) {
        _expandedDirs.value = if (_expandedDirs.value.contains(path))
            _expandedDirs.value - path else _expandedDirs.value + path
    }

    fun createFile(parent: java.io.File, name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                java.io.File(parent, name).createNewFile()
                _activeProject.value?.let { refreshFileTree(java.io.File(projectsRoot, it)) }
            } catch (e: Exception) {
                Log.e("Kodrix", "Failed to create file", e)
            }
        }
    }

    fun createFolder(parent: java.io.File, name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                java.io.File(parent, name).mkdirs()
                _activeProject.value?.let { refreshFileTree(java.io.File(projectsRoot, it)) }
            } catch (e: Exception) {
                Log.e("Kodrix", "Failed to create folder", e)
            }
        }
    }

    fun renameFile(file: java.io.File, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val newFile = java.io.File(file.parentFile, newName)
                file.renameTo(newFile)
                _activeProject.value?.let { refreshFileTree(java.io.File(projectsRoot, it)) }
                val updatedTabs = _openTabs.value.map {
                    if (it.file == file) it.copy(file = newFile) else it
                }
                _openTabs.value = updatedTabs
            } catch (e: Exception) {
                Log.e("Kodrix", "Failed to rename file", e)
            }
        }
    }

    fun deleteFile(file: java.io.File) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                file.deleteRecursively()
                _activeProject.value?.let { refreshFileTree(java.io.File(projectsRoot, it)) }
                val index = _openTabs.value.indexOfFirst { it.file == file }
                if (index != -1) {
                    withContext(Dispatchers.Main) {
                        closeTab(index)
                    }
                }
            } catch (e: Exception) {
                Log.e("Kodrix", "Failed to delete file", e)
            }
        }
    }

    fun setClipboard(file: java.io.File, cut: Boolean) {
        _clipboardFile.value = file
        _isCutMode.value = cut
    }

    fun pasteFile(targetDir: java.io.File) {
        val source = _clipboardFile.value ?: return
        val dest = java.io.File(targetDir, source.name)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (_isCutMode.value) {
                    source.renameTo(dest)
                    _clipboardFile.value = null
                } else {
                    source.copyRecursively(dest, overwrite = true)
                }
                _activeProject.value?.let { refreshFileTree(java.io.File(projectsRoot, it)) }
            } catch (e: Exception) {
                Log.e("Kodrix", "Failed to paste file", e)
            }
        }
    }

    fun openInTerminal(file: java.io.File) {
        val dir = if (file.isDirectory) file else file.parentFile ?: return
        togglePanel(true)
        viewModelScope.launch(Dispatchers.IO) {
            _instances.value.getOrNull(_activeInstanceIndex.value)?.sendInput("\r\ncd \"${dir.absolutePath}\"\n")
        }
    }

    init {
        refreshProjects()
        val current = _activeProject.value
        if (current != null) {
            refreshFileTree(java.io.File(projectsRoot, current))
            refreshGitStatus()
        }
        addNewTerminal()
        _isReady.value = true
    }

    // ── FIXED: Multi-terminal fix using instanceHolder pattern ────────────────
    fun addNewTerminal() {
        val nextId = (_instances.value.maxByOrNull { it.id }?.id ?: 0) + 1
        val projDir = _activeProject.value?.let { java.io.File(projectsRoot, it) }
        val cwd = projDir?.absolutePath ?: "/"

        // Use holder so the client callback can reference the instance
        var instanceHolder: TerminalInstance? = null

        val client = object : com.termux.terminal.TerminalSessionClient {
            override fun onTextChanged(changedSession: com.termux.terminal.TerminalSession) {
                instanceHolder?.boundView?.postInvalidate()
                // Detect gh device auth one-time code in terminal output
                val screenText = changedSession.emulator?.screen?.transcriptText ?: return
                val match = GH_CODE_REGEX.find(screenText)
                if (match != null) {
                    val code = match.groupValues[1]
                    if (code != lastDetectedGhCode) {
                        lastDetectedGhCode = code
                        _ghDeviceCode.value = code
                    }
                }
            }
            override fun onTitleChanged(changedSession: com.termux.terminal.TerminalSession) {}
            override fun onSessionFinished(finishedSession: com.termux.terminal.TerminalSession) {}
            override fun onCopyTextToClipboard(session: com.termux.terminal.TerminalSession, text: String) {}
            override fun onPasteTextFromClipboard(session: com.termux.terminal.TerminalSession) {}
            override fun onBell(session: com.termux.terminal.TerminalSession) {}
            override fun onColorsChanged(session: com.termux.terminal.TerminalSession) {}
            override fun onTerminalCursorStateChange(state: Boolean) {}
            override fun getTerminalCursorStyle(): Int { return com.termux.terminal.TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK }
            override fun logError(tag: String, message: String) {}
            override fun logWarn(tag: String, message: String) {}
            override fun logInfo(tag: String, message: String) {}
            override fun logDebug(tag: String, message: String) {}
            override fun logVerbose(tag: String, message: String) {}
            override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
            override fun logStackTrace(tag: String, e: Exception) {}
        }

        val context = getApplication<android.app.Application>().applicationContext
        com.kodrix.zohaib.bridge.PtyBridge().setupEnvironment(context)

        val binDir = context.filesDir.absolutePath
        val nativeLibPath = context.applicationInfo.nativeLibraryDir
        val libLinksDir = java.io.File(context.filesDir, "lib").absolutePath

        // Inject the active Node.js version's bin/ and lib/ dirs if available
        val activeNodeVersion = binaryManager.getActiveVersion("node")
        val activeNodeBinDir = if (activeNodeVersion != null) {
            java.io.File(context.filesDir, "versions/node/$activeNodeVersion/bin").absolutePath
        } else null
        val activeNodeLibDir = if (activeNodeVersion != null) {
            java.io.File(context.filesDir, "versions/node/$activeNodeVersion/lib").absolutePath
        } else null

        val newPath = buildString {
            if (activeNodeBinDir != null) append("$activeNodeBinDir:")
            append("$binDir/usr/bin:$binDir/bin:$binDir/usr/git-exec:/system/bin:/system/xbin:/vendor/bin")
        }
        val envPath = "$binDir/init.sh"

        val envList = mutableListOf(
            "PATH=$newPath",
            "TERM=xterm-256color",
            "HOME=$cwd",
            "APP_FILES_DIR=$binDir",
            "APP_LIB_DIR=$libLinksDir",
            "GIT_EXEC_PATH=$nativeLibPath",
            "ENV=$envPath"
        )
        if (activeNodeLibDir != null) envList.add("LD_LIBRARY_PATH=$activeNodeLibDir:$libLinksDir")
        else envList.add("LD_LIBRARY_PATH=$libLinksDir")
        _githubUser.value?.let { envList.add("KODRIX_GH_USER=$it") }
        _githubToken.value?.let { envList.add("KODRIX_GH_TOKEN=$it") }

        val env = envList.toTypedArray()

        val instance = TerminalInstance(nextId, "sh", cwd, arrayOf("-i", "-l"), env, client)
        instanceHolder = instance // Link holder to instance

        _instances.value = _instances.value + instance
        _activeInstanceIndex.value = _instances.value.size - 1
        _setupStatus.value = "Starting Shell $nextId..."
        _isReady.value = true
        installLSPsIfNeeded() // Now node is definitely extracted
        manageBackgroundService()
    }

    fun switchTerminal(index: Int) { _activeInstanceIndex.value = index }

    fun removeTerminal(index: Int) {
        if (_instances.value.size > 1) {
            val newList = _instances.value.toMutableList()
            val removed = newList.removeAt(index)
            try { removed.session.finishIfRunning() } catch (_: Exception) {}
            removed.boundView = null
            _instances.value = newList
            _activeInstanceIndex.value = (_activeInstanceIndex.value).coerceAtMost(newList.size - 1)
            manageBackgroundService()
        }
    }

    fun sendInput(data: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _instances.value.getOrNull(_activeInstanceIndex.value)?.sendInput(data)
        }
    }

    fun sendSpecialKey(key: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val activeInstance = _instances.value.getOrNull(_activeInstanceIndex.value)
            activeInstance?.let { instance ->
                val code = when (key) {
                    "ESC" -> "\u001b"
                    "UP" -> "\u001b[A"
                    "DOWN" -> "\u001b[B"
                    "RIGHT" -> "\u001b[C"
                    "LEFT" -> "\u001b[D"
                    "HOME" -> "\u001b[1~"
                    "END" -> "\u001b[4~"
                    "PGUP" -> "\u001b[5~"
                    "PGDN" -> "\u001b[6~"
                    "TAB" -> "\t"
                    else -> key
                }
                instance.sendInput(code)
            }
        }
    }


    fun updateFontSize(delta: Int) {
        _fontSize.value = (_fontSize.value + delta).coerceIn(8, 30)
        prefs.edit().putInt("font_size", _fontSize.value).apply()
    }

    fun updateEditorFontSize(delta: Int) {
        _editorFontSize.value = (_editorFontSize.value + delta).coerceIn(8, 40)
        prefs.edit().putInt("editor_font_size", _editorFontSize.value).apply()
    }

    fun updateUIScale(delta: Float) {
        _uiScale.value = (_uiScale.value + delta).coerceIn(0.7f, 1.5f)
        prefs.edit().putFloat("ui_scale", _uiScale.value).apply()
    }

    fun updatePanelHeight(delta: Dp) { _panelHeight.value = (_panelHeight.value + delta).coerceIn(100.dp, 500.dp) }
    fun togglePanel(visible: Boolean) { _isPanelVisible.value = visible }
    fun toggleSidebar(visible: Boolean) { _sidebarOpen.value = visible }

    fun toggleAutoScroll() { _autoScroll.value = !_autoScroll.value }

    fun importProjectZip(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val tempZip = java.io.File(context.cacheDir, "import.zip")
                    tempZip.outputStream().use { output -> input.copyTo(output) }
                    ZipUtils.unzip(tempZip, projectsRoot)
                    tempZip.delete()
                    withContext(Dispatchers.Main) {
                        refreshProjects()
                        android.widget.Toast.makeText(context, "Project Imported", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("Kodrix", "Import failed", e)
            }
        }
    }

    fun exportProjectZip(projectName: String, uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val projDir = java.io.File(projectsRoot, projectName)
                val tempZip = java.io.File(context.cacheDir, "export.zip")
                ZipUtils.zipDirectory(projDir, tempZip)
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    tempZip.inputStream().use { input -> input.copyTo(output) }
                }
                tempZip.delete()
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Project Exported", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("Kodrix", "Export failed", e)
            }
        }
    }

    fun importFile(targetDir: java.io.File, uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    cursor.moveToFirst()
                    cursor.getString(nameIndex)
                } ?: "uploaded_file"
                context.contentResolver.openInputStream(uri)?.use { input ->
                    java.io.File(targetDir, fileName).outputStream().use { output -> input.copyTo(output) }
                }
                _activeProject.value?.let { refreshFileTree(java.io.File(projectsRoot, it)) }
            } catch (e: Exception) {
                Log.e("Kodrix", "File import failed", e)
            }
        }
    }

    fun exportFile(file: java.io.File, uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                }
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "File Exported", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("Kodrix", "File export failed", e)
            }
        }
    }

    fun copyTerminalLogs(index: Int) {
        val instance = _instances.value.getOrNull(index) ?: return
        val text = instance.getAllOutput()
        val context = getApplication<Application>()
        val clipboardManager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboardManager.setPrimaryClip(android.content.ClipData.newPlainText("Terminal Logs", text))
        android.widget.Toast.makeText(context, "Logs copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
    }

    fun searchNpm(query: String) {
        if (query.isEmpty()) {
            _npmResults.value = emptyList()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _isNpmSearching.value = true
            var connection: java.net.HttpURLConnection? = null
            try {
                val url = java.net.URL("https://registry.npmjs.org/-/v1/search?text=${java.net.URLEncoder.encode(query, "UTF-8")}&size=20")
                val conn = url.openConnection() as java.net.HttpURLConnection
                connection = conn
                conn.setRequestProperty("User-Agent", "Kodrix-IDE-App")
                if (conn.responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    val objects = json.getJSONArray("objects")
                    val results = mutableListOf<NpmPackage>()
                    for (i in 0 until objects.length()) {
                        val pkg = objects.getJSONObject(i).getJSONObject("package")
                        results.add(NpmPackage(
                            name = pkg.getString("name"),
                            version = pkg.getString("version"),
                            description = pkg.optString("description", "No description"),
                            author = pkg.optJSONObject("author")?.optString("name") ?: pkg.optString("publisher", "Unknown"),
                            date = pkg.optString("date", "")
                        ))
                    }
                    _npmResults.value = results
                }
            } catch (e: Exception) {
                Log.e("Kodrix", "NPM Search failed", e)
            } finally {
                connection?.disconnect()
                _isNpmSearching.value = false
            }
        }
    }

    fun installNpmPackage(packageName: String) {
        runTask("NPM Install", "npm install $packageName")
        _sidebarMode.value = SidebarMode.EXPLORER
        _isPanelVisible.value = true
    }

    // ── Python Onboarding Setup ───────────────────────────────────────────────
    private val _showPythonOnboarding = MutableStateFlow(
        prefs.getBoolean("first_time_python_prompt", true)
    )
    val showPythonOnboarding = _showPythonOnboarding.asStateFlow()

    fun dismissPythonOnboarding() {
        prefs.edit().putBoolean("first_time_python_prompt", false).apply()
        _showPythonOnboarding.value = false
    }

    fun installPythonInBackground() {
        viewModelScope.launch(Dispatchers.IO) {
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val url = when (abi) {
                "armeabi-v7a" -> "https://github.com/Zohaib8090/KodrixMarketplace/releases/download/v1.0/python-3.13.13-armeabi-v7a.zip"
                "x86" -> "https://github.com/Zohaib8090/KodrixMarketplace/releases/download/v1.0/python-3.13.13-x86.zip"
                "x86_64" -> "https://github.com/Zohaib8090/KodrixMarketplace/releases/download/v1.0/python-3.13.13-x86_64.zip"
                else -> "https://github.com/Zohaib8090/KodrixMarketplace/releases/download/v1.0/python-3.13.13-arm64-v8a.zip"
            }
            
            try {
                // Download and extract
                binaryManager.downloadVersion("python", "3.13.13", url)
                // Set as active
                binaryManager.setActiveVersion("python", "3.13.13")
                
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        getApplication(),
                        "✅ Python 3.13.13 installed & activated successfully!",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Log.e("TerminalViewModel", "Failed to install Python in background", e)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        getApplication(),
                        "❌ Background Python installation failed: ${e.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        aiManager.stopEngine()
        // Stop foreground service
        val intent = Intent(getApplication(), KodrixService::class.java)
        getApplication<Application>().stopService(intent)

        // Destroy logcat process
        logcatProcess?.destroy()
        logcatProcess = null
        // Destroy all terminal sessions
        _instances.value.forEach { instance ->
            try { instance.session.finishIfRunning() } catch (_: Exception) {}
        }
        // Destroy all tunnel processes
        _activeTunnels.value.forEach { tunnel ->
            try { tunnel.process.destroy() } catch (_: Exception) {}
        }
        _activeTunnels.value = emptyList()
    }
}