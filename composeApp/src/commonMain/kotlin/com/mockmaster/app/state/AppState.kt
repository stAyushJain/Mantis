package com.mockmaster.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mockmaster.app.api.ApiConfig
import com.mockmaster.app.api.ApiException
import com.mockmaster.app.api.MockMasterApi
import com.mockmaster.shared.UpsertFlowReq
import com.mockmaster.shared.UpsertFlowStepReq
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
import kotlinx.serialization.json.Json

/**
 * Pretty-printed JSON for the import/export feature. Indented so users can
 * eyeball the file in a text editor; `ignoreUnknownKeys` means files written
 * by a future version of Mantis with extra fields still parse cleanly.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private val EXPORT_JSON: Json = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    encodeDefaults = true
    ignoreUnknownKeys = true
}

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
    /**
     * Persist a flow. `toastOnSuccess` lets quiet callers (inline reorder,
     * single-step delete) skip the "Flow updated" toast that would otherwise
     * fire on every arrow-button click. Errors always surface via the
     * returned Deferred so callers can decide how to display them.
     */
    fun upsertFlow(req: UpsertFlowReq, toastOnSuccess: Boolean = true): Deferred<String?> {
        val deferred = CompletableDeferred<String?>()
        scope.launch {
            try {
                val saved = MockMasterApi.upsertFlow(req)
                refreshWorkspace()
                selectedFlowId = saved.id
                if (toastOnSuccess) {
                    showToast(if (req.id == null) "Flow created" else "Flow updated")
                }
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

    /**
     * Duplicate an entire flow. Server picks a fresh id; we pick a fresh
     * non-conflicting name client-side (the BE would also reject duplicate
     * names with a 409, but we'd rather not roundtrip just to be told that).
     */
    fun duplicateFlow(id: String): Deferred<String?> {
        val src = workspace.flows.firstOrNull { it.id == id }
            ?: return CompletableDeferred("Flow not found")
        val existingNames = workspace.flows.map { it.name }.toSet()
        var candidate = "${src.name} (copy)"
        var n = 2
        while (candidate in existingNames) {
            candidate = "${src.name} (copy $n)"
            n++
            if (n > 999) break
        }
        val steps = src.steps.map {
            UpsertFlowStepReq(
                id = null, // force fresh ids
                path = it.path,
                method = it.method,
                statusCode = it.statusCode,
                body = it.body,
            )
        }
        val result = upsertFlow(
            UpsertFlowReq(
                id = null,
                name = candidate,
                description = src.description,
                steps = steps,
            ),
            toastOnSuccess = false,
        )
        scope.launch { if (result.await() == null) showToast("Flow duplicated as \"$candidate\"") }
        return result
    }

    /**
     * Serialise a flow to a portable JSON string. We intentionally export
     * UpsertFlowReq (no server-assigned ids, no consumption state) so
     * round-tripping through file → import produces a clean new flow.
     */
    fun exportFlowJson(id: String): String? {
        val flow = workspace.flows.firstOrNull { it.id == id } ?: return null
        val payload = UpsertFlowReq(
            id = null,
            name = flow.name,
            description = flow.description,
            steps = flow.steps.map {
                UpsertFlowStepReq(
                    id = null,
                    path = it.path,
                    method = it.method,
                    statusCode = it.statusCode,
                    body = it.body,
                )
            },
        )
        return EXPORT_JSON.encodeToString(UpsertFlowReq.serializer(), payload)
    }

    /**
     * Import a flow from a JSON blob (produced by exportFlowJson or hand
     * written). Renames on name collision (same logic as duplicate). Returns
     * an error message on parse/validation failure.
     */
    fun importFlowJson(json: String): Deferred<String?> {
        val parsed = runCatching {
            EXPORT_JSON.decodeFromString(UpsertFlowReq.serializer(), json.trim())
        }.getOrElse {
            return CompletableDeferred("Could not parse JSON: ${it.message ?: "invalid format"}")
        }
        if (parsed.name.isBlank()) {
            return CompletableDeferred("Flow JSON must include a non-empty \"name\".")
        }
        val existingNames = workspace.flows.map { it.name }.toSet()
        var candidate = parsed.name
        if (candidate in existingNames) {
            candidate = "${parsed.name} (imported)"
            var n = 2
            while (candidate in existingNames) {
                candidate = "${parsed.name} (imported $n)"; n++
                if (n > 999) break
            }
        }
        // Strip any ids and reset to a fresh flow.
        val req = parsed.copy(
            id = null,
            name = candidate,
            steps = parsed.steps.map { it.copy(id = null) },
        )
        val result = upsertFlow(req, toastOnSuccess = false)
        scope.launch { if (result.await() == null) showToast("Imported \"$candidate\"") }
        return result
    }

    /**
     * Convert a Flow's steps into the request shape expected by upsertFlow,
     * preserving server-assigned ids so the backend updates existing rows in
     * place instead of recreating them (which would lose consumption state).
     */
    private fun Flow.toUpsertSteps(): MutableList<UpsertFlowStepReq> =
        steps.mapTo(mutableListOf()) {
            UpsertFlowStepReq(
                id = it.id,
                path = it.path,
                method = it.method,
                statusCode = it.statusCode,
                body = it.body,
            )
        }

    private fun Flow.toUpsertReq(steps: List<UpsertFlowStepReq>): UpsertFlowReq =
        UpsertFlowReq(id = id, name = name, description = description, steps = steps)

    /** Append a single new step to the end of a flow. */
    fun addFlowStep(flowId: String, step: UpsertFlowStepReq): Deferred<String?> {
        val flow = workspace.flows.firstOrNull { it.id == flowId }
            ?: return CompletableDeferred("Flow not found")
        val steps = flow.toUpsertSteps().also { it.add(step.copy(id = null)) }
        val result = upsertFlow(flow.toUpsertReq(steps), toastOnSuccess = false)
        scope.launch { if (result.await() == null) showToast("Endpoint added") }
        return result
    }

    /**
     * Insert a step at a specific index (clamped to [0..size]). Used both by
     * "duplicate step" (inserts right after the source) and by "add from
     * intercepted log" so the new entry appears next to its source row.
     */
    fun addFlowStepAt(flowId: String, step: UpsertFlowStepReq, index: Int): Deferred<String?> {
        val flow = workspace.flows.firstOrNull { it.id == flowId }
            ?: return CompletableDeferred("Flow not found")
        val steps = flow.toUpsertSteps()
        val target = index.coerceIn(0, steps.size)
        steps.add(target, step.copy(id = null))
        val result = upsertFlow(flow.toUpsertReq(steps), toastOnSuccess = false)
        scope.launch { if (result.await() == null) showToast("Endpoint added") }
        return result
    }

    /**
     * Duplicate a single step in place. The copy is inserted right after
     * the original so the user can quickly produce response variants
     * (e.g. duplicate a 200 then tweak the duplicate to 500 for a retry
     * scenario).
     */
    fun duplicateFlowStep(flowId: String, stepId: String): Deferred<String?> {
        val flow = workspace.flows.firstOrNull { it.id == flowId }
            ?: return CompletableDeferred("Flow not found")
        val src = flow.steps.firstOrNull { it.id == stepId }
            ?: return CompletableDeferred("Step not found")
        val idx = flow.steps.indexOfFirst { it.id == stepId }
        return addFlowStepAt(
            flowId,
            UpsertFlowStepReq(
                id = null,
                path = src.path,
                method = src.method,
                statusCode = src.statusCode,
                body = src.body,
            ),
            idx + 1,
        )
    }

    /** Remove one step by id. */
    fun removeFlowStep(flowId: String, stepId: String): Deferred<String?> {
        val flow = workspace.flows.firstOrNull { it.id == flowId }
            ?: return CompletableDeferred("Flow not found")
        val steps = flow.toUpsertSteps().also { list -> list.removeAll { it.id == stepId } }
        val result = upsertFlow(flow.toUpsertReq(steps), toastOnSuccess = false)
        scope.launch { if (result.await() == null) showToast("Endpoint removed") }
        return result
    }

    /**
     * Move a step to a new index. Negative values clamp to 0; values >= size
     * clamp to the last position. No-ops (and no network call) if the index
     * doesn't change, so we don't spam the backend on clicks at the list
     * boundaries. Silent on success — reorders are too frequent for toasts.
     */
    fun moveFlowStep(flowId: String, stepId: String, newIndex: Int): Deferred<String?> {
        val flow = workspace.flows.firstOrNull { it.id == flowId }
            ?: return CompletableDeferred("Flow not found")
        val steps = flow.toUpsertSteps()
        val from = steps.indexOfFirst { it.id == stepId }
        if (from < 0) return CompletableDeferred("Step not found")
        val target = newIndex.coerceIn(0, steps.size - 1)
        if (target == from) return CompletableDeferred(null)
        val item = steps.removeAt(from)
        steps.add(target, item)
        return upsertFlow(flow.toUpsertReq(steps), toastOnSuccess = false)
    }

    /** Update a single step in place (path/method/status/body). */
    fun updateFlowStep(flowId: String, step: UpsertFlowStepReq): Deferred<String?> {
        val flow = workspace.flows.firstOrNull { it.id == flowId }
            ?: return CompletableDeferred("Flow not found")
        val stepId = step.id ?: return CompletableDeferred("Step id missing")
        val steps = flow.toUpsertSteps()
        val idx = steps.indexOfFirst { it.id == stepId }
        if (idx < 0) return CompletableDeferred("Step not found")
        steps[idx] = step
        val result = upsertFlow(flow.toUpsertReq(steps), toastOnSuccess = false)
        scope.launch { if (result.await() == null) showToast("Endpoint updated") }
        return result
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

    // ----- "Add from logs" promotion helpers -----

    /**
     * Build an UpsertRuleReq seeded from an intercepted call. For passthrough
     * calls (no recorded response body) we default to "200 OK" with an empty
     * JSON object so the user gets a sane starting point; for already-mocked
     * calls we reuse the original status/body. The intent is "I want a mock
     * for this URL — give me the editor pre-filled."
     */
    fun seedRuleFromLog(call: InterceptedCall): UpsertRuleReq = UpsertRuleReq(
        id = null,
        path = call.path,
        method = call.method.uppercase(),
        statusCode = if (call.matched && call.statusCode in 100..599) call.statusCode else 200,
        body = call.responseBody.ifBlank { "{}" },
        isEnabled = true,
    )

    /** Same but for flow steps. */
    fun seedFlowStepFromLog(call: InterceptedCall): UpsertFlowStepReq = UpsertFlowStepReq(
        id = null,
        path = call.path,
        method = call.method.uppercase(),
        statusCode = if (call.matched && call.statusCode in 100..599) call.statusCode else 200,
        body = call.responseBody.ifBlank { "{}" },
    )
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
