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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.mockmaster.app.platform.FileIo
import com.mockmaster.app.state.AppState
import com.mockmaster.shared.InterceptedCall
import com.mockmaster.shared.UpsertRuleReq
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

// Pre-baked, pretty JSON formatter for the "Export logs" action. Kept at
// file scope so we don't recreate it on every recomposition.
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private val EXPORT_JSON = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    encodeDefaults = true
    ignoreUnknownKeys = true
}

private enum class MatchFilter { ALL, MATCHED, PASSTHROUGH }

@Composable
fun LogsScreen(state: AppState) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<InterceptedCall?>(null) }
    // Filter pills. We store the active method set as null = no method
    // filter, vs a non-null set = restrict to those methods. Same for
    // statusBuckets (200/300/400/500). MatchFilter is a tri-state enum.
    var methodFilter by remember { mutableStateOf<Set<String>?>(null) }
    var statusFilter by remember { mutableStateOf<Set<Int>?>(null) }
    var matchFilter by remember { mutableStateOf(MatchFilter.ALL) }
    // "Promote to mock" picker target: null = closed; otherwise the call
    // the user wants to turn into a rule or flow step.
    var promote by remember { mutableStateOf<InterceptedCall?>(null) }

    val filtered = remember(state.logs.toList(), query, methodFilter, statusFilter, matchFilter) {
        state.logs.asSequence().filter { call ->
            // Text filter
            val q = query.trim()
            val matchesText = q.isEmpty() ||
                call.path.contains(q, ignoreCase = true) ||
                call.url.contains(q, ignoreCase = true) ||
                call.method.contains(q, ignoreCase = true)
            // Method pills
            val matchesMethod = methodFilter?.let { it.contains(call.method.uppercase()) } ?: true
            // Status family pills (200/300/400/500). Passthrough = statusCode 0.
            val bucket = when {
                call.statusCode in 200..299 -> 200
                call.statusCode in 300..399 -> 300
                call.statusCode in 400..499 -> 400
                call.statusCode >= 500 -> 500
                else -> 0
            }
            val matchesStatus = statusFilter?.let { it.contains(bucket) } ?: true
            // Matched / passthrough toggle
            val matchesMatched = when (matchFilter) {
                MatchFilter.ALL -> true
                MatchFilter.MATCHED -> call.matched
                MatchFilter.PASSTHROUGH -> !call.matched
            }
            matchesText && matchesMethod && matchesStatus && matchesMatched
        }
            // Newest first. Backend's GET /logs returns oldest-first while the
            // SSE stream prepends new entries, so the bootstrap list can come
            // in mixed order. Sort by timestamp descending for a consistent UX.
            .sortedByDescending { it.timestamp }
            .toList()
    }

    Row(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1.2f)
                .fillMaxHeight()
                .border(1.dp, MockColors.border, RoundedCornerShape(8.dp))
                .background(MockColors.surface, RoundedCornerShape(8.dp))
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Intercepted calls", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                Text(
                    "${filtered.size} of ${state.logs.size}",
                    color = MockColors.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.width(12.dp))
                OutlinedButton(
                    onClick = {
                        if (filtered.isEmpty()) {
                            state.showToast("No entries to export")
                        } else {
                            val json = EXPORT_JSON.encodeToString(
                                ListSerializer(InterceptedCall.serializer()),
                                filtered,
                            )
                            FileIo.download("mantis-logs.json", json)
                            state.showToast("Exported ${filtered.size} entries")
                        }
                    },
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Export")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { state.clearLogs() }) {
                    Icon(Icons.Filled.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Clear")
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Filter by path, URL, method…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            // Filter pills. Click toggles a method into / out of the active
            // set; clicking with no active set selected starts a new
            // single-member set. "All" clears every filter at once.
            FilterRow(
                methodFilter = methodFilter,
                statusFilter = statusFilter,
                matchFilter = matchFilter,
                onMethodToggle = { m ->
                    val cur = methodFilter
                    methodFilter = when {
                        cur == null -> setOf(m)
                        m in cur && cur.size == 1 -> null
                        m in cur -> cur - m
                        else -> cur + m
                    }
                },
                onStatusToggle = { bucket ->
                    val cur = statusFilter
                    statusFilter = when {
                        cur == null -> setOf(bucket)
                        bucket in cur && cur.size == 1 -> null
                        bucket in cur -> cur - bucket
                        else -> cur + bucket
                    }
                },
                onMatchFilter = { matchFilter = it },
                onReset = {
                    methodFilter = null
                    statusFilter = null
                    matchFilter = MatchFilter.ALL
                    query = ""
                },
            )
            Spacer(Modifier.height(8.dp))
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (filtered.isEmpty()) {
                    Text(
                        if (state.logs.isEmpty())
                            "Waiting for traffic. Set your device proxy to ${state.serverInfo?.localIp ?: "<your-ip>"}:${state.serverInfo?.proxyPort ?: 8080} and trigger a request."
                        else "No matches with the current filters.",
                        color = MockColors.textSecondary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                filtered.forEach { call ->
                    LogRow(
                        call = call,
                        selected = selected?.id == call.id,
                        onClick = { selected = call },
                        onPromote = { promote = call },
                    )
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val sel = selected
            if (sel == null) {
                Card(Modifier.fillMaxSize()) {
                    Column(
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxSize(),
                    ) { Text("Select an entry to view request/response.", color = MockColors.textSecondary) }
                }
            } else {
                LogDetail(sel, onPromote = { promote = sel })
            }
        }
    }

    promote?.let { call ->
        PromoteCallDialog(
            call = call,
            state = state,
            onDismiss = { promote = null },
        )
    }
}

@Composable
private fun FilterRow(
    methodFilter: Set<String>?,
    statusFilter: Set<Int>?,
    matchFilter: MatchFilter,
    onMethodToggle: (String) -> Unit,
    onStatusToggle: (Int) -> Unit,
    onMatchFilter: (MatchFilter) -> Unit,
    onReset: () -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            listOf("GET", "POST", "PUT", "DELETE", "PATCH").forEach { m ->
                val active = methodFilter?.contains(m) == true
                FilterPill(m, active, accent = MockColors.accent) { onMethodToggle(m) }
                Spacer(Modifier.width(4.dp))
            }
            Spacer(Modifier.width(8.dp))
            listOf(200 to "2xx", 300 to "3xx", 400 to "4xx", 500 to "5xx").forEach { (code, label) ->
                val active = statusFilter?.contains(code) == true
                val tint = when (code) {
                    200 -> MockColors.success
                    300 -> MockColors.accent
                    400 -> MockColors.warning
                    else -> MockColors.danger
                }
                FilterPill(label, active, accent = tint) { onStatusToggle(code) }
                Spacer(Modifier.width(4.dp))
            }
            Spacer(Modifier.weight(1f))
            if (methodFilter != null || statusFilter != null || matchFilter != MatchFilter.ALL) {
                TextButton(onClick = onReset) { Text("Reset", style = MaterialTheme.typography.labelMedium) }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MatchFilter.entries.forEach { mf ->
                val active = matchFilter == mf
                val label = when (mf) {
                    MatchFilter.ALL -> "All"
                    MatchFilter.MATCHED -> "Mocked only"
                    MatchFilter.PASSTHROUGH -> "Passthrough only"
                }
                FilterPill(label, active, accent = MockColors.accent) { onMatchFilter(mf) }
                Spacer(Modifier.width(4.dp))
            }
        }
    }
}

@Composable
private fun FilterPill(text: String, active: Boolean, accent: Color, onClick: () -> Unit) {
    val bg = if (active) accent.copy(alpha = 0.18f) else Color.Transparent
    val border = if (active) accent else MockColors.border
    val fg = if (active) accent else MockColors.textSecondary
    Box(
        modifier = Modifier
            .border(1.dp, border, RoundedCornerShape(50))
            .background(bg, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text,
            color = fg,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun LogRow(
    call: InterceptedCall,
    selected: Boolean,
    onClick: () -> Unit,
    onPromote: () -> Unit,
) {
    val borderColor = when {
        call.matched && call.matchSource == "flow" -> MockColors.warning
        call.matched -> MockColors.success
        else -> MockColors.border
    }
    var menuOpen by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .border(1.dp, borderColor.copy(alpha = if (selected) 1f else 0.4f), RoundedCornerShape(6.dp))
            .background(
                if (selected) MockColors.accent.copy(alpha = 0.06f) else Color.Transparent,
                RoundedCornerShape(6.dp),
            )
            .clickable { onClick() }
            .padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 2.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MethodPill(call.method)
                Spacer(Modifier.width(6.dp))
                if (call.matched) StatusPill(call.statusCode) else SourceBadge("PASS")
                Spacer(Modifier.width(8.dp))
                Text(
                    call.path,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                )
                if (call.matched) {
                    Text(
                        "${call.matchSource}${if (call.matchLabel.isNotEmpty()) " · ${call.matchLabel}" else ""}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MockColors.textSecondary,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                // Overflow menu: "Use as mock" + copy URL.
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Actions", modifier = Modifier.size(16.dp))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Use as mock…") },
                            leadingIcon = { Icon(Icons.Filled.Bolt, contentDescription = null) },
                            onClick = { menuOpen = false; onPromote() },
                        )
                        DropdownMenuItem(
                            text = { Text("Copy URL") },
                            leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                            onClick = { menuOpen = false; FileIo.copyToClipboard(call.url) },
                        )
                    }
                }
            }
            Text(
                call.timestamp.take(19).replace("T", " "),
                style = MaterialTheme.typography.bodySmall,
                color = MockColors.textSecondary,
            )
        }
    }
}

@Composable
private fun SourceBadge(label: String) {
    Surface(color = MockColors.textSecondary.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp)) {
        Text(
            label,
            color = MockColors.textSecondary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun LogDetail(call: InterceptedCall, onPromote: () -> Unit) {
    Card(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MethodPill(call.method)
                Spacer(Modifier.width(8.dp))
                if (call.matched) StatusPill(call.statusCode)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (call.matched) "Mocked (${call.matchSource})" else "Passthrough",
                    color = if (call.matched) MockColors.success else MockColors.textSecondary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onPromote) {
                    Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Use as mock")
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(call.url, fontFamily = FontFamily.Monospace)
            Text(call.timestamp, style = MaterialTheme.typography.bodySmall, color = MockColors.textSecondary)

            Spacer(Modifier.height(16.dp))
            DetailSection("Request headers") {
                if (call.requestHeaders.isEmpty()) Text("(none)", color = MockColors.textSecondary)
                else call.requestHeaders.forEach { (k, v) ->
                    Row {
                        Text("$k: ", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                        Text(v, fontFamily = FontFamily.Monospace, color = MockColors.textSecondary)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            DetailSection("Request body") {
                if (call.requestBody.isBlank()) Text("(empty)", color = MockColors.textSecondary)
                else CodeBlock(call.requestBody)
            }
            Spacer(Modifier.height(12.dp))
            val responseTitle = if (call.matched) "Mocked response" else "Upstream response"
            DetailSection(responseTitle) {
                if (call.responseBody.isBlank()) {
                    Text("(empty)", color = MockColors.textSecondary)
                } else {
                    CodeBlock(formatJsonOrNull(call.responseBody) ?: call.responseBody)
                }
            }
        }
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun CodeBlock(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MockColors.surfaceMuted, RoundedCornerShape(6.dp))
            .padding(10.dp),
    ) {
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * "Use as mock" dialog: pick a target folder or flow, and the intercepted
 * call becomes a new rule / flow step with its path, method, status, and
 * (for matched calls) original response body pre-filled. This is the big
 * "I just made this call by hand, now let me mock it" workflow.
 */
@Composable
private fun PromoteCallDialog(
    call: InterceptedCall,
    state: AppState,
    onDismiss: () -> Unit,
) {
    // Flatten the folder tree to a "Acme / Login" → id map for the picker.
    val folderOptions = remember(state.workspace.folders) {
        buildList {
            fun walk(nodes: List<com.mockmaster.shared.FolderNode>, prefix: String) {
                nodes.forEach { f ->
                    val label = if (prefix.isEmpty()) f.name else "$prefix / ${f.name}"
                    add(label to f.id)
                    walk(f.subFolders, label)
                }
            }
            walk(state.workspace.folders, "")
        }
    }
    val flowOptions = remember(state.workspace.flows) {
        state.workspace.flows.map { it.name to it.id }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Use as mock") },
        text = {
            Column(modifier = Modifier.height(360.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Add ${call.method} ${call.path} to:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                if (folderOptions.isEmpty() && flowOptions.isEmpty()) {
                    Text(
                        "Create a folder or flow first, then come back here.",
                        color = MockColors.textSecondary,
                    )
                }
                if (folderOptions.isNotEmpty()) {
                    Text("Folder rule", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(4.dp))
                    folderOptions.forEach { (label, id) ->
                        TargetRow(
                            icon = Icons.Filled.Folder,
                            label = label,
                            onClick = {
                                state.upsertRule(id, state.seedRuleFromLog(call))
                                state.showToast("Added to \"$label\"")
                                onDismiss()
                            },
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
                if (flowOptions.isNotEmpty()) {
                    Text("Flow step (appended)", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(4.dp))
                    flowOptions.forEach { (label, id) ->
                        TargetRow(
                            icon = Icons.Filled.Bolt,
                            label = label,
                            onClick = {
                                state.addFlowStep(id, state.seedFlowStepFromLog(call))
                                state.showToast("Added to flow \"$label\"")
                                onDismiss()
                            },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TargetRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .border(1.dp, MockColors.border, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MockColors.textSecondary)
        Spacer(Modifier.width(8.dp))
        Text(label, fontFamily = FontFamily.Monospace)
    }
}
