package com.kodrix.zohaib.viewmodel

import com.kodrix.zohaib.platform.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

open class BaseIDEViewModel {
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val state = IDEState()

    open fun createProject(name: String) {}
    open fun deleteProject(name: String) {}
    open fun switchProject(name: String) {}
    open fun openFolder(path: String) {}
    open fun removeRecentProject(path: String) {}
    open fun openFile(path: String) {}
    open fun closeTab(index: Int) {}
    open fun saveFile(path: String) {}
    open fun sendAiMessage(message: String) {}
    open fun clearAiChat() {}
    open fun createNewChatSession() {}
    open fun deleteChatSession(id: String) {}
    open fun switchChatSession(id: String) {}
    open fun cloneProject(url: String, name: String) {}
    open fun commitChanges(message: String) {}
    open fun pushChanges() {}
    open fun pullChanges() {}
    open fun checkoutBranch(branch: String) {}
    open fun createBranch(name: String) {}
    open fun stageFile(path: String) {}
    open fun unstageFile(path: String) {}
    open fun discardFileChanges(path: String) {}
    open fun loginGithub() {}
    open fun logoutGithub() {}
    open fun saveGithubAuth(user: String, token: String) {}
    open fun searchNpmPackages(query: String) {}
    open fun installNpmPackage(name: String) {}
    open fun searchExtensions(query: String) {}
    open fun dismissUpdate() {}
    open fun applyBinaryUpdate(tool: String) {}
    open fun checkBinaryUpdates() {}
    open fun exportProjectZip(name: String) {}
    open fun importProjectZip() {}
    open fun installGithubExtension(url: String) {}
    open fun closePreview() {}
}
