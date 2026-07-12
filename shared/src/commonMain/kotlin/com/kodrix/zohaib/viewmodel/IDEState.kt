package com.kodrix.zohaib.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class IDEState {
    val nodeVersion = MutableStateFlow("N/A")
    val gitVersion = MutableStateFlow("N/A")
    val isReady = MutableStateFlow(true)
    val isCtrlActive = MutableStateFlow(false)
    val activeInstanceIndex = MutableStateFlow(0)
    val installingIds = MutableStateFlow<Set<String>>(emptySet())
    val installingProgress = MutableStateFlow<Map<String, Float>>(emptyMap())

    val aiChatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val isAiThinking = MutableStateFlow(false)
    val chatSessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val activeSessionId = MutableStateFlow<String?>(null)
    val showThirdPartyDialog = MutableStateFlow(false)

    val isPanelVisible = MutableStateFlow(true)
    val panelHeight = MutableStateFlow(250f)
    val uiScale = MutableStateFlow(1.0f)
    val fontSize = MutableStateFlow(14)
    val terminalFontSize = MutableStateFlow(12)
    val editorFontSize = MutableStateFlow(14)
    val showLineNumbers = MutableStateFlow(true)
    val safeMode = MutableStateFlow(false)

    val sidebarMode = MutableStateFlow(SidebarMode.EXPLORER)
    val sidebarOpen = MutableStateFlow(true)

    val activeProject = MutableStateFlow<String?>(null)
    val projects = MutableStateFlow<List<String>>(emptyList())
    val openTabs = MutableStateFlow<List<String>>(emptyList())
    val activeTabIndices = MutableStateFlow<List<Int>>(emptyList())
    val fileContents = MutableStateFlow<Map<String, String>>(emptyMap())
    val unsavedChanges = MutableStateFlow<Set<String>>(emptySet())

    val isUpdatingBinaries = MutableStateFlow(false)
    val binaryUpdateStatus = MutableStateFlow<Map<String, String>>(emptyMap())
    val binaryUpdateProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val availableUpdate = MutableStateFlow<String?>(null)

    val githubUser = MutableStateFlow<String?>(null)
    val githubToken = MutableStateFlow<String?>(null)

    val gitBranches = MutableStateFlow<List<String>>(emptyList())
    val gitCurrentBranch = MutableStateFlow("main")
    val gitChanges = MutableStateFlow<List<GitChange>>(emptyList())
    val gitCommits = MutableStateFlow<List<GitCommit>>(emptyList())
    val cloneProgress = MutableStateFlow("")
    val gitStatusMessage = MutableStateFlow("")

    val npmSearchResults = MutableStateFlow<List<NpmPackage>>(emptyList())
    val isNpmSearching = MutableStateFlow(false)

    val availableExtensions = MutableStateFlow<List<Any>>(emptyList())
    val isSearchingExtensions = MutableStateFlow(false)
    val isScanningMarketplace = MutableStateFlow(false)
    val isExtensionEnabled = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val activeExtensionDetail = MutableStateFlow<Any?>(null)
    val activeGithubExtensionDetail = MutableStateFlow<Any?>(null)

    val logcatOutput = MutableStateFlow<List<String>>(emptyList())
    val logcatFilter = MutableStateFlow("")
    val isLogsPaused = MutableStateFlow(false)
    val activeTunnels = MutableStateFlow<List<Any>>(emptyList())
    val acceptThirdPartyServices = MutableStateFlow(false)
    val binaryUpdateProgressInfo = MutableStateFlow<Map<String, String>>(emptyMap())

    fun setSidebarMode(mode: SidebarMode) { sidebarMode.value = mode }
    fun toggleSidebar() { sidebarOpen.value = !sidebarOpen.value }
}
