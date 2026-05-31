package com.mockmaster.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mockmaster.app.api.ApiConfig
import com.mockmaster.app.api.MockMasterApi
import com.mockmaster.app.state.AppState
import com.mockmaster.app.ui.AppScaffold
import com.mockmaster.app.ui.LoginScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Top-level Compose entry. Gates the main UI behind a username (pseudo-auth);
 * once a user is signed in, hosts the [AppState] and a polling loop that keeps
 * server status fresh, plus a live SSE log stream.
 */
@Composable
fun App() {
    MockMasterTheme {
        val scope = rememberCoroutineScope()
        val state = remember { AppState(scope) }

        // If a username is already remembered, validate it against the BE
        // before trusting it. If the BE doesn't know about that workspace
        // (different machine, wiped data, etc.), drop back to the login
        // screen instead of silently auto-creating it.
        LaunchedEffect(state.currentUser) {
            val u = state.currentUser
            if (u != null) {
                val valid = runCatching {
                    com.mockmaster.app.api.MockMasterApi.userExists(u).exists
                }.getOrDefault(false)
                if (!valid) {
                    state.signOut()
                    return@LaunchedEffect
                }
                runCatching { MockMasterApi.setActiveUser(u) }
                state.bootstrap()
                installLiveLogStream(state)
            }
        }

        // Lightweight polling: keeps server status fresh.
        LaunchedEffect(state.currentUser) {
            if (state.currentUser != null) {
                while (true) {
                    delay(4000)
                    state.refreshServerInfo()
                }
            }
        }

        when {
            state.currentUser == null -> LoginScreen(state)
            state.loading -> LoadingShell()
            else -> AppScaffold(state)
        }

        state.lastError?.let { msg ->
            Box(
                Modifier.fillMaxSize().padding(16.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                androidx.compose.material3.Surface(
                    color = MockColors.danger.copy(alpha = 0.95f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                ) {
                    Text(
                        msg,
                        color = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingShell() {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                "Connecting to Mantis backend...",
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

/** Platform-specific live log stream installer (wasmJs uses EventSource). */
expect fun installLiveLogStream(state: AppState)
