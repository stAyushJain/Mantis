package com.mockmaster.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
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
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignTop
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.mockmaster.app.MockColors
import com.mockmaster.app.platform.FileIo
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
    // Inline quick-add: which flow to append a new step to (null = closed).
    var quickAddFlowId by remember { mutableStateOf<String?>(null) }
    // Inline single-step edit: (flowId, step) currently being tweaked.
    var stepEdit by remember { mutableStateOf<Pair<String, FlowStep>?>(null) }
    var stepDeleteConfirm by remember { mutableStateOf<Pair<String, FlowStep>?>(null) }
    var flowQuery by remember { mutableStateOf("") }
    var flowMenuOpen by remember { mutableStateOf(false) }
    var importDialogOpen by remember { mutableStateOf(false) }
    // Filter the flow list by user query (case-insensitive, matches name or
    // description). Empty query shows all.
    val filteredFlows = remember(state.workspace.flows, flowQuery) {
        val q = flowQuery.trim()
        if (q.isEmpty()) state.workspace.flows
        else state.workspace.flows.filter {
            it.name.contains(q, ignoreCase = true) || it.description.contains(q, ignoreCase = true)
        }
    }

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
                Box {
                    IconButton(onClick = { flowMenuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More flow actions")
                    }
                    DropdownMenu(expanded = flowMenuOpen, onDismissRequest = { flowMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Import flow from JSON…") },
                            leadingIcon = { Icon(Icons.Filled.UploadFile, contentDescription = null) },
                            onClick = { flowMenuOpen = false; importDialogOpen = true },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // Tiny single-line filter. Hidden until you have enough flows to
            // benefit from it — keeps the sidebar uncluttered for new users.
            if (state.workspace.flows.size >= 3) {
                OutlinedTextField(
                    value = flowQuery,
                    onValueChange = { flowQuery = it },
                    placeholder = { Text("Filter flows…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
            }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (state.workspace.flows.isEmpty()) {
                    Text(
                        "No flows yet. A flow is an ordered series of mocks that play through together — perfect for booking, signup, etc.",
                        color = MockColors.textSecondary,
                        modifier = Modifier.padding(8.dp),
                    )
                } else if (filteredFlows.isEmpty()) {
                    Text(
                        "No flows match \"$flowQuery\".",
                        color = MockColors.textSecondary,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                filteredFlows.forEach { f ->
                    FlowListItem(
                        flow = f,
                        active = state.workspace.activeFlowId == f.id,
                        selected = state.selectedFlowId == f.id,
                        running = state.workspace.activeFlowId == f.id,
                        onSelect = { state.selectedFlowId = f.id },
                        onDuplicate = { state.duplicateFlow(f.id) },
                        onExport = {
                            state.exportFlowJson(f.id)?.let { json ->
                                FileIo.download("${f.name}.mantis-flow.json", json)
                                state.showToast("Exported \"${f.name}\"")
                            }
                        },
                        onShare = { state.openShareFlow(f.id) },
                        onDelete = { deleteConfirmId = f.id },
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
                    onAddEndpoint = { quickAddFlowId = selected.id },
                    onMoveStep = { stepId, newIndex ->
                        state.moveFlowStep(selected.id, stepId, newIndex)
                    },
                    onEditStep = { step -> stepEdit = selected.id to step },
                    onDeleteStep = { step -> stepDeleteConfirm = selected.id to step },
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
    quickAddFlowId?.let { flowId ->
        QuickAddStepDialog(
            onDismiss = { quickAddFlowId = null },
            onSave = { draft -> state.addFlowStep(flowId, draft) },
        )
    }
    stepEdit?.let { (flowId, step) ->
        QuickAddStepDialog(
            initial = step,
            onDismiss = { stepEdit = null },
            onSave = { draft -> state.updateFlowStep(flowId, draft.copy(id = step.id)) },
        )
    }
    stepDeleteConfirm?.let { (flowId, step) ->
        AlertDialog(
            onDismissRequest = { stepDeleteConfirm = null },
            title = { Text("Remove endpoint?") },
            text = {
                Text("Remove ${step.method} ${step.path} from this flow? This can't be undone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        state.removeFlowStep(flowId, step.id); stepDeleteConfirm = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MockColors.danger),
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { stepDeleteConfirm = null }) { Text("Cancel") }
            },
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
    if (importDialogOpen) {
        ImportFlowDialog(
            onDismiss = { importDialogOpen = false },
            onImport = { json -> state.importFlowJson(json) },
        )
    }
}

@Composable
private fun FlowListItem(
    flow: Flow,
    active: Boolean,
    selected: Boolean,
    running: Boolean,
    onSelect: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(
                if (selected) MockColors.accent.copy(alpha = 0.08f) else Color.Transparent,
                RoundedCornerShape(6.dp),
            )
            .clickable { onSelect() }
            .padding(start = 8.dp, top = 4.dp, bottom = 4.dp, end = 2.dp),
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
            // Per-item overflow menu: duplicate / export / share / delete.
            // Mutating actions are disabled while the flow is running.
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "Flow actions",
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Duplicate") },
                        leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                        enabled = !running,
                        onClick = { menuOpen = false; onDuplicate() },
                    )
                    DropdownMenuItem(
                        text = { Text("Export as JSON") },
                        leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                        onClick = { menuOpen = false; onExport() },
                    )
                    DropdownMenuItem(
                        text = { Text("Share with user…") },
                        leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                        enabled = !running,
                        onClick = { menuOpen = false; onShare() },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = MockColors.danger) },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MockColors.danger) },
                        enabled = !running,
                        onClick = { menuOpen = false; onDelete() },
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
    onAddEndpoint: () -> Unit,
    onMoveStep: (stepId: String, newIndex: Int) -> Unit,
    onEditStep: (FlowStep) -> Unit,
    onDeleteStep: (FlowStep) -> Unit,
) {
    val running = state.workspace.activeFlowId == flow.id
    val anyOtherRunning = state.workspace.activeFlowId != null && !running
    // Disable mutating buttons while the flow is running — same rule that
    // already gates the top-level Edit/Delete buttons.
    val canMutate = !running

    // Per-flow step search query. Reset when switching flows so a query
    // from one flow doesn't carry over into another (the `flow.id` key on
    // remember does this automatically — a new composable instance is
    // created when the user selects a different flow on the left).
    var stepQuery by remember(flow.id) { mutableStateOf("") }
    val q = stepQuery.trim()
    val filteredSteps = remember(flow.steps, q) {
        if (q.isEmpty()) flow.steps
        else flow.steps.filter {
            it.path.contains(q, ignoreCase = true) ||
                it.method.contains(q, ignoreCase = true) ||
                it.body.contains(q, ignoreCase = true)
        }
    }
    val isFiltered = q.isNotEmpty()

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
                OutlinedButton(onClick = onEdit, enabled = canMutate) { Text("Edit") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { state.openShareFlow(flow.id) },
                    enabled = canMutate,
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Share")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onDelete, enabled = canMutate) {
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
                    "Steps execute top-down: the first not-yet-consumed step matching an intercepted call wins. Drag the handle (or use the arrows) on each step to reorder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MockColors.textSecondary,
                )
            }
            Spacer(Modifier.height(12.dp))
            // Steps subheader: count on the left, prominent "+ Add endpoint"
            // on the right so users don't have to open the Edit dialog just
            // to append one more step.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (isFiltered) "Steps (${filteredSteps.size} of ${flow.steps.size})"
                    else "Steps (${flow.steps.size})",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onAddEndpoint,
                    enabled = canMutate,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add endpoint")
                }
            }
            Spacer(Modifier.height(8.dp))
            // Step search bar — only shows up once a flow has enough
            // endpoints to make scrolling tedious. Disables drag-reorder
            // while a query is active (re-ordering a filtered subset is
            // confusing — the move target indexes wouldn't match the
            // underlying full-list indexes).
            if (flow.steps.size >= 4) {
                OutlinedTextField(
                    value = stepQuery,
                    onValueChange = { stepQuery = it },
                    placeholder = { Text("Filter steps by path, method, or body…") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
            }
            if (flow.steps.isEmpty()) {
                Text(
                    "No endpoints yet. Click \"Add endpoint\" above to add one — or use \"Edit\" to bulk-create several.",
                    color = MockColors.textSecondary,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else if (filteredSteps.isEmpty()) {
                Text(
                    "No steps match \"$q\". Clear the filter to see all ${flow.steps.size} endpoints.",
                    color = MockColors.textSecondary,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                DraggableStepList(
                    fullSteps = flow.steps,
                    visibleSteps = filteredSteps,
                    running = running,
                    canMutate = canMutate && !isFiltered,
                    isFiltered = isFiltered,
                    onMove = onMoveStep,
                    onEditStep = onEditStep,
                    onDuplicateStep = { step -> state.duplicateFlowStep(flow.id, step.id) },
                    onDeleteStep = onDeleteStep,
                )
            }
        }
    }
}

/**
 * Drag-and-drop reorderable list for flow steps.
 *
 * Why hand-rolled? `detectDragGestures` / `Modifier.dragAndDropSource` are
 * known to be unreliable on Compose Multiplatform wasmJs as of 1.7.3
 * (JetBrains/compose-multiplatform#4493). The low-level
 * `awaitPointerEventScope { drag(...) }` API does work consistently on
 * wasm, so we use it directly.
 *
 * State model:
 *   - The list of step ids in display order is mirrored locally in
 *     `orderedIds`. While a drag is in progress we mutate this list
 *     immediately so neighbours slide out of the way. On drag end we
 *     fire one `onMove` call and let the backend become the source of
 *     truth on the next refresh.
 *   - `draggingId` + `dragOffsetY` describe the floating row's
 *     translation. We deliberately translate the dragged row visually
 *     while the underlying list reflects the *proposed* new order, so
 *     the user sees siblings shift into place under their finger.
 *   - `itemHeightsPx` is captured per item via `onGloballyPositioned`.
 *     We need heights (not just count) because rows have variable size
 *     when the body preview wraps to a second line.
 */
@Composable
private fun DraggableStepList(
    fullSteps: List<FlowStep>,
    visibleSteps: List<FlowStep>,
    running: Boolean,
    canMutate: Boolean,
    isFiltered: Boolean,
    onMove: (stepId: String, newIndex: Int) -> Unit,
    onEditStep: (FlowStep) -> Unit,
    onDuplicateStep: (FlowStep) -> Unit,
    onDeleteStep: (FlowStep) -> Unit,
) {
    val byId = remember(fullSteps) { fullSteps.associateBy { it.id } }
    val visibleIds = remember(visibleSteps) { visibleSteps.map { it.id }.toSet() }
    // Mirror of step IDs in current display order. Mutated optimistically
    // during a drag; reconciled to `fullSteps` whenever the server-side list
    // changes AND we're not currently dragging.
    val orderedIds = remember { mutableStateListOf<String>().apply { addAll(fullSteps.map { it.id }) } }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    // Heights of each row in pixels, keyed by step id. Used to figure out
    // which slot the floating row currently overlaps.
    val itemHeightsPx = remember { mutableStateMapOf<String, Int>() }
    // Vertical gap between rows from `Arrangement.spacedBy(8.dp)`. We need
    // this in pixels for hit-testing; resolved via LocalDensity below.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val gapPx = with(density) { 8.dp.toPx() }

    // Reconcile with upstream when not dragging: if the server-side step
    // list changed (add/delete/external reorder/share-import), pick up the
    // new ids while preserving local ordering for ids we already know.
    androidx.compose.runtime.LaunchedEffect(fullSteps) {
        if (draggingId == null) {
            val incoming = fullSteps.map { it.id }
            if (incoming != orderedIds.toList()) {
                orderedIds.clear()
                orderedIds.addAll(incoming)
            }
        }
    }

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        orderedIds.forEachIndexed { idx, id ->
            val step = byId[id] ?: return@forEachIndexed
            // While filtering, only render the matching rows but keep the
            // underlying ordering math intact (so the "step #" badge still
            // reflects the real position in the flow, not the filtered
            // position). This is the right mental model: the user is
            // peeking at a subset of a larger ordered list.
            if (isFiltered && id !in visibleIds) return@forEachIndexed
            val isDragging = draggingId == id
            // Pre-compute the displayed index for "move to top/bottom" etc.
            val lastIdx = orderedIds.lastIndex
            // Key composables by step id (not slot) so that during a
            // reorder the same composable instance moves with its step.
            // Without this, a swap would re-bind each slot's pointerInput
            // closure to a different `id`, mid-drag, and the dragged row
            // would visually "switch" with its neighbour. With keying,
            // Compose moves the existing node to its new layout slot.
            androidx.compose.runtime.key(id) {
            FlowStepRow(
                idx = idx + 1,
                step = step,
                running = running,
                canMutate = canMutate,
                isFirst = idx == 0,
                isLast = idx == lastIdx,
                isDragging = isDragging,
                onMoveUp = { onMove(id, idx - 1) },
                onMoveDown = { onMove(id, idx + 1) },
                onMoveToTop = { onMove(id, 0) },
                onMoveToBottom = { onMove(id, lastIdx) },
                onEditStep = { onEditStep(step) },
                onDuplicateStep = { onDuplicateStep(step) },
                onDeleteStep = { onDeleteStep(step) },
                rowModifier = Modifier
                    // Capture each row's measured height so the drag logic
                    // knows when the pointer has crossed into the next slot.
                    .onGloballyPositioned { coords ->
                        itemHeightsPx[id] = coords.size.height
                    }
                    // Lift the dragged row above its neighbours and apply
                    // the live finger translation. Non-dragged rows stay
                    // where layout puts them — Compose handles the gap
                    // animation implicitly when we mutate `orderedIds`.
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer {
                        if (isDragging) {
                            translationY = dragOffsetY
                            alpha = 0.92f
                        }
                    },
                dragHandleModifier = Modifier.pointerInput(canMutate, orderedIds.size) {
                    // No drag handling while the flow is running or only
                    // one item exists (nothing to reorder against).
                    if (!canMutate || orderedIds.size < 2) return@pointerInput
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val startId = id
                            draggingId = startId
                            dragOffsetY = 0f
                            var accumulated = 0f
                            // drag() suspends and re-invokes on every
                            // motion event until the pointer is released
                            // or cancelled. Returns false if cancelled.
                            val completed = drag(down.id) { change ->
                                val delta = change.position.y - change.previousPosition.y
                                accumulated += delta
                                dragOffsetY = accumulated
                                change.consume()
                                // Slot detection: once the floating row's
                                // centre crosses the centre of a neighbour,
                                // swap them. We use the neighbour's height
                                // + gap as the threshold so big rows
                                // (multi-line body preview) need to be
                                // dragged further before swapping.
                                val currentIdx = orderedIds.indexOf(startId)
                                if (currentIdx < 0) return@drag
                                if (accumulated < 0 && currentIdx > 0) {
                                    val above = orderedIds[currentIdx - 1]
                                    val threshold = -((itemHeightsPx[above] ?: 0) + gapPx) / 2f
                                    if (accumulated < threshold) {
                                        orderedIds.removeAt(currentIdx)
                                        orderedIds.add(currentIdx - 1, startId)
                                        // Reset relative origin so further
                                        // movement is measured from the
                                        // new slot, otherwise the row
                                        // would visually snap back.
                                        accumulated -= threshold * 2f
                                        dragOffsetY = accumulated
                                    }
                                } else if (accumulated > 0 && currentIdx < orderedIds.lastIndex) {
                                    val below = orderedIds[currentIdx + 1]
                                    val threshold = ((itemHeightsPx[below] ?: 0) + gapPx) / 2f
                                    if (accumulated > threshold) {
                                        orderedIds.removeAt(currentIdx)
                                        orderedIds.add(currentIdx + 1, startId)
                                        accumulated -= threshold * 2f
                                        dragOffsetY = accumulated
                                    }
                                }
                            }
                            val finalIdx = orderedIds.indexOf(startId)
                            val originalIdx = fullSteps.indexOfFirst { it.id == startId }
                            draggingId = null
                            dragOffsetY = 0f
                            // Only call the backend if the drag actually
                            // changed the order. Cancellation (e.g.
                            // pointer leaves window) also lands here.
                            if (completed && finalIdx >= 0 && finalIdx != originalIdx) {
                                onMove(startId, finalIdx)
                            } else if (!completed) {
                                // Snap back: undo any optimistic shuffling.
                                orderedIds.clear()
                                orderedIds.addAll(fullSteps.map { it.id })
                            }
                        }
                    }
                },
            )
            } // end key(id)
        }
    }
}

@Composable
private fun FlowStepRow(
    idx: Int,
    step: FlowStep,
    running: Boolean,
    canMutate: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onMoveToTop: () -> Unit,
    onMoveToBottom: () -> Unit,
    onEditStep: () -> Unit,
    onDeleteStep: () -> Unit,
    // Drag-related parameters carry defaults so the dialog editor can still
    // reuse the row component (it doesn't drag).
    isDragging: Boolean = false,
    rowModifier: Modifier = Modifier,
    dragHandleModifier: Modifier = Modifier,
    onDuplicateStep: (() -> Unit)? = null,
) {
    val consumedAlpha = if (running && step.isConsumed) 0.45f else 1f
    var menuOpen by remember { mutableStateOf(false) }
    val borderColor = if (isDragging) MockColors.accent else MockColors.border
    val borderWidth = if (isDragging) 2.dp else 1.dp

    Box(
        modifier = rowModifier
            .fillMaxWidth()
            .border(borderWidth, borderColor, RoundedCornerShape(8.dp))
            .background(MockColors.surfaceMuted2, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Drag handle — only when the flow is editable. Cursor
                // changes to grab on hover (browser default for this icon
                // role on web). Tap targets are 32dp so they stay easy
                // to grab on touch screens.
                if (canMutate) {
                    Box(
                        modifier = dragHandleModifier
                            .size(28.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.DragIndicator,
                            contentDescription = "Drag to reorder",
                            modifier = Modifier.size(20.dp),
                            tint = MockColors.textSecondary,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }
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
                    Spacer(Modifier.width(4.dp))
                }
                // Reorder + overflow controls. Hidden during a running flow
                // because mutating the active flow could surprise the user
                // mid-playback; same rationale that disables Edit/Delete.
                if (canMutate) {
                    IconButton(
                        onClick = onMoveUp,
                        enabled = !isFirst,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Filled.ArrowUpward,
                            contentDescription = "Move up",
                            modifier = Modifier.size(18.dp),
                            tint = if (isFirst) MockColors.textSecondary.copy(alpha = 0.4f) else MockColors.textPrimary,
                        )
                    }
                    IconButton(
                        onClick = onMoveDown,
                        enabled = !isLast,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Filled.ArrowDownward,
                            contentDescription = "Move down",
                            modifier = Modifier.size(18.dp),
                            tint = if (isLast) MockColors.textSecondary.copy(alpha = 0.4f) else MockColors.textPrimary,
                        )
                    }
                    Box {
                        IconButton(
                            onClick = { menuOpen = true },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = "More actions",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Edit body…") },
                                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                onClick = { menuOpen = false; onEditStep() },
                            )
                            if (onDuplicateStep != null) {
                                DropdownMenuItem(
                                    text = { Text("Duplicate") },
                                    leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                                    onClick = { menuOpen = false; onDuplicateStep() },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Move to top") },
                                leadingIcon = { Icon(Icons.Filled.VerticalAlignTop, contentDescription = null) },
                                enabled = !isFirst,
                                onClick = { menuOpen = false; onMoveToTop() },
                            )
                            DropdownMenuItem(
                                text = { Text("Move to bottom") },
                                leadingIcon = { Icon(Icons.Filled.VerticalAlignBottom, contentDescription = null) },
                                enabled = !isLast,
                                onClick = { menuOpen = false; onMoveToBottom() },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete", color = MockColors.danger) },
                                leadingIcon = {
                                    Icon(Icons.Filled.Delete, contentDescription = null, tint = MockColors.danger)
                                },
                                onClick = { menuOpen = false; onDeleteStep() },
                            )
                        }
                    }
                }
            }
            val preview = step.body.replace("\n", " ").take(160)
            if (preview.isNotEmpty()) {
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
                    Text("Steps (${drafts.size})", style = MaterialTheme.typography.titleMedium)
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
                            isFirst = idx == 0,
                            isLast = idx == drafts.lastIndex,
                            onChange = { drafts[idx] = it },
                            onMoveUp = {
                                if (idx > 0) {
                                    val item = drafts.removeAt(idx); drafts.add(idx - 1, item)
                                }
                            },
                            onMoveDown = {
                                if (idx < drafts.lastIndex) {
                                    val item = drafts.removeAt(idx); drafts.add(idx + 1, item)
                                }
                            },
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
    isFirst: Boolean,
    isLast: Boolean,
    onChange: (StepDraft) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
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
                IconButton(
                    onClick = onMoveUp,
                    enabled = !isFirst,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Filled.ArrowUpward,
                        contentDescription = "Move up",
                        modifier = Modifier.size(18.dp),
                        tint = if (isFirst) MockColors.textSecondary.copy(alpha = 0.4f) else MockColors.textPrimary,
                    )
                }
                IconButton(
                    onClick = onMoveDown,
                    enabled = !isLast,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Filled.ArrowDownward,
                        contentDescription = "Move down",
                        modifier = Modifier.size(18.dp),
                        tint = if (isLast) MockColors.textSecondary.copy(alpha = 0.4f) else MockColors.textPrimary,
                    )
                }
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

// ---------- Quick add / edit single-step dialog ----------

/**
 * Lightweight single-step editor used both for the inline "+ Add endpoint"
 * button on the FlowDetail view and for per-step "Edit body…" actions from
 * the overflow menu. Reuses the same validation as the bulk editor.
 *
 * When `initial` is null we're adding; otherwise we're editing in place
 * (the caller is responsible for stamping the resulting draft with the
 * existing step id before sending it to the backend).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickAddStepDialog(
    initial: FlowStep? = null,
    onDismiss: () -> Unit,
    onSave: (UpsertFlowStepReq) -> kotlinx.coroutines.Deferred<String?>,
) {
    var method by remember { mutableStateOf(initial?.method ?: "GET") }
    var path by remember { mutableStateOf(initial?.path ?: "") }
    var statusCode by remember { mutableStateOf((initial?.statusCode ?: 200).toString()) }
    var body by remember { mutableStateOf(initial?.body ?: "{\n  \n}") }
    var methodMenuOpen by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun submit() {
        if (busy) return
        val st = statusCode.toIntOrNull()
        if (st == null || st !in 100..599) {
            error = "Status must be a number between 100 and 599."; return
        }
        if (path.isBlank()) {
            error = "URL path is required."; return
        }
        val cleanBody = formatJsonOrNull(body)
        if (cleanBody == null) {
            error = "Body must be valid JSON. Try the Format button."; return
        }
        error = null; busy = true
        scope.launch {
            val msg = onSave(
                UpsertFlowStepReq(
                    id = initial?.id,
                    path = path.trim(),
                    method = method,
                    statusCode = st,
                    body = cleanBody,
                ),
            ).await()
            busy = false
            if (msg == null) onDismiss() else error = msg
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
                .widthIn(min = 560.dp, max = 760.dp)
                .padding(24.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    if (initial == null) "Add endpoint" else "Edit endpoint",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (initial == null)
                        "Appended to the end of the flow. Reorder it later with the up/down arrows on each step."
                    else
                        "Editing an existing endpoint — position in the flow is preserved.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MockColors.textSecondary,
                )
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ExposedDropdownMenuBox(
                        expanded = methodMenuOpen,
                        onExpandedChange = { methodMenuOpen = it },
                        modifier = Modifier.width(120.dp),
                    ) {
                        OutlinedTextField(
                            value = method,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Method") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(methodMenuOpen) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                        )
                        DropdownMenu(
                            expanded = methodMenuOpen,
                            onDismissRequest = { methodMenuOpen = false },
                        ) {
                            HTTP_METHODS.forEach { m ->
                                DropdownMenuItem(text = { Text(m) }, onClick = {
                                    method = m; methodMenuOpen = false
                                })
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = path,
                        onValueChange = { path = it },
                        label = { Text("URL path") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = statusCode,
                        onValueChange = { statusCode = it.filter { ch -> ch.isDigit() }.take(3) },
                        label = { Text("Status") },
                        singleLine = true,
                        modifier = Modifier.width(100.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Body (JSON)", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = {
                        val pretty = formatJsonOrNull(body) ?: return@OutlinedButton
                        body = pretty
                    }) {
                        Icon(Icons.Filled.AutoFixHigh, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Format")
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    error?.let {
                        Text(it, color = MockColors.danger, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { submit() },
                        enabled = !busy && path.trim().isNotEmpty(),
                    ) {
                        Text(if (initial == null) "Add endpoint" else "Save changes")
                    }
                }
            }
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

// ---------- Import flow dialog ----------

/**
 * Two-step dialog: either upload a `.mantis-flow.json` file via the OS
 * picker, or paste raw JSON. We keep both paths so users without file
 * access (e.g. browser sandbox quirks) can still copy/paste content from
 * a chat or git diff. JSON is validated client-side by `state.importFlowJson`
 * before any backend call.
 */
@Composable
private fun ImportFlowDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> kotlinx.coroutines.Deferred<String?>,
) {
    var pasted by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun submit(json: String) {
        val cleaned = json.trim()
        if (cleaned.isEmpty()) { error = "Please paste JSON or pick a file."; return }
        busy = true; error = null
        scope.launch {
            val msg = onImport(cleaned).await()
            busy = false
            if (msg == null) onDismiss() else error = msg
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import flow") },
        text = {
            Column {
                Text(
                    "Import a flow exported from Mantis (or any tool that produces the same JSON shape). The flow gets fresh ids and is renamed if a flow with the same name already exists.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MockColors.textSecondary,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        FileIo.pickTextFile("application/json,.json") { text ->
                            if (text == null) {
                                error = "No file picked."
                            } else {
                                pasted = text
                            }
                        }
                    },
                    enabled = !busy,
                ) {
                    Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Choose .json file…")
                }
                Spacer(Modifier.height(12.dp))
                Text("or paste JSON below:", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = pasted,
                    onValueChange = { pasted = it; error = null },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    placeholder = { Text("{ \"name\": \"…\", \"steps\": [ … ] }") },
                )
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = MockColors.danger, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { submit(pasted) },
                enabled = !busy && pasted.isNotBlank(),
            ) { Text(if (busy) "Importing…" else "Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
