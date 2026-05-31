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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.mockmaster.app.state.AppState
import com.mockmaster.shared.InterceptedCall

@Composable
fun LogsScreen(state: AppState) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<InterceptedCall?>(null) }

    val filtered = state.logs.filter {
        query.isBlank() ||
            it.path.contains(query, ignoreCase = true) ||
            it.url.contains(query, ignoreCase = true) ||
            it.method.contains(query, ignoreCase = true)
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
                    "${filtered.size} entries",
                    color = MockColors.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.width(12.dp))
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
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (filtered.isEmpty()) {
                    Text(
                        if (state.logs.isEmpty())
                            "Waiting for traffic. Set your device proxy to ${state.serverInfo?.localIp ?: "<your-ip>"}:${state.serverInfo?.proxyPort ?: 8080} and trigger a request."
                        else "No matches.",
                        color = MockColors.textSecondary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                filtered.forEach { call ->
                    LogRow(call, selected?.id == call.id) { selected = call }
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (selected == null) {
                Card(Modifier.fillMaxSize()) {
                    Column(
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxSize(),
                    ) { Text("Select an entry to view request/response.", color = MockColors.textSecondary) }
                }
            } else {
                LogDetail(selected!!)
            }
        }
    }
}

@Composable
private fun LogRow(call: InterceptedCall, selected: Boolean, onClick: () -> Unit) {
    val borderColor = when {
        call.matched && call.matchSource == "flow" -> MockColors.warning
        call.matched -> MockColors.success
        else -> MockColors.border
    }
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
            .padding(8.dp),
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
private fun LogDetail(call: InterceptedCall) {
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
            if (call.matched) {
                Spacer(Modifier.height(12.dp))
                DetailSection("Mocked response") {
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
