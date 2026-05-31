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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mockmaster.app.MockColors
import com.mockmaster.shared.UpsertFlowReq
import com.mockmaster.shared.UpsertFlowStepReq
import com.mockmaster.app.state.AppState
import com.mockmaster.shared.Flow
import com.mockmaster.shared.FlowStep
import kotlinx.coroutines.launch

@Composable
fun FlowsScreen(state: AppState) {
    var editing by remember { mutableStateOf<Flow?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleteConfirmId by remember { mutableStateOf<String?>(null) }

    Row(Modifier.fillMaxSize()) {
        // Flow list
        Column(
            modifier = Modifier
                .width(320.dp)
                .fillMaxHeight()
                .border(1.dp, MockColors.border, RoundedCornerShape(8.dp))
                .background(MockColors.surface, RoundedCornerShape(8.dp))
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Flows", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { creating = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "New flow")
                }
            }
            Spacer(Modifier.height(8.dp))
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (state.workspace.flows.isEmpty()) {
                    Text(
                        "No flows yet. A flow is an ordered series of mocks that play through together — perfect for booking, signup, etc.",
                        color = MockColors.textSecondary,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                state.workspace.flows.forEach { f ->
                    FlowListItem(
                        flow = f,
                        active = state.workspace.activeFlowId == f.id,
                        selected = state.selectedFlowId == f.id,
                        onSelect = { state.selectedFlowId = f.id },
                    )
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val selected = state.selectedFlowId?.let { id -> state.workspace.flows.firstOrNull { it.id == id } }
            if (selected == null) {
                Card(Modifier.fillMaxSize()) {
                    Column(
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Text("Select a flow on the left, or create a new one.", color = MockColors.textSecondary)
                    }
                }
            } else {
                FlowDetail(
                    flow = selected,
                    state = state,
                    onEdit = { editing = selected },
                    onDelete = { deleteConfirmId = selected.id },
                )
            }
        }
    }

    if (creating) {
        FlowEditorDialog(
            initial = null,
            state = state,
            onDismiss = { creating = false },
        )
    }
    editing?.let { f ->
        FlowEditorDialog(
            initial = f,
            state = state,
            onDismiss = { editing = null },
        )
    }
    deleteConfirmId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteConfirmId = null },
            title = { Text("Delete flow?") },
            text = { Text("This will permanently remove the flow and all its steps.") },
            confirmButton = {
                Button(
                    onClick = { state.deleteFlow(id); deleteConfirmId = null },
                    colors = ButtonDefaults.buttonColors(containerColor = MockColors.danger),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteConfirmId = null }) { Text("Cancel") } },
        )
    }
    state.sharingFlowId?.let { id ->
        ShareFlowDialog(
            flow = state.workspace.flows.firstOrNull { it.id == id },
            currentUser = state.currentUser ?: "",
            onDismiss = { state.closeShareFlow() },
            onSubmit = { target -> state.shareFlow(id, target) },
        )
    }
}

@Composable
private fun FlowListItem(flow: Flow, active: Boolean, selected: Boolean, onSelect: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(
                if (selected) MockColors.accent.copy(alpha = 0.08f) else Color.Transparent,
                RoundedCornerShape(6.dp),
            )
            .clickable { onSelect() }
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    flow.name,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
                Text(
                    "${flow.steps.size} steps",
                    color = MockColors.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (active) {
                Surface(
                    color = MockColors.warning.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(
                        "RUNNING",
                        style = MaterialTheme.typography.labelMedium,
                        color = MockColors.warning,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FlowDetail(
    flow: Flow,
    state: AppState,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val running = state.workspace.activeFlowId == flow.id
    val anyOtherRunning = state.workspace.activeFlowId != null && !running

    Card(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(flow.name, style = MaterialTheme.typography.titleLarge)
                    if (flow.description.isNotBlank()) {
                        Text(flow.description, color = MockColors.textSecondary)
                    }
                }
                if (running) {
                    Button(
                        onClick = { state.stopFlow() },
                        colors = ButtonDefaults.buttonColors(containerColor = MockColors.danger),
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("End flow")
                    }
                } else {
                    Button(
                        onClick = { state.startFlow(flow.id) },
                        enabled = !anyOtherRunning,
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Start flow")
                    }
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onEdit, enabled = !running) { Text("Edit") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { state.openShareFlow(flow.id) },
                    enabled = !running,
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Share")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onDelete, enabled = !running) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            if (running) {
                Text(
                    "Flow is running — folder mocks are paused. Each step below is consumed once in order; if all matching steps are consumed, the last one is replayed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MockColors.warning,
                )
            } else if (anyOtherRunning) {
                Text(
                    "Another flow is currently running. Stop it before starting a new one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MockColors.warning,
                )
            } else {
                Text(
                    "Steps execute top-down: the first not-yet-consumed step matching an intercepted call wins.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MockColors.textSecondary,
                )
            }
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (flow.steps.isEmpty()) {
                    Text(
                        "No steps yet. Click \"Edit\" to add some.",
                        color = MockColors.textSecondary,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                flow.steps.forEachIndexed { idx, step ->
                    FlowStepRow(idx = idx + 1, step = step, running = running)
                }
            }
        }
    }
}

@Composable
private fun FlowStepRow(idx: Int, step: FlowStep, running: Boolean) {
    val consumedAlpha = if (running && step.isConsumed) 0.45f else 1f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MockColors.border, RoundedCornerShape(8.dp))
            .background(MockColors.surfaceMuted2, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = MockColors.accent.copy(alpha = 0.1f), shape = RoundedCornerShape(50)) {
                    Text(
                        "$idx",
                        color = MockColors.accent,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                MethodPill(step.method)
                Spacer(Modifier.width(6.dp))
                StatusPill(step.statusCode)
                Spacer(Modifier.width(8.dp))
                Text(
                    step.path,
                    fontFamily = FontFamily.Monospace,
                    color = MockColors.textPrimary.copy(alpha = consumedAlpha),
                    modifier = Modifier.weight(1f),
                )
                if (running && step.isConsumed) {
                    Text(
                        "consumed",
                        style = MaterialTheme.typography.labelMedium,
                        color = MockColors.textSecondary,
                    )
                }
            }
            val preview = step.body.replace("\n", " ").take(160)
            Text(
                if (preview.length < step.body.length) "$preview…" else preview,
                style = MaterialTheme.typography.bodySmall,
                color = MockColors.textSecondary,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

// ---------- Flow editor dialog ----------

private val HTTP_METHODS = listOf("GET", "POST", "PUT", "DELETE", "PATCH")

private data class StepDraft(
    var id: String?,
    var path: String,
    var method: String,
    var statusCode: String,
    var body: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlowEditorDialog(
    initial: Flow?,
    state: AppState,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var saveError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val drafts = remember {
        mutableStateListOf<StepDraft>().apply {
            initial?.steps?.forEach {
                add(StepDraft(it.id, it.path, it.method, it.statusCode.toString(), it.body))
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            color = MockColors.surface,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .widthIn(min = 720.dp, max = 1000.dp)
                .padding(24.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(if (initial == null) "New flow" else "Edit flow", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(12.dp))
                Row {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Flow name") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Description (optional)") },
                        modifier = Modifier.weight(2f),
                        singleLine = true,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Steps", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = {
                        drafts.add(StepDraft(null, "", "GET", "200", "{\n  \n}"))
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add step")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .height(440.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (drafts.isEmpty()) {
                        Text(
                            "Add steps in the order you expect the app to call them. Multiple steps can share the same path+method — they'll be consumed in order.",
                            color = MockColors.textSecondary,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                    drafts.forEachIndexed { idx, draft ->
                        StepDraftRow(
                            idx = idx + 1,
                            draft = draft,
                            onChange = { drafts[idx] = it },
                            onRemove = { drafts.removeAt(idx) },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    saveError?.let {
                        Text(it, color = MockColors.danger, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            // Validate: every step needs path + valid status, body must be JSON.
                            val cleaned = drafts.mapNotNull { d ->
                                val st = d.statusCode.toIntOrNull() ?: return@mapNotNull null
                                if (st !in 100..599) return@mapNotNull null
                                if (d.path.isBlank()) return@mapNotNull null
                                val body = formatJsonOrNull(d.body) ?: return@mapNotNull null
                                UpsertFlowStepReq(
                                    id = d.id,
                                    path = d.path.trim(),
                                    method = d.method,
                                    statusCode = st,
                                    body = body,
                                )
                            }
                            if (cleaned.size != drafts.size && drafts.isNotEmpty()) {
                                saveError = "Every step needs a path, a 100–599 status, and valid JSON."
                                return@Button
                            }
                            busy = true; saveError = null
                            scope.launch {
                                val msg = state.upsertFlow(
                                    UpsertFlowReq(
                                        id = initial?.id,
                                        name = name.trim(),
                                        description = description.trim(),
                                        steps = cleaned,
                                    ),
                                ).await()
                                busy = false
                                if (msg == null) onDismiss() else saveError = msg
                            }
                        },
                        enabled = !busy && name.trim().isNotEmpty(),
                    ) { Text(if (initial == null) "Create flow" else "Save changes") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StepDraftRow(
    idx: Int,
    draft: StepDraft,
    onChange: (StepDraft) -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var local by remember(draft) { mutableStateOf(draft) }

    fun update(block: StepDraft.() -> StepDraft) {
        local = local.block()
        onChange(local)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MockColors.border, RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = MockColors.accent.copy(alpha = 0.1f), shape = RoundedCornerShape(50)) {
                    Text(
                        "$idx",
                        color = MockColors.accent,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                ExposedDropdownMenuBox(
                    expanded = menuOpen,
                    onExpandedChange = { menuOpen = it },
                    modifier = Modifier.width(120.dp),
                ) {
                    OutlinedTextField(
                        value = local.method,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Method") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(menuOpen) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        HTTP_METHODS.forEach { m ->
                            DropdownMenuItem(text = { Text(m) }, onClick = {
                                update { copy(method = m) }
                                menuOpen = false
                            })
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = local.path,
                    onValueChange = { update { copy(path = it) } },
                    label = { Text("URL path") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = local.statusCode,
                    onValueChange = { update { copy(statusCode = it.filter { ch -> ch.isDigit() }.take(3)) } },
                    label = { Text("Status") },
                    singleLine = true,
                    modifier = Modifier.width(100.dp),
                )
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove step", tint = MockColors.danger)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Body (JSON)", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = {
                    val pretty = formatJsonOrNull(local.body) ?: return@OutlinedButton
                    update { copy(body = pretty) }
                }) {
                    Icon(Icons.Filled.AutoFixHigh, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Format")
                }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = local.body,
                onValueChange = { update { copy(body = it) } },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}

// ---------- Share dialog ----------

@Composable
private fun ShareFlowDialog(
    flow: Flow?,
    currentUser: String,
    onDismiss: () -> Unit,
    onSubmit: (String) -> kotlinx.coroutines.Deferred<String?>,
) {
    var target by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun submit() {
        if (busy) return
        val t = target.trim()
        if (t.isEmpty()) { error = "Enter a username"; return }
        if (t.equals(currentUser, ignoreCase = true)) {
            error = "Cannot share with yourself"; return
        }
        busy = true; error = null
        scope.launch {
            val msg = onSubmit(t).await()
            busy = false
            if (msg == null) onDismiss() else error = msg
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share flow") },
        text = {
            Column {
                Text(
                    "A snapshot copy of \"${flow?.name ?: "this flow"}\" will appear in the recipient's workspace. They can edit or delete it freely without affecting your original.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MockColors.textSecondary,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it; error = null },
                    label = { Text("Recipient's username") },
                    singleLine = true,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
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
                enabled = !busy && target.trim().isNotEmpty(),
            ) { Text("Share") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
