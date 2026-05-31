package com.mockmaster.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mockmaster.app.api.ApiConfig
import com.mockmaster.app.api.ApiException
import com.mockmaster.app.api.MockMasterApi
import com.mockmaster.shared.UpsertFlowReq
import com.mockmaster.shared.UpsertRuleReq
import com.mockmaster.shared.Flow
import com.mockmaster.shared.FolderNode
import com.mockmaster.shared.InterceptedCall
import com.mockmaster.shared.MockRule
import com.mockmaster.shared.ServerInfo
import com.mockmaster.shared.WorkspaceState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch

class AppState(private val scope: CoroutineScope) {
    var workspace by mutableStateOf(WorkspaceState())
        private set
    var serverInfo by mutableStateOf<ServerInfo?>(null)
        private set

    val logs = mutableStateListOf<InterceptedCall>()

    var loading by mutableStateOf(true)
        private set
    var lastError by mutableStateOf<String?>(null)
    var toast by mutableStateOf<String?>(null)

    /** Currently signed-in user. Null means show the username gate. */
    var currentUser by mutableStateOf(ApiConfig.currentUser)
        private set

    var selectedFolderId by mutableStateOf<String?>(null)
    var selectedFlowId by mutableStateOf<String?>(null)

    val activeFlow: Flow?
        get() = workspace.activeFlowId?.let { id -> workspace.flows.firstOrNull { it.id == id } }

    val isFlowActive: Boolean
        get() = workspace.activeFlowId != null

    // ----- dialog state -----
    var createFolderDialogParentId by mutableStateOf<String?>(null)
        private set

    var editingRule by mutableStateOf<Pair<String, MockRule?>?>(null)
        private set

    var deleteFolderConfirmId by mutableStateOf<String?>(null)
        private set

    var deleteRuleConfirm by mutableStateOf<Pair<String, String>?>(null)
        private set

    var renamingFolderId by mutableStateOf<String?>(null)
        private set
    var renamingFolderInitial by mutableStateOf<String?>(null)
        private set

    var sharingFlowId by mutableStateOf<String?>(null)
        private set
    fun openShareFlow(id: String) { sharingFlowId = id }
    fun closeShareFlow() { sharingFlowId = null }

    fun openCreateFolderDialog(parentId: String?) {
        createFolderDialogParentId = parentId ?: "__root__"
    }
    fun closeCreateFolderDialog() { createFolderDialogParentId = null }

    fun openRuleEditor(folderId: String, rule: MockRule?) {
        editingRule = folderId to rule
    }
    fun closeRuleEditor() { editingRule = null }

    fun askDeleteFolder(id: String?) { deleteFolderConfirmId = id }
    fun askDeleteRule(pair: Pair<String, String>?) { deleteRuleConfirm = pair }

    fun openRenameFolder(id: String, initial: String) {
        renamingFolderId = id
        renamingFolderInitial = initial
    }
    fun closeRenameFolder() {
        renamingFolderId = null
        renamingFolderInitial = null
    }

    // ----- session -----
    /**
     * Sign in (create-on-first-use). Idempotent. The username is sanitized
     * server-side; we use the server's canonical form going forward so the
     * X-MockMaster-User header always matches the workspace folder.
     */
    suspend fun signIn(username: String): String? {
        val name = username.trim()
        if (name.isEmpty()) return "Please enter a username"
        return try {
            val resp = MockMasterApi.ensureUser(name)
            ApiConfig.currentUser = resp.username
            currentUser = resp.username
            // Tell the proxy to serve this user's mocks.
            runCatching { MockMasterApi.setActiveUser(resp.username) }
            bootstrap()
            if (resp.created) showToast("Workspace created for '${resp.username}'")
            else showToast("Welcome back, ${resp.username}")
            null
        } catch (e: ApiException) {
            e.userMessage
        } catch (e: Throwable) {
            "Could not reach backend: ${e.message}"
        }
    }

    fun signOut() {
        ApiConfig.currentUser = null
        currentUser = null
        workspace = WorkspaceState()
        logs.clear()
        selectedFolderId = null
        selectedFlowId = null
    }

    suspend fun bootstrap() {
        loading = true
        try {
            workspace = MockMasterApi.fetchWorkspace()
            serverInfo = MockMasterApi.serverInfo()
            logs.clear()
            logs.addAll(MockMasterApi.listLogs())
            if (selectedFolderId == null) {
                selectedFolderId = firstFolderId(workspace.folders)
            }
            lastError = null
        } catch (e: Throwable) {
            lastError = "Could not connect to backend at ${ApiConfig.baseUrl}: ${e.message}"
        } finally {
            loading = false
        }
    }

    suspend fun refreshWorkspace() {
        try {
            workspace = MockMasterApi.fetchWorkspace()
        } catch (e: Throwable) {
            lastError = e.message
        }
    }

    suspend fun refreshServerInfo() {
        try {
            serverInfo = MockMasterApi.serverInfo()
        } catch (_: Throwable) {
        }
    }

    fun showToast(msg: String) {
        toast = msg
        scope.launch {
            kotlinx.coroutines.delay(2500)
            if (toast == msg) toast = null
        }
    }

    fun appendLog(call: InterceptedCall) {
        logs.add(0, call)
        while (logs.size > 500) logs.removeAt(logs.size - 1)
    }

    fun clearLocalLogs() {
        logs.clear()
    }

    /**
     * Helper for dialog-driven mutations. Returns a `Deferred<String?>` that
     * resolves to `null` on success or a user-facing error message on failure.
     * Dialogs await it before deciding whether to close.
     */
    private fun <T> launchOp(block: suspend () -> T): Deferred<String?> {
        val deferred = CompletableDeferred<String?>()
        scope.launch {
            try {
                block()
                refreshWorkspace()
                deferred.complete(null)
            } catch (e: ApiException) {
                deferred.complete(e.userMessage)
            } catch (e: Throwable) {
                deferred.complete(e.message ?: "Something went wrong")
            }
        }
        return deferred
    }

    // --- folder ops (dialog-driven, return errors) ---
    fun createFolder(name: String, parentId: String?): Deferred<String?> = launchOp {
        MockMasterApi.createFolder(name, parentId)
        showToast("Folder created")
    }

    fun renameFolder(id: String, name: String): Deferred<String?> = launchOp {
        MockMasterApi.renameFolder(id, name)
        showToast("Folder renamed")
    }

    fun toggleFolder(id: String, enabled: Boolean) = scope.launch {
        try {
            MockMasterApi.toggleFolder(id, enabled); refreshWorkspace()
        } catch (e: Throwable) { showToast("Toggle failed: ${e.message}") }
    }

    fun deleteFolder(id: String) = scope.launch {
        try {
            MockMasterApi.deleteFolder(id)
            if (selectedFolderId == id) selectedFolderId = null
            refreshWorkspace()
            showToast("Folder deleted")
        } catch (e: Throwable) { showToast("Delete failed: ${e.message}") }
    }

    // --- rule ops ---
    fun upsertRule(folderId: String, req: UpsertRuleReq): Deferred<String?> = launchOp {
        MockMasterApi.upsertRule(folderId, req)
        showToast(if (req.id == null) "Endpoint added" else "Endpoint updated")
    }

    fun deleteRule(folderId: String, ruleId: String) = scope.launch {
        try {
            MockMasterApi.deleteRule(folderId, ruleId)
            refreshWorkspace()
            showToast("Endpoint deleted")
        } catch (e: Throwable) { showToast("Delete failed: ${e.message}") }
    }

    fun toggleRule(folderId: String, ruleId: String, enabled: Boolean) = scope.launch {
        try {
            MockMasterApi.toggleRule(folderId, ruleId, enabled); refreshWorkspace()
        } catch (e: Throwable) { showToast("Toggle failed: ${e.message}") }
    }

    // --- flow ops ---
    fun upsertFlow(req: UpsertFlowReq): Deferred<String?> {
        val deferred = CompletableDeferred<String?>()
        scope.launch {
            try {
                val saved = MockMasterApi.upsertFlow(req)
                refreshWorkspace()
                selectedFlowId = saved.id
                showToast(if (req.id == null) "Flow created" else "Flow updated")
                deferred.complete(null)
            } catch (e: ApiException) {
                deferred.complete(e.userMessage)
            } catch (e: Throwable) {
                deferred.complete(e.message ?: "Save failed")
            }
        }
        return deferred
    }

    fun deleteFlow(id: String) = scope.launch {
        try {
            MockMasterApi.deleteFlow(id)
            if (selectedFlowId == id) selectedFlowId = null
            refreshWorkspace()
            showToast("Flow deleted")
        } catch (e: Throwable) { showToast("Delete failed: ${e.message}") }
    }

    fun shareFlow(id: String, targetUser: String): Deferred<String?> {
        val deferred = CompletableDeferred<String?>()
        scope.launch {
            try {
                val resp = MockMasterApi.shareFlow(id, targetUser)
                showToast(resp.message ?: "Shared with ${resp.targetUser}")
                deferred.complete(null)
            } catch (e: ApiException) {
                deferred.complete(e.userMessage)
            } catch (e: Throwable) {
                deferred.complete(e.message ?: "Share failed")
            }
        }
        return deferred
    }

    fun startFlow(id: String) = scope.launch {
        try {
            MockMasterApi.startFlow(id); refreshWorkspace()
            showToast("Flow started — folder mocks paused")
        } catch (e: Throwable) { showToast("Start failed: ${e.message}") }
    }

    fun stopFlow() = scope.launch {
        try {
            MockMasterApi.stopFlow(); refreshWorkspace()
            showToast("Flow stopped — folder mocks resumed")
        } catch (e: Throwable) { showToast("Stop failed: ${e.message}") }
    }

    // --- proxy ---
    fun connectProxy() = scope.launch {
        try { MockMasterApi.connectProxy(); refreshServerInfo() }
        catch (e: Throwable) { showToast("Connect failed: ${e.message}") }
    }
    fun disconnectProxy() = scope.launch {
        try { MockMasterApi.disconnectProxy(); refreshServerInfo() }
        catch (e: Throwable) { showToast("Disconnect failed: ${e.message}") }
    }

    fun clearLogs() = scope.launch {
        try {
            MockMasterApi.clearLogs(); logs.clear()
        } catch (e: Throwable) { showToast("Clear failed: ${e.message}") }
    }
}

private fun firstFolderId(nodes: List<FolderNode>): String? {
    nodes.firstOrNull()?.let { return it.id }
    return null
}

fun rulesFor(workspace: WorkspaceState, folderId: String): List<MockRule> =
    workspace.rulesMap[folderId].orEmpty()

/** True if this folder is enabled AND every ancestor is enabled. */
fun effectivelyEnabled(folders: List<FolderNode>, id: String): Boolean {
    fun walk(nodes: List<FolderNode>, parentEnabled: Boolean): Boolean? {
        for (n in nodes) {
            val effective = parentEnabled && n.isEnabled
            if (n.id == id) return effective
            walk(n.subFolders, effective)?.let { return it }
        }
        return null
    }
    return walk(folders, true) ?: false
}
