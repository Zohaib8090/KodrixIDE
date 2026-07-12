package com.kodrix.zohaib.viewmodel

data class IDEProblem(
    val filePath: String,
    val line: Int,
    val column: Int,
    val message: String,
    val severity: String
)

data class GitCommit(
    val hash: String,
    val message: String,
    val author: String,
    val date: String
)

data class GitChange(
    val path: String,
    val status: String,
    val isStaged: Boolean
)

data class NpmPackage(
    val name: String,
    val version: String,
    val description: String,
    val author: String,
    val date: String
)

data class ChatMessage(val role: String, val content: String, val timestamp: Long = 0L)

data class ChatSession(
    val id: String = "",
    val title: String = "New Chat",
    val messages: List<ChatMessage> = emptyList(),
    val timestamp: Long = 0L
)

enum class SidebarMode {
    PROJECTS, EXPLORER, GIT, SEARCH, EXTENSIONS, MARKETPLACE, AI, DEBUG, BROWSER, SETTINGS, TERMINAL
}
