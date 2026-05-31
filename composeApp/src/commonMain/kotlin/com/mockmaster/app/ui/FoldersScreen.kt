package com.mockmaster.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mockmaster.app.MockColors
import com.mockmaster.shared.UpsertRuleReq
import com.mockmaster.app.state.AppState
import com.mockmaster.app.state.effectivelyEnabled
import com.mockmaster.app.state.rulesFor
import com.mockmaster.shared.FolderNode
import com.mockmaster.shared.MockRule
import kotlinx.coroutines.launch

@Composable
fun FoldersScreen(state: AppState) {
    val grayedOut = state.isFlowActive
    Row(Modifier.fillMaxSize()) {
        // Tree pane
        Column(
            modifier = Modifier
                .width(320.dp)
                .fillMaxHeight()
                .border(1.dp, MockColors.border, RoundedCornerShape(8.dp))
                .background(MockColors.surface, RoundedCornerShape(8.dp))
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Folders", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = { state.openCreateFolderDialog(parentId = null) },
                    enabled = !grayedOut,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "New root folder")
                }
            }
            if (grayedOut) {
                Text(
                    "Folders are paused while a flow is running. End the flow to edit them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MockColors.warning,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Column(Modifier.verticalScroll(rememberScrollState())) {
                state.workspace.folders.forEach { folder ->
                    FolderTreeNode(
                        node = folder,
                        depth = 0,
                        state = state,
                        ancestorEnabled = true,
                        grayedOut = grayedOut,
                    )
                }
                if (state.workspace.folders.isEmpty()) {
                    Text(
                        "No folders yet. Create one to start adding mocks.",
                        color = MockColors.textSecondary,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        // Endpoint pane
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val selected = state.selectedFolderId
            if (selected == null) {
                Card(Modifier.fillMaxSize()) {
                    Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize()) {
                        Text("Select a folder to view its endpoints", color = MockColors.textSecondary)
                    }
                }
            } else {
                EndpointsPane(state, selected, grayedOut)
            }
        }
    }

    // dialogs
    state.createFolderDialogParentId?.let { parentId ->
        CreateFolderDialog(
            parentId = parentId,
            state = state,
            onDismiss = { state.closeCreateFolderDialog() },
        )
    }
    state.editingRule?.let { (folderId, rule) ->
        RuleEditorDialog(
            folderId = folderId,
            rule = rule,
            state = state,
            onDismiss = { state.closeRuleEditor() },
        )
    }
    state.deleteFolderConfirmId?.let { id ->
        ConfirmDialog(
            title = "Delete folder?",
            message = "All endpoints inside it (and any sub-folders) will be removed. This cannot be undone.",
            onDismiss = { state.askDeleteFolder(null) },
            onConfirm = {
                state.deleteFolder(id); state.askDeleteFolder(null)
            },
        )
    }
    state.deleteRuleConfirm?.let { (folderId, ruleId) ->
        ConfirmDialog(
            title = "Delete endpoint?",
            message = "This mock will stop being served immediately.",
            onDismiss = { state.askDeleteRule(null) },
            onConfirm = {
                state.deleteRule(folderId, ruleId); state.askDeleteRule(null)
            },
        )
    }
    state.renamingFolderId?.let { id ->
        RenameFolderDialog(
            initial = state.renamingFolderInitial.orEmpty(),
            state = state,
            folderId = id,
            onDismiss = { state.closeRenameFolder() },
        )
    }
}

@Composable
private fun FolderTreeNode(
    node: FolderNode,
    depth: Int,
    state: AppState,
    ancestorEnabled: Boolean,
    grayedOut: Boolean,
) {
    var expanded by remember(node.id) { mutableStateOf(true) }
    val effective = ancestorEnabled && node.isEnabled
    val selected = state.selectedFolderId == node.id
    val rules = rulesFor(state.workspace, node.id)

    val nameAlpha = if (effective && !grayedOut) 1f else 0.5f

    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = (depth * 14).dp)
                .background(
                    if (selected) MockColors.accent.copy(alpha = 0.08f) else Color.Transparent,
                    RoundedCornerShape(6.dp),
                )
                .clickable { state.selectedFolderId = node.id }
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(24.dp)) {
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
            Icon(
                if (effective) Icons.Filled.Folder else Icons.Filled.FolderOff,
                contentDescription = null,
                tint = if (effective) MockColors.accent else MockColors.textSecondary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                node.name,
                color = MockColors.textPrimary.copy(alpha = nameAlpha),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${rules.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MockColors.textSecondary,
                modifier = Modifier.padding(end = 6.dp),
            )
            Switch(
                checked = node.isEnabled,
                onCheckedChange = { state.toggleFolder(node.id, it) },
                enabled = !grayedOut,
            )
        }

        if (selected) {
            Row(
                modifier = Modifier
                    .padding(start = (depth * 14 + 30).dp)
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FolderActionChip("New subfolder", Icons.Filled.Add, enabled = !grayedOut) {
                    state.openCreateFolderDialog(node.id)
                }
                FolderActionChip("Rename", Icons.Filled.Edit, enabled = !grayedOut) {
                    state.openRenameFolder(node.id, node.name)
                }
                FolderActionChip("Delete", Icons.Filled.Delete, enabled = !grayedOut) {
                    state.askDeleteFolder(node.id)
                }
            }
        }

        if (expanded) {
            node.subFolders.forEach { child ->
                FolderTreeNode(child, depth + 1, state, effective, grayedOut)
            }
        }
    }
}

@Composable
private fun FolderActionChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        modifier = Modifier.height(28.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun EndpointsPane(state: AppState, folderId: String, grayedOut: Boolean) {
    val folderName = findFolderName(state.workspace.folders, folderId) ?: "—"
    val effective = effectivelyEnabled(state.workspace.folders, folderId)
    val rules = rulesFor(state.workspace, folderId)

    Card(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(folderName, style = MaterialTheme.typography.titleLarge)
                    Text(
                        when {
                            grayedOut -> "Folder is paused while a flow runs"
                            !effective -> "Folder (or an ancestor) is disabled — endpoints below are inactive"
                            else -> "${rules.count { it.isEnabled }} of ${rules.size} endpoints active"
                        },
                        color = MockColors.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(
                    onClick = { state.openRuleEditor(folderId, null) },
                    enabled = !grayedOut,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add endpoint")
                }
            }
            Spacer(Modifier.height(12.dp))
            if (rules.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MockColors.border, RoundedCornerShape(8.dp))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No endpoints yet. Click \"Add endpoint\" to create your first mock.",
                        color = MockColors.textSecondary,
                    )
                }
            } else {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rules.sortedByDescending { it.createdAt }.forEach { rule ->
                        RuleRow(rule = rule, folderId = folderId, state = state, grayedOut = grayedOut, parentEnabled = effective)
                    }
                }
            }
        }
    }
}

@Composable
private fun RuleRow(
    rule: MockRule,
    folderId: String,
    state: AppState,
    grayedOut: Boolean,
    parentEnabled: Boolean,
) {
    val active = rule.isEnabled && parentEnabled && !grayedOut
    val alpha = if (active) 1f else 0.55f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MockColors.border, RoundedCornerShape(8.dp))
            .background(MockColors.surfaceMuted2, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MethodPill(rule.method)
                Spacer(Modifier.width(8.dp))
                StatusPill(rule.statusCode)
                Spacer(Modifier.width(8.dp))
                Text(
                    rule.path,
                    fontFamily = FontFamily.Monospace,
                    color = MockColors.textPrimary.copy(alpha = alpha),
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = rule.isEnabled,
                    onCheckedChange = { state.toggleRule(folderId, rule.id, it) },
                    enabled = !grayedOut,
                )
                IconButton(
                    onClick = { state.openRuleEditor(folderId, rule) },
                    enabled = !grayedOut,
                ) { Icon(Icons.Filled.Edit, contentDescription = "Edit") }
                IconButton(
                    onClick = { state.askDeleteRule(folderId to rule.id) },
                    enabled = !grayedOut,
                ) { Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MockColors.danger) }
            }
            Spacer(Modifier.height(6.dp))
            val preview = rule.body.replace("\n", " ").take(160)
            Text(
                if (preview.length < rule.body.length) "$preview…" else preview,
                style = MaterialTheme.typography.bodySmall,
                color = MockColors.textSecondary,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private fun findFolderName(folders: List<FolderNode>, id: String): String? {
    for (f in folders) {
        if (f.id == id) return f.name
        findFolderName(f.subFolders, id)?.let { return it }
    }
    return null
}

// ----------- DIALOGS ------------

@Composable
private fun CreateFolderDialog(
    parentId: String,
    state: AppState,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun submit() {
        if (busy) return
        val n = name.trim()
        if (n.isEmpty()) { error = "Name is required"; return }
        busy = true; error = null
        scope.launch {
            val msg = state.createFolder(n, parentId.takeIf { it != "__root__" }).await()
            busy = false
            if (msg == null) onDismiss() else error = msg
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (parentId == "__root__") "New folder" else "New subfolder") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("Folder name") },
                    singleLine = true,
                    isError = error != null,
                )
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = MockColors.danger, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { submit() },
                enabled = !busy && name.trim().isNotEmpty(),
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RenameFolderDialog(
    initial: String,
    state: AppState,
    folderId: String,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun submit() {
        if (busy) return
        val n = name.trim()
        if (n.isEmpty()) { error = "Name is required"; return }
        busy = true; error = null
        scope.launch {
            val msg = state.renameFolder(folderId, n).await()
            busy = false
            if (msg == null) onDismiss() else error = msg
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename folder") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("New name") },
                    singleLine = true,
                    isError = error != null,
                )
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = MockColors.danger, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { submit() },
                enabled = !busy && name.trim().isNotEmpty(),
            ) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ConfirmDialog(title: String, message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MockColors.danger,
                ),
            ) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
