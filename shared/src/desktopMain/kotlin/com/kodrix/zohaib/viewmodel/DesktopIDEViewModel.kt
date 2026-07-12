package com.kodrix.zohaib.viewmodel

import com.kodrix.zohaib.ai.DesktopAIBackendManager
import com.kodrix.zohaib.platform.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.UUID

class DesktopIDEViewModel : BaseIDEViewModel() {

    private val homeDir = System.getProperty("user.home") ?: "/"
    private val projectsDir = File(homeDir, "KodrixProjects").apply { mkdirs() }
    private var currentProjectDir: File? = null

    /** Desktop AI backend — uses Gemini API directly via HTTP (no WebView) */
    val aiBackend = DesktopAIBackendManager()

    init {
        refreshProjects()
        state.nodeVersion.value = detectCommandVersion("node", "--version")
        state.gitVersion.value  = detectCommandVersion("git", "--version")
        restoreGithubAuth()
    }

    private fun detectCommandVersion(cmd: String, vararg args: String): String {
        return try {
            val result = executeCommand(listOf(cmd, *args))
            result.stdout.trim().ifEmpty { result.stderr.trim() }
        } catch (e: Exception) {
            "Not found"
        }
    }

    // === Project Management ===

    override fun createProject(name: String) {
        val dir = File(projectsDir, name)
        dir.mkdirs()
        refreshProjects()
        switchProject(name)
    }

    override fun deleteProject(name: String) {
        File(projectsDir, name).deleteRecursively()
        if (currentProjectDir?.name == name) {
            currentProjectDir = null
            state.activeProject.value = null
        }
        refreshProjects()
    }

    override fun switchProject(name: String) {
        val dir = File(projectsDir, name)
        if (dir.exists() && dir.isDirectory) {
            currentProjectDir = dir
            state.activeProject.value = name
            state.fileContents.value = emptyMap()
            state.openTabs.value = emptyList()
            state.activeTabIndices.value = emptyList()
            state.unsavedChanges.value = emptySet()
            refreshGitState()
            addRecentProject(dir.absolutePath)
        }
    }

    override fun openFolder(path: String) {
        val dir = File(path)
        if (dir.exists() && dir.isDirectory) {
            currentProjectDir = dir
            state.activeProject.value = dir.name
            state.fileContents.value = emptyMap()
            state.openTabs.value = emptyList()
            state.activeTabIndices.value = emptyList()
            state.unsavedChanges.value = emptySet()
            refreshGitState()
            addRecentProject(dir.absolutePath)
        }
    }

    private fun getRecentProjects(): List<String> {
        return try {
            val prefs = java.util.prefs.Preferences.userRoot().node("com/kodrix/zohaib/ide")
            val raw = prefs.get("recent_projects", "")
            if (raw.isEmpty()) emptyList()
            else raw.split("|").filter { it.isNotEmpty() && File(it).exists() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun addRecentProject(path: String) {
        try {
            val current = getRecentProjects().toMutableList()
            current.remove(path)
            current.add(0, path)
            val updated = current.take(15)
            val prefs = java.util.prefs.Preferences.userRoot().node("com/kodrix/zohaib/ide")
            prefs.put("recent_projects", updated.joinToString("|"))
            state.projects.value = updated
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun removeRecentProject(path: String) {
        try {
            val current = getRecentProjects().toMutableList()
            current.remove(path)
            val prefs = java.util.prefs.Preferences.userRoot().node("com/kodrix/zohaib/ide")
            prefs.put("recent_projects", current.joinToString("|"))
            state.projects.value = current
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun refreshProjects() {
        state.projects.value = getRecentProjects()
    }

    // === File Operations ===

    override fun openFile(path: String) {
        val file = File(path)
        if (!file.exists() || !file.isFile) return

        val tabs = state.openTabs.value.toMutableList()
        if (path !in tabs) {
            tabs.add(path)
            state.openTabs.value = tabs
        }

        val idx = tabs.indexOf(path)
        state.activeTabIndices.value = listOf(idx)

        if (path !in state.fileContents.value) {
            try {
                val content = file.readText(Charsets.UTF_8)
                state.fileContents.value = state.fileContents.value + (path to content)
            } catch (e: Exception) {
                state.fileContents.value = state.fileContents.value + (path to "// Error reading file: ${e.message}")
            }
        }
    }

    override fun closeTab(index: Int) {
        val tabs = state.openTabs.value.toMutableList()
        if (index in tabs.indices) {
            val path = tabs.removeAt(index)
            state.openTabs.value = tabs

            val contents = state.fileContents.value.toMutableMap()
            contents.remove(path)
            state.fileContents.value = contents

            val unsaved = state.unsavedChanges.value.toMutableSet()
            unsaved.remove(path)
            state.unsavedChanges.value = unsaved

            val indices = state.activeTabIndices.value.toMutableList()
            indices.remove(index)
            if (indices.isEmpty() && tabs.isNotEmpty()) {
                indices.add((index - 1).coerceAtLeast(0))
            }
            state.activeTabIndices.value = indices
        }
    }

    override fun saveFile(path: String) {
        val content = state.fileContents.value[path] ?: return
        try {
            File(path).writeText(content, Charsets.UTF_8)
            val unsaved = state.unsavedChanges.value.toMutableSet()
            unsaved.remove(path)
            state.unsavedChanges.value = unsaved
            refreshGitState()
        } catch (e: Exception) {
            logError("DesktopVM", "Failed to save $path", e)
        }
    }

    fun updateFileContent(path: String, content: String) {
        state.fileContents.value = state.fileContents.value + (path to content)
        val unsaved = state.unsavedChanges.value.toMutableSet()
        unsaved.add(path)
        state.unsavedChanges.value = unsaved
    }

    fun createFile(name: String) {
        val dir = currentProjectDir ?: return
        File(dir, name).createNewFile()
        refreshGitState()
    }

    fun createDirectory(name: String) {
        val dir = currentProjectDir ?: return
        File(dir, name).mkdirs()
        refreshGitState()
    }

    fun deleteFile(path: String) {
        File(path).deleteRecursively()
        closeTab(state.openTabs.value.indexOf(path))
        refreshGitState()
    }

    fun renameFile(oldPath: String, newName: String) {
        val file = File(oldPath)
        val newFile = File(file.parent, newName)
        file.renameTo(newFile)
        val tabs = state.openTabs.value.toMutableList()
        val idx = tabs.indexOf(oldPath)
        if (idx >= 0) {
            tabs[idx] = newFile.absolutePath
            state.openTabs.value = tabs
            val contents = state.fileContents.value.toMutableMap()
            val content = contents.remove(oldPath)
            if (content != null) {
                state.fileContents.value = contents + (newFile.absolutePath to content)
            }
        }
        refreshGitState()
    }

    fun getDirectoryTree(dir: File): List<FileNode> {
        val result = mutableListOf<FileNode>()
        try {
            val files = dir.listFiles() ?: return emptyList()
            data class FileEntry(val file: File, val isDir: Boolean, val lowerName: String)
            val entries = files.map { FileEntry(it, it.isDirectory, it.name.lowercase()) }
            val sorted = entries.sortedWith(compareBy<FileEntry> { !it.isDir }.thenBy { it.lowerName })
            sorted.take(200).forEach { entry ->
                val file = entry.file
                if (!file.name.startsWith(".") && file.name != "node_modules" && file.name != "__pycache__" && file.name != "build" && file.name != ".gradle") {
                    result.add(FileNode(
                        path = file.absolutePath,
                        name = file.name,
                        isDirectory = entry.isDir,
                        isLoaded = !entry.isDir, // Files are loaded by default; directories are lazy-loaded
                        children = emptyList()
                    ))
                }
            }
        } catch (e: Exception) {
            logError("DesktopVM", "Error building directory tree for ${dir.name}", e)
        }
        return result
    }

    fun updateNodeChildren(nodes: List<FileNode>, targetPath: String, newChildren: List<FileNode>): List<FileNode> {
        return nodes.map { node ->
            if (node.path == targetPath) {
                node.copy(children = newChildren, isLoaded = true)
            } else if (node.isDirectory && targetPath.startsWith(node.path + java.io.File.separator)) {
                node.copy(children = updateNodeChildren(node.children, targetPath, newChildren))
            } else {
                node
            }
        }
    }

    // === Git Operations ===
    
    override fun stageFile(path: String) {
        val dir = currentProjectDir ?: return
        scope.launch {
            state.gitStatusMessage.value = "Staging ${path.substringAfterLast("/")}..."
            val result = executeCommand(listOf("git", "add", path), workingDir = dir.absolutePath)
            withContext(Dispatchers.Main) {
                if (result.exitCode == 0) {
                    state.gitStatusMessage.value = "Staged ${path.substringAfterLast("/")}"
                } else {
                    state.gitStatusMessage.value = "Failed to stage: ${result.stderr.trim()}"
                }
                refreshGitState()
            }
        }
    }

    override fun unstageFile(path: String) {
        val dir = currentProjectDir ?: return
        scope.launch {
            state.gitStatusMessage.value = "Unstaging ${path.substringAfterLast("/")}..."
            val result = executeCommand(listOf("git", "reset", "HEAD", path), workingDir = dir.absolutePath)
            withContext(Dispatchers.Main) {
                if (result.exitCode == 0) {
                    state.gitStatusMessage.value = "Unstaged ${path.substringAfterLast("/")}"
                } else {
                    state.gitStatusMessage.value = "Failed to unstage: ${result.stderr.trim()}"
                }
                refreshGitState()
            }
        }
    }

    override fun discardFileChanges(path: String) {
        val dir = currentProjectDir ?: return
        scope.launch {
            state.gitStatusMessage.value = "Discarding changes in ${path.substringAfterLast("/")}..."
            val result = executeCommand(listOf("git", "checkout", "--", path), workingDir = dir.absolutePath)
            withContext(Dispatchers.Main) {
                if (result.exitCode == 0) {
                    state.gitStatusMessage.value = "Discarded changes in ${path.substringAfterLast("/")}"
                    // Reload file in the editor if open
                    val fullPath = File(dir, path).absolutePath
                    if (state.openTabs.value.contains(fullPath)) {
                        try {
                            val content = File(fullPath).readText(Charsets.UTF_8)
                            val contents = state.fileContents.value.toMutableMap()
                            contents[fullPath] = content
                            state.fileContents.value = contents
                            val unsaved = state.unsavedChanges.value.toMutableSet()
                            unsaved.remove(fullPath)
                            state.unsavedChanges.value = unsaved
                        } catch (e: Exception) {
                            logError("DesktopVM", "Failed to reload file content", e)
                        }
                    }
                } else {
                    state.gitStatusMessage.value = "Failed to discard changes: ${result.stderr.trim()}"
                }
                refreshGitState()
            }
        }
    }

    override fun cloneProject(url: String, name: String) {
        scope.launch {
            state.cloneProgress.value = "Cloning..."
            val result = executeCommand(
                listOf("git", "clone", url, name),
                workingDir = projectsDir.absolutePath
            )
            if (result.exitCode == 0) {
                withContext(Dispatchers.Main) {
                    refreshProjects()
                    switchProject(name)
                }
            }
            state.cloneProgress.value = if (result.exitCode == 0) "Done" else "Error: ${result.stderr}"
        }
    }

    override fun commitChanges(message: String) {
        val dir = currentProjectDir ?: return
        scope.launch {
            state.gitStatusMessage.value = "Committing..."
            val hasStaged = state.gitChanges.value.any { it.isStaged }
            if (!hasStaged) {
                executeCommand(listOf("git", "add", "-A"), workingDir = dir.absolutePath)
            }
            val result = executeCommand(listOf("git", "commit", "-m", message), workingDir = dir.absolutePath)
            withContext(Dispatchers.Main) {
                if (result.exitCode == 0) {
                    state.gitStatusMessage.value = "Commit successful"
                } else {
                    state.gitStatusMessage.value = "Commit failed: ${result.stderr.trim()}"
                }
                refreshGitState()
            }
        }
    }

    override fun pushChanges() {
        val dir = currentProjectDir ?: return
        scope.launch {
            state.gitStatusMessage.value = "Pushing to remote..."
            val result = executeCommand(listOf("git", "push"), workingDir = dir.absolutePath)
            withContext(Dispatchers.Main) {
                if (result.exitCode == 0) {
                    state.gitStatusMessage.value = "Push successful"
                } else {
                    state.gitStatusMessage.value = "Push failed: ${result.stderr.trim()}"
                }
                refreshGitState()
            }
        }
    }

    override fun pullChanges() {
        val dir = currentProjectDir ?: return
        scope.launch {
            state.gitStatusMessage.value = "Pulling from remote..."
            val result = executeCommand(listOf("git", "pull"), workingDir = dir.absolutePath)
            withContext(Dispatchers.Main) {
                if (result.exitCode == 0) {
                    state.gitStatusMessage.value = "Pull successful"
                } else {
                    state.gitStatusMessage.value = "Pull failed: ${result.stderr.trim()}"
                }
                refreshGitState()
            }
        }
    }

    override fun checkoutBranch(branch: String) {
        val dir = currentProjectDir ?: return
        scope.launch {
            state.gitStatusMessage.value = "Checking out $branch..."
            val result = executeCommand(listOf("git", "checkout", branch), workingDir = dir.absolutePath)
            withContext(Dispatchers.Main) {
                if (result.exitCode == 0) {
                    state.gitStatusMessage.value = "Switched to branch '$branch'"
                } else {
                    state.gitStatusMessage.value = "Failed to switch: ${result.stderr.trim()}"
                }
                refreshGitState()
            }
        }
    }

    override fun createBranch(name: String) {
        val dir = currentProjectDir ?: return
        scope.launch {
            state.gitStatusMessage.value = "Creating branch $name..."
            val result = executeCommand(listOf("git", "checkout", "-b", name), workingDir = dir.absolutePath)
            withContext(Dispatchers.Main) {
                if (result.exitCode == 0) {
                    state.gitStatusMessage.value = "Created and switched to branch '$name'"
                } else {
                    state.gitStatusMessage.value = "Failed to create branch: ${result.stderr.trim()}"
                }
                refreshGitState()
            }
        }
    }

    private fun refreshGitState() {
        val dir = currentProjectDir ?: return
        if (!File(dir, ".git").exists()) {
            state.gitCurrentBranch.value = ""
            state.gitBranches.value = emptyList()
            state.gitChanges.value = emptyList()
            state.gitStatusMessage.value = ""
            return
        }

        scope.launch {
            // Current branch
            val branchResult = executeCommand(listOf("git", "rev-parse", "--abbrev-ref", "HEAD"), workingDir = dir.absolutePath)
            val branch = branchResult.stdout.trim()

            // All branches
            val branchesResult = executeCommand(listOf("git", "branch", "--format=%(refname:short)"), workingDir = dir.absolutePath)
            val branches = branchesResult.stdout.lines().filter { it.isNotEmpty() }

            // Changes
            val statusResult = executeCommand(listOf("git", "status", "--porcelain"), workingDir = dir.absolutePath)
            val changes = statusResult.stdout.lines().filter { it.isNotEmpty() }.map { line ->
                val status = line.substring(0, 2).trim()
                val path = line.substring(3).trim()
                val displayStatus = when {
                    status.contains("M") -> "M"
                    status.contains("A") -> "A"
                    status.contains("D") -> "D"
                    status.contains("?") -> "?"
                    else -> status.firstOrNull()?.toString() ?: ""
                }
                val firstChar = line.getOrNull(0) ?: ' '
                val isStaged = firstChar != ' ' && firstChar != '?'
                GitChange(path, displayStatus, isStaged)
            }

            // Recent commits
            val logResult = executeCommand(
                listOf("git", "log", "--oneline", "-20", "--format=%H|%s|%an|%ai"),
                workingDir = dir.absolutePath
            )
            val commits = logResult.stdout.lines().filter { it.isNotEmpty() }.map { line ->
                val parts = line.split("|", limit = 4)
                GitCommit(
                    hash = parts.getOrElse(0) { "" }.take(8),
                    message = parts.getOrElse(1) { "" },
                    author = parts.getOrElse(2) { "" },
                    date = parts.getOrElse(3) { "" }
                )
            }

            withContext(Dispatchers.Main) {
                state.gitCurrentBranch.value = branch
                state.gitBranches.value = branches
                state.gitChanges.value = changes
                state.gitCommits.value = commits
            }
        }
    }
    // === GitHub OAuth ===

    private val githubClientId     = "Ov23liGDwcWLayi70rk2"
    private val githubClientSecret = "961d371f7bd737f4d3de71f13f6b9dfebfed118c"
    private var oauthStateToken: String? = null

    override fun loginGithub() {
        oauthStateToken = UUID.randomUUID().toString()
        val url = "https://github.com/login/oauth/authorize" +
                "?client_id=$githubClientId" +
                "&scope=repo,user" +
                "&redirect_uri=kodrix://github-auth" +
                "&state=$oauthStateToken"

        // Open the system browser
        try {
            val os = System.getProperty("os.name").lowercase()
            val cmd = when {
                os.contains("win")   -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
                os.contains("mac")   -> listOf("open", url)
                else                 -> listOf("xdg-open", url)
            }
            ProcessBuilder(cmd).start()
        } catch (e: Exception) {
            state.gitStatusMessage.value = "Could not open browser: ${e.message}"
            return
        }

        state.gitStatusMessage.value = "Waiting for GitHub login..."
    }

    fun handleOAuthCallback(url: String) {
        scope.launch(Dispatchers.IO) {
            try {
                // Parse: kodrix://github-auth?code=XXX&state=YYY
                val query = url.substringAfter("?").substringBefore(" ")
                val params = query.split("&").associate {
                    val (k, v) = it.split("=", limit = 2).let { p -> p[0] to (p.getOrElse(1) { "" }) }
                    k to v
                }

                val code  = params["code"]  ?: run { withContext(Dispatchers.Main) { state.gitStatusMessage.value = "GitHub login cancelled." }; return@launch }
                val state = params["state"] ?: ""

                if (state != oauthStateToken) {
                    withContext(Dispatchers.Main) { this@DesktopIDEViewModel.state.gitStatusMessage.value = "OAuth state mismatch — possible CSRF." }
                    return@launch
                }
                oauthStateToken = null

                // Exchange code → access token
                val tokenUrl  = java.net.URL("https://github.com/login/oauth/access_token")
                val tokenConn = tokenUrl.openConnection() as java.net.HttpURLConnection
                tokenConn.requestMethod = "POST"
                tokenConn.setRequestProperty("Accept", "application/json")
                tokenConn.doOutput = true
                tokenConn.outputStream.write(
                    "client_id=$githubClientId&client_secret=$githubClientSecret&code=$code".toByteArray()
                )
                val tokenResp = tokenConn.inputStream.bufferedReader().readText()
                val token     = jsonStr(tokenResp, "access_token")

                if (token.isEmpty()) {
                    val err = jsonStr(tokenResp, "error_description").ifEmpty { "Unknown error" }
                    withContext(Dispatchers.Main) { this@DesktopIDEViewModel.state.gitStatusMessage.value = "GitHub login failed: $err" }
                    return@launch
                }

                // Fetch user info
                val userUrl  = java.net.URL("https://api.github.com/user")
                val userConn = userUrl.openConnection() as java.net.HttpURLConnection
                userConn.setRequestProperty("Authorization", "token $token")
                userConn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                val userResp = userConn.inputStream.bufferedReader().readText()
                val login    = jsonStr(userResp, "login")

                withContext(Dispatchers.Main) {
                    saveGithubAuth(login, token)
                    this@DesktopIDEViewModel.state.gitStatusMessage.value = "Logged in as $login"
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    this@DesktopIDEViewModel.state.gitStatusMessage.value = "GitHub login error: ${e.message}"
                }
            }
        }
    }

    override fun logoutGithub() {
        state.githubUser.value  = null
        state.githubToken.value = null
        state.gitStatusMessage.value = "Logged out of GitHub"
        // Persist via Java prefs so login survives restarts
        val prefs = java.util.prefs.Preferences.userRoot().node("com/kodrix/zohaib/ide")
        prefs.remove("github_user")
        prefs.remove("github_token")
    }

    override fun saveGithubAuth(user: String, token: String) {
        state.githubUser.value  = user
        state.githubToken.value = token
        val prefs = java.util.prefs.Preferences.userRoot().node("com/kodrix/zohaib/ide")
        prefs.put("github_user", user)
        prefs.put("github_token", token)
    }

    /** Restore persisted login on startup (called from init). */
    private fun restoreGithubAuth() {
        val prefs = java.util.prefs.Preferences.userRoot().node("com/kodrix/zohaib/ide")
        val user  = prefs.get("github_user", null)
        val token = prefs.get("github_token", null)
        if (user != null && token != null) {
            state.githubUser.value  = user
            state.githubToken.value = token
        }
    }

    // === NPM ===


    override fun searchNpmPackages(query: String) {
        if (query.isBlank()) {
            state.npmSearchResults.value = emptyList()
            return
        }
        state.isNpmSearching.value = true
        scope.launch {
            val result = executeCommand(listOf("npm", "search", query, "--json"))
            try {
                val json = result.stdout
                val names = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").findAll(json).map { it.groupValues[1] }.take(20).toList()
                val descs = Regex("\"description\"\\s*:\\s*\"([^\"]+)\"").findAll(json).map { it.groupValues[1] }.take(20).toList()
                val pkgs = names.mapIndexed { i, name ->
                    NpmPackage(name, "", descs.getOrElse(i) { "" }, "", "")
                }
                withContext(Dispatchers.Main) {
                    state.npmSearchResults.value = pkgs
                    state.isNpmSearching.value = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { state.isNpmSearching.value = false }
            }
        }
    }

    override fun installNpmPackage(name: String) {
        val dir = currentProjectDir ?: return
        scope.launch {
            executeCommand(listOf("npm", "install", name), workingDir = dir.absolutePath)
        }
    }

    fun runNpmInstall() {
        val dir = currentProjectDir ?: return
        scope.launch {
            executeCommand(listOf("npm", "install"), workingDir = dir.absolutePath)
        }
    }

    fun runNpmCommand(command: String) {
        val dir = currentProjectDir ?: return
        scope.launch {
            executeCommand(listOf("npm", *command.split(" ").toTypedArray()), workingDir = dir.absolutePath)
        }
    }

    // === Terminal ===

    fun getWorkingDirectory(): String {
        return currentProjectDir?.absolutePath ?: homeDir
    }

    // === AI Chat ===

    override fun sendAiMessage(message: String) {
        val currentMessages = state.aiChatMessages.value.toMutableList()
        currentMessages.add(ChatMessage("user", message))
        state.aiChatMessages.value = currentMessages
        state.isAiThinking.value = true

        scope.launch {
            // Use the real Gemini API backend
            val response = aiBackend.ask(message)
            val updatedMessages = state.aiChatMessages.value.toMutableList()
            updatedMessages.add(ChatMessage("assistant", response))
            withContext(Dispatchers.Main) {
                state.aiChatMessages.value = updatedMessages
                state.isAiThinking.value = false
            }
        }
    }

    override fun clearAiChat() {
        state.aiChatMessages.value = emptyList()
    }

    override fun createNewChatSession() {
        val session = ChatSession(id = UUID.randomUUID().toString(), title = "New Chat")
        state.chatSessions.value = state.chatSessions.value + session
        state.activeSessionId.value = session.id
    }

    override fun deleteChatSession(id: String) {
        state.chatSessions.value = state.chatSessions.value.filter { it.id != id }
        if (state.activeSessionId.value == id) {
            state.activeSessionId.value = state.chatSessions.value.lastOrNull()?.id
        }
    }

    override fun switchChatSession(id: String) {
        state.activeSessionId.value = id
    }

    fun shutdown() {
        scope.cancel()
    }

    /** Extracts a string value from a flat JSON payload without needing org.json. */
    private fun jsonStr(json: String, key: String): String =
        Regex(""""${Regex.escape(key)}"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.getOrElse(1) { "" } ?: ""
}

data class FileNode(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val isLoaded: Boolean = false,
    val children: List<FileNode> = emptyList()
)
