package com.kodrix.zohaib.ai

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AgentRole(val displayName: String, val header: String) {
    CEO("CEO (Strategist)", "# CEO Workspace"),
    TASK_ASSIGNER("Task Assigner", "# Task Assigner Workspace"),
    DEV("Developer (Coder)", "# Developer Workspace"),
    DEBUGGER("Debugger (QA)", "# Debugger Workspace"),
    LOGGER("Logger (Operations)", "# Logger Workspace")
}

class AgentOrchestrator(
    private val aiManager: com.kodrix.zohaib.ai.AIBackendManager,
    private val binaryManager: com.kodrix.zohaib.bridge.BinaryManager
) {

    private val scope = CoroutineScope(Dispatchers.IO)
    private var loopJob: Job? = null

    private val _workspaces = MutableStateFlow<Map<AgentRole, String>>(emptyMap())
    val workspaces = _workspaces.asStateFlow()

    private val _activeRole = MutableStateFlow(AgentRole.CEO)
    val activeRole = _activeRole.asStateFlow()

    private val _goal = MutableStateFlow("")
    val goal = _goal.asStateFlow()

    private val _isAgentMode = MutableStateFlow(false)
    val isAgentMode = _isAgentMode.asStateFlow()

    private val _isFastMode = MutableStateFlow(false)
    val isFastMode = _isFastMode.asStateFlow()

    private val _orchestratorState = MutableStateFlow("Idle")
    val orchestratorState = _orchestratorState.asStateFlow()

    private val _pendingQuestion = MutableStateFlow<String?>(null)
    val pendingQuestion = _pendingQuestion.asStateFlow()

    private val _activeProject = MutableStateFlow<String?>(null)
    val activeProject = _activeProject.asStateFlow()

    private val maxCycles = 15
    private var currentCycle = 1

    fun toggleAgentMode(enabled: Boolean) {
        _isAgentMode.value = enabled
        if (!enabled) {
            stopLoop()
        }
    }

    fun startGoal(goalText: String) {
        if (!_isAgentMode.value) return
        stopLoop()

        _goal.value = goalText
        _workspaces.value = AgentRole.values().associateWith { role ->
            if (role == AgentRole.CEO) "# CEO Workspace\n**Goal:** $goalText\n\n## CEO Plan\n[Planning...]"
            else "# ${role.displayName} Workspace\n[Waiting for CEO...]"
        }
        _activeRole.value = AgentRole.CEO
        currentCycle = 1

        loopJob = scope.launch {
            runLoop()
        }
    }

    fun setActiveRole(role: AgentRole) {
        _activeRole.value = role
    }

    fun updateActiveProject(projectName: String?) {
        _activeProject.value = projectName
    }

    fun stopLoop() {
        loopJob?.cancel()
        loopJob = null
        _orchestratorState.value = "Stopped"
        _pendingQuestion.value = null
        
        // Reset workspaces to clean state
        _workspaces.value = AgentRole.values().associateWith { role ->
            "# ${role.displayName} Workspace\n[Empty]"
        }
    }

    fun answerQuestion(answer: String) {
        val q = _pendingQuestion.value ?: return
        val active = _activeRole.value
        val currentWs = _workspaces.value[active] ?: ""
        
        val updatedWs = "$currentWs\n\n**User Answer:** $answer"
        val updatedWorkspaces = _workspaces.value.toMutableMap()
        updatedWorkspaces[active] = updatedWs
        _workspaces.value = updatedWorkspaces
        
        _pendingQuestion.value = null
        
        // Resume loop
        loopJob = scope.launch {
            runLoop()
        }
    }

    private suspend fun runLoop() {
        var nextRole = _activeRole.value

        while (currentCycle <= maxCycles) {
            _orchestratorState.value = "Cycle $currentCycle/$maxCycles: ${nextRole.displayName} running..."
            _activeRole.value = nextRole

            val fullWorkspace = buildFullWorkspace()
            val prompt = buildPrompt(nextRole, currentCycle, maxCycles, fullWorkspace)
            
            // Call AI
            val response = aiManager.ask(prompt) ?: ""
            if (response == "NEEDS_LOGIN") {
                _orchestratorState.value = "Error: Needs Login"
                break
            }

            // Parse and update specific agent workspace
            val updatedWorkspaces = _workspaces.value.toMutableMap()
            updatedWorkspaces[nextRole] = response
            _workspaces.value = updatedWorkspaces

            // Handle Tool Calls in the response
            handleToolCalls(response, nextRole)

            // Check for questions in the response
            if (response.contains("## Questions", ignoreCase = true)) {
                val q = extractSection(response, "## Questions")
                if (q.isNotBlank() && q != "[Empty]" && !q.contains("**User Answer:**")) {
                    _pendingQuestion.value = q
                    _orchestratorState.value = "Paused: Waiting for User"
                    break 
                }
            }

            // Determine next role from the response
            val explicitNext = extractNextAgentFromResponse(response)
            var actualNext = nextRole
            if (explicitNext != null) {
                actualNext = explicitNext
            } else if (nextRole == AgentRole.CEO) {
                actualNext = AgentRole.TASK_ASSIGNER
            } else if (nextRole == AgentRole.TASK_ASSIGNER) {
                actualNext = AgentRole.DEV
            } else if (nextRole == AgentRole.DEV) {
                actualNext = AgentRole.CEO
            }

            // UI Hint: Mark the next agent as starting
            if (actualNext != nextRole) {
                val handoffWorkspaces = _workspaces.value.toMutableMap()
                handoffWorkspaces[actualNext] = "## ${actualNext.displayName}\n[Agent is starting to think...]"
                _workspaces.value = handoffWorkspaces
            }
            
            nextRole = actualNext

            if (nextRole == AgentRole.CEO && (response.contains("**Status:** Complete", ignoreCase = true) || response.contains("**Status:** Done", ignoreCase = true))) {
                _orchestratorState.value = "Goal Achieved!"
                break
            }

            currentCycle++
            
            val loopDelay = if (_isFastMode.value) 500L else 2000L
            delay(loopDelay)
        }
        
        if (currentCycle > maxCycles) {
            _orchestratorState.value = "Max cycles reached. Stopped."
        }
    }

    private fun buildFullWorkspace(): String {
        return _workspaces.value.entries.joinToString("\n\n---\n\n") { (role, content) ->
            "### ${role.displayName} Workspace\n$content"
        }
    }

    fun toggleFastMode(enabled: Boolean) {
        _isFastMode.value = enabled
    }

    private suspend fun handleToolCalls(response: String, role: AgentRole) {
        val toolRegex = Regex("<(\\w+)\\s+([^>]*?)>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL)
        val matches = toolRegex.findAll(response)
        
        if (matches.none()) return

        val results = StringBuilder("\n\n### Tool Execution Results (${role.displayName})\n")
        
        for (match in matches) {
            val toolName = match.groupValues[1]
            val paramsStr = match.groupValues[2]
            val content = match.groupValues[3].trim()
            
            val params = paramsStr.split(" ").associate { 
                val parts = it.split("=")
                if (parts.size == 2) parts[0] to parts[1].removeSurrounding("\"")
                else it to ""
            }

            val result = when (toolName) {
                "create_file" -> createFile(params["path"], content)
                "read_file" -> readFile(params["path"])
                "list_dir" -> listDir(params["path"])
                "execute_command" -> executeCommand(content)
                "get_logs" -> getLogs(params["tag"], params["limit"]?.toIntOrNull() ?: 50)
                else -> "Error: Unknown tool '$toolName'"
            }
            results.append("- **$toolName**: $result\n")
        }

        // Add results to the shared workspace so the next agent sees them
        val updatedWorkspaces = _workspaces.value.toMutableMap()
        val currentLogs = updatedWorkspaces[AgentRole.LOGGER] ?: ""
        updatedWorkspaces[AgentRole.LOGGER] = "$currentLogs\n$results"
        _workspaces.value = updatedWorkspaces
    }

    private fun createFile(path: String?, content: String): String {
        if (path == null) return "Error: Missing path"
        return try {
            val baseDir = if (_activeProject.value != null) {
                java.io.File(aiManager.application.filesDir, "projects/${_activeProject.value}")
            } else aiManager.application.filesDir
            
            val file = java.io.File(baseDir, path)
            file.parentFile?.mkdirs()
            file.writeText(content)
            "Success: Created file at $path"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun readFile(path: String?): String {
        if (path == null) return "Error: Missing path"
        return try {
            val baseDir = if (_activeProject.value != null) {
                java.io.File(aiManager.application.filesDir, "projects/${_activeProject.value}")
            } else aiManager.application.filesDir
            
            val file = java.io.File(baseDir, path)
            if (file.exists()) "Content of $path:\n```\n${file.readText()}\n```"
            else "Error: File not found at $path"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun listDir(path: String?): String {
        val baseDir = if (_activeProject.value != null) {
            java.io.File(aiManager.application.filesDir, "projects/${_activeProject.value}")
        } else aiManager.application.filesDir
        
        val dir = java.io.File(baseDir, path ?: "")
        return try {
            val files = dir.listFiles()?.joinToString("\n") { 
                if (it.isDirectory) "[DIR] ${it.name}" else "[FILE] ${it.name}"
            } ?: "Empty"
            "Contents of ${path ?: "root"}:\n$files"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private suspend fun executeCommand(cmd: String): String {
        return try {
            val baseDir = if (_activeProject.value != null) {
                java.io.File(aiManager.application.filesDir, "projects/${_activeProject.value}")
            } else aiManager.application.filesDir
            
            val pb = ProcessBuilder("/system/bin/sh", "-c", cmd)
            pb.directory(baseDir)
            val env = pb.environment()
            val filesDir = aiManager.application.filesDir.absolutePath
            val nativeLibPath = aiManager.application.applicationInfo.nativeLibraryDir
            
            // Critical: Ensure agents have access to our standardized binaries
            // Inject versioned paths if managed by BinaryManager
            val nodeBinPath = binaryManager.getBinaryPath("node")?.let { java.io.File(it).parent }
            val nodeLibPath = binaryManager.getLibPath("node")
            
            val pathPrefix = if (nodeBinPath != null) "$nodeBinPath:" else ""
            val libPrefix = if (nodeLibPath != null) "$nodeLibPath:" else ""

            env["PATH"] = "$pathPrefix$filesDir/usr/bin:$filesDir/bin:/system/bin:/system/xbin"
            env["LD_LIBRARY_PATH"] = "$libPrefix$nativeLibPath:$filesDir/lib"
            env["HOME"] = filesDir
            env["USER"] = "kodrix"
            
            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            process.waitFor()
            
            "Exit ${process.exitValue()}\nOutput: $output\nError: $error"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun getLogs(tag: String?, limit: Int): String {
        return try {
            val cmd = if (tag != null) "logcat -d -s $tag -t $limit" else "logcat -d -t $limit"
            val process = Runtime.getRuntime().exec(cmd)
            val output = process.inputStream.bufferedReader().readText()
            "Logs (limit $limit):\n```\n$output\n```"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun buildPrompt(role: AgentRole, cycle: Int, maxCycles: Int, workspace: String): String {
        val isFast = _isFastMode.value
        val sb = StringBuilder()
        sb.append("You are the ${role.displayName} in a professional AI team.\n")
        sb.append("The User is the CTO. You report to the CEO and the CTO.\n")
        val proj = _activeProject.value
        if (proj != null) {
            sb.append("ACTIVE PROJECT: $proj\n")
            sb.append("All tools (create_file, etc.) are relative to the project root: /files/projects/$proj/\n\n")
        } else {
            sb.append("NO ACTIVE PROJECT: Tools are relative to /files/\n\n")
        }
        
        if (isFast) {
            sb.append("FAST MODE ACTIVE: Be extremely direct. Use tools immediately. Skip summaries. No conversational filler.\n\n")
        }

        sb.append("TOOLS ACCESS (XML syntax):\n")
        sb.append("- `<create_file path=\"path\">content</create_file>`\n")
        sb.append("- `<read_file path=\"path\"></read_file>`\n")
        sb.append("- `<list_dir path=\"path\"></list_dir>`\n")
        sb.append("- `<execute_command>cmd</execute_command>`\n")
        sb.append("- `<get_logs tag=\"tag\" limit=\"50\"></get_logs>`\n\n")
        
        sb.append("SHARED WORKSPACE:\n```markdown\n$workspace\n```\n\n")
        
        when (role) {
            AgentRole.CEO -> {
                sb.append("CEO ROLE: STRATEGY and DELEGATION. Review reports and tool results.\n")
                sb.append("1. If goal is unclear, ask CTO via '## Questions'.\n")
                sb.append("2. Research code structure using tools if needed.\n")
                sb.append("3. Write a plan and assign the next agent.\n")
                if (isFast) sb.append("FAST MODE: Skip deep research. Go straight to TASK_ASSIGNER if path is clear.\n")
                sb.append("At the end, you MUST specify: **Next Agent:** [TASK_ASSIGNER, DEV, DEBUGGER, or DONE]\n")
            }
            AgentRole.TASK_ASSIGNER -> {
                sb.append("TASK ASSIGNER ROLE: Break the CEO's plan into code-level tasks.\n")
                sb.append("Assign work to the DEV agent immediately.\n")
                sb.append("At the end, you MUST specify: **Next Agent:** DEV\n")
            }
            AgentRole.DEV -> {
                sb.append("DEV ROLE: Execute tasks. Write code using `<create_file>`.\n")
                sb.append("When done, provide a summary of changes.\n")
                sb.append("At the end, you MUST specify: **Next Agent:** CEO (for review) or DEBUGGER (if bugs exist)\n")
            }
            AgentRole.DEBUGGER -> {
                sb.append("DEBUGGER ROLE: Use `<get_logs>` to find root causes. Fix bugs.\n")
                sb.append("At the end, you MUST specify: **Next Agent:** CEO\n")
            }
            AgentRole.LOGGER -> {
                sb.append("LOGGER ROLE: Monitor app behavior. Highlight errors.\n")
                sb.append("At the end, you MUST specify: **Next Agent:** DEBUGGER or CEO\n")
            }
        }
        
        sb.append("\nIMPORTANT:\n")
        sb.append("- Respond ONLY with your updated workspace section starting with '${role.header}'.\n")
        sb.append("- PRIORITIZE ACTION OVER TALK.\n")
        
        return sb.toString()
    }

    private fun extractNextAgentFromResponse(content: String): AgentRole? {
        val regex = Regex("\\*\\*Next Agent:\\*\\*\\s*([\\w\\s\\(\\)]+)", RegexOption.IGNORE_CASE)
        val match = regex.find(content)
        val roleStr = match?.groupValues?.get(1)?.trim()?.uppercase() ?: return null
        
        return AgentRole.values().find { 
            it.name == roleStr || 
            it.name.replace("_", " ") == roleStr ||
            it.displayName.uppercase().contains(roleStr) ||
            roleStr.contains(it.name)
        }
    }

    private fun extractSection(markdown: String, header: String): String {
        val headerEscaped = Regex.escape(header)
        val regex = Regex("$headerEscaped\\n(.*?)(?=\\n## |\\z)", RegexOption.DOT_MATCHES_ALL)
        val match = regex.find(markdown)
        return match?.groupValues?.get(1)?.trim() ?: ""
    }
}
