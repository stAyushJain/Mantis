package com.mockmaster.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mockmaster.app.MockColors
import com.mockmaster.app.api.ApiConfig
import com.mockmaster.app.state.AppState
import kotlinx.coroutines.launch

/**
 * Username gate. This is NOT real authentication — anyone who knows your
 * username can use your workspace. It exists so each developer on the team
 * has their own folder/flow space without colliding with everyone else.
 *
 * UX: single field, hit Enter or click "Open workspace". If the username is
 * new the BE creates a workspace; if it's known, the BE reopens it.
 */
@Composable
fun LoginScreen(state: AppState) {
    var name by remember { mutableStateOf("") }
    var apiUrl by remember { mutableStateOf(ApiConfig.baseUrl) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun submit() {
        val n = name.trim()
        if (n.isEmpty()) {
            error = "Please enter a username"
            return
        }
        if (apiUrl.trim() != ApiConfig.baseUrl) {
            ApiConfig.baseUrl = apiUrl.trim()
        }
        error = null
        busy = true
        scope.launch {
            val msg = state.signIn(n)
            busy = false
            error = msg
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = MockColors.surface,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(min = 400.dp, max = 480.dp).padding(24.dp),
        ) {
            Column(Modifier.padding(28.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .background(MockColors.accent, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("M", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Mantis", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "HTTPS mocking",
                            style = MaterialTheme.typography.bodySmall,
                            color = MockColors.textSecondary,
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    "Enter your name to open your personal workspace.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MockColors.textSecondary,
                )
                Text(
                    "If it's new, we'll create one for you. If it already exists, we'll open it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MockColors.textSecondary,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("Username") },
                    placeholder = { Text("e.g. ayush") },
                    singleLine = true,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = apiUrl,
                    onValueChange = { apiUrl = it },
                    label = { Text("Backend URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MockColors.danger, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { submit() },
                    enabled = !busy && name.trim().isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Open workspace")
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Note: this is a workaround, not real authentication. Anyone using the same backend who picks your username can access your workspace.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MockColors.textSecondary,
                )
            }
        }
    }
}
