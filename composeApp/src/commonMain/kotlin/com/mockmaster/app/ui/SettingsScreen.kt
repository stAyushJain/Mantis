package com.mockmaster.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mockmaster.app.MockColors
import com.mockmaster.app.api.ApiConfig
import com.mockmaster.app.state.AppState
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(state: AppState) {
    var apiUrl by remember { mutableStateOf(ApiConfig.baseUrl) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column {
                Text("Signed in", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                InfoLine("User", state.currentUser ?: "—")
                Text(
                    "Each user has an isolated workspace at workspace_data/<user>/. Sign out to switch to a different one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MockColors.textSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { state.signOut() }) { Text("Sign out") }
            }
        }

        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column {
                Text("Backend connection", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Mantis's backend exposes its API on this URL. Change it if you're running the BE on another machine. The URL is remembered between visits.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MockColors.textSecondary,
                )
                Spacer(Modifier.height(12.dp))
                Row {
                    OutlinedTextField(
                        value = apiUrl,
                        onValueChange = { apiUrl = it },
                        label = { Text("Backend URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = {
                        ApiConfig.baseUrl = apiUrl.trim()
                        state.showToast("Backend URL saved. Reloading...")
                        scope.launch { state.bootstrap() }
                    }) { Text("Apply") }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column {
                Text("Server info", style = MaterialTheme.typography.titleLarge)
                state.serverInfo?.let {
                    Spacer(Modifier.height(6.dp))
                    InfoLine("Status", it.status)
                    InfoLine("Proxy", "${it.proxyHost}:${it.proxyPort}")
                    InfoLine("API", "127.0.0.1:${it.apiPort}")
                    InfoLine("Local IP", it.localIp ?: "unavailable")
                    InfoLine("Version", it.version)
                } ?: Text("Backend not reachable.", color = MockColors.danger)
            }
        }

        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column {
                Text("Workspace", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                val totalFolders = countAllFolders(state.workspace.folders)
                InfoLine("Folders", totalFolders.toString())
                val totalRules = state.workspace.rulesMap.values.sumOf { it.size }
                InfoLine("Endpoints", totalRules.toString())
                InfoLine("Flows", state.workspace.flows.size.toString())
                InfoLine("Active flow", state.activeFlow?.name ?: "—")
                InfoLine("Cached log entries", state.logs.size.toString())
            }
        }
    }
}

private fun countAllFolders(nodes: List<com.mockmaster.shared.FolderNode>): Int {
    var total = 0
    for (n in nodes) {
        total += 1
        total += countAllFolders(n.subFolders)
    }
    return total
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(label, color = MockColors.textSecondary, modifier = Modifier.width(140.dp))
        Text(value)
    }
}
