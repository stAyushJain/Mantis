package com.mockmaster.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mockmaster.app.MockColors
import com.mockmaster.app.state.AppState
import com.mockmaster.shared.UpsertRuleReq
import com.mockmaster.shared.MockRule
import kotlinx.coroutines.launch

private val HTTP_METHODS = listOf("GET", "POST", "PUT", "DELETE", "PATCH")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleEditorDialog(
    folderId: String,
    rule: MockRule?,
    state: AppState,
    onDismiss: () -> Unit,
) {
    var path by remember { mutableStateOf(rule?.path ?: "") }
    var method by remember { mutableStateOf(rule?.method ?: "GET") }
    var statusText by remember { mutableStateOf((rule?.statusCode ?: 200).toString()) }
    var body by remember { mutableStateOf(rule?.body ?: "{\n  \n}") }
    var enabled by remember { mutableStateOf(rule?.isEnabled ?: true) }
    var jsonError by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var methodMenuOpen by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun tryFormat() {
        val pretty = formatJsonOrNull(body)
        if (pretty == null) {
            jsonError = "Body is not valid JSON"
        } else {
            body = pretty
            jsonError = null
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
                .widthIn(min = 600.dp, max = 880.dp)
                .padding(24.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    if (rule == null) "Add endpoint" else "Edit endpoint",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    "Adding a duplicate (same method + path) in this folder will replace the previous one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MockColors.textSecondary,
                )
                Spacer(Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Method
                    ExposedDropdownMenuBox(
                        expanded = methodMenuOpen,
                        onExpandedChange = { methodMenuOpen = it },
                        modifier = Modifier.width(140.dp),
                    ) {
                        OutlinedTextField(
                            value = method,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Method") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = methodMenuOpen) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                        )
                        DropdownMenu(
                            expanded = methodMenuOpen,
                            onDismissRequest = { methodMenuOpen = false },
                        ) {
                            HTTP_METHODS.forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(m) },
                                    onClick = { method = m; methodMenuOpen = false },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = path,
                        onValueChange = { path = it },
                        label = { Text("URL path") },
                        placeholder = { Text("/v1/api/driver") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = statusText,
                        onValueChange = { statusText = it.filter { ch -> ch.isDigit() }.take(3) },
                        label = { Text("Status") },
                        singleLine = true,
                        modifier = Modifier.width(110.dp),
                    )
                }

                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Response body (JSON)", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    jsonError?.let {
                        Text(it, color = MockColors.danger, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(8.dp))
                    }
                    OutlinedButton(onClick = { tryFormat() }) {
                        Icon(Icons.Filled.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Format JSON")
                    }
                }
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it; jsonError = null },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    placeholder = { Text("{ \"key\": \"value\" }") },
                )

                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                    Spacer(Modifier.width(8.dp))
                    Text(if (enabled) "Enabled" else "Disabled")
                    Spacer(Modifier.weight(1f))
                    saveError?.let {
                        Text(it, color = MockColors.danger, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(8.dp))
                    }
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = !busy && path.trim().isNotEmpty() && (statusText.toIntOrNull() ?: 0) in 100..599,
                        onClick = {
                            val finalBody = formatJsonOrNull(body) ?: run {
                                jsonError = "Body is not valid JSON"
                                return@Button
                            }
                            busy = true; saveError = null
                            scope.launch {
                                val msg = state.upsertRule(
                                    folderId,
                                    UpsertRuleReq(
                                        id = rule?.id,
                                        path = path.trim(),
                                        method = method,
                                        statusCode = statusText.toIntOrNull() ?: 200,
                                        body = finalBody,
                                        isEnabled = enabled,
                                    ),
                                ).await()
                                busy = false
                                if (msg == null) onDismiss() else saveError = msg
                            }
                        },
                    ) { Text(if (rule == null) "Add endpoint" else "Save changes") }
                }
            }
        }
    }
}
