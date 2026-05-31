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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mockmaster.app.MockColors
import com.mockmaster.app.api.MockMasterApi
import com.mockmaster.app.state.AppState

enum class Tab(val title: String, val icon: ImageVector) {
    Folders("Endpoints", Icons.Filled.Folder),
    Flows("Flows", Icons.Filled.Timeline),
    Logs("Intercepted Calls", Icons.Filled.Bolt),
    Docs("How to Use", Icons.Filled.Description),
    Settings("Settings", Icons.Filled.Settings),
}

@Composable
fun AppScaffold(state: AppState) {
    var tab by remember { mutableStateOf(Tab.Folders) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar(state)
        Row(Modifier.fillMaxSize()) {
            SideNav(tab, onSelect = { tab = it })
            Box(Modifier.fillMaxSize().padding(20.dp)) {
                when (tab) {
                    Tab.Folders -> FoldersScreen(state)
                    Tab.Flows -> FlowsScreen(state)
                    Tab.Logs -> LogsScreen(state)
                    Tab.Docs -> DocsScreen(state)
                    Tab.Settings -> SettingsScreen(state)
                }
            }
        }
    }

    state.toast?.let { ToastOverlay(it) }
}

@Composable
private fun TopBar(state: AppState) {
    val info = state.serverInfo
    val running = info?.status == "running"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MockColors.surface)
            .border(width = 1.dp, color = MockColors.border, shape = RoundedCornerShape(0.dp))
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Logo / brand
        Box(
            Modifier
                .size(32.dp)
                .background(MockColors.accent, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("M", color = Color.White, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text("Mantis", style = MaterialTheme.typography.titleLarge)
            Text(
                "HTTPS mocking for mobile and web",
                style = MaterialTheme.typography.bodySmall,
                color = MockColors.textSecondary,
            )
        }

        Spacer(Modifier.width(32.dp))

        if (info != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(running, if (running) "Proxy running" else "Proxy stopped")
                Spacer(Modifier.width(16.dp))
                ServerInfoChip("Proxy", info.localIp?.let { "$it:${info.proxyPort}" } ?: "127.0.0.1:${info.proxyPort}")
                Spacer(Modifier.width(8.dp))
                ServerInfoChip("API", "127.0.0.1:${info.apiPort}")
                if (state.isFlowActive) {
                    Spacer(Modifier.width(8.dp))
                    FlowActiveChip(state.activeFlow?.name ?: "Active flow")
                }
            }
        }

        Spacer(Modifier.weight(1f))

        state.currentUser?.let { user ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(MockColors.surfaceMuted, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Icon(Icons.Filled.Person, contentDescription = null, tint = MockColors.textSecondary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(user, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { state.signOut() }) { Text("Sign out") }
            Spacer(Modifier.width(8.dp))
        }

        if (running) {
            OutlinedButton(onClick = { state.disconnectProxy() }) {
                Icon(Icons.Filled.WifiTethering, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Disconnect")
            }
        } else {
            Button(onClick = { state.connectProxy() }) {
                Icon(Icons.Filled.WifiTethering, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Connect")
            }
        }

        Spacer(Modifier.width(8.dp))
        OutlinedButton(
            onClick = {
                kotlinx.browser.window.open(MockMasterApi.certUrl(), "_self")
            },
        ) { Text("Download cert") }
    }
}

@Composable
private fun ServerInfoChip(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(MockColors.surfaceMuted, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MockColors.textSecondary)
        Spacer(Modifier.width(6.dp))
        Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun FlowActiveChip(flowName: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(MockColors.warning.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Box(Modifier.size(6.dp).background(MockColors.warning, RoundedCornerShape(50)))
        Spacer(Modifier.width(6.dp))
        Text(
            "Flow: $flowName",
            style = MaterialTheme.typography.labelMedium,
            color = MockColors.warning,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SideNav(current: Tab, onSelect: (Tab) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(220.dp)
            .background(MockColors.surface)
            .border(width = 1.dp, color = MockColors.border, shape = RoundedCornerShape(0.dp))
            .padding(vertical = 12.dp),
    ) {
        Tab.entries.forEach { t ->
            val active = current == t
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .background(
                        if (active) MockColors.accent.copy(alpha = 0.10f) else Color.Transparent,
                        RoundedCornerShape(6.dp),
                    )
                    .clickable { onSelect(t) }
                    .padding(horizontal = 10.dp, vertical = 10.dp),
            ) {
                Icon(
                    t.icon,
                    contentDescription = null,
                    tint = if (active) MockColors.accent else MockColors.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    t.title,
                    color = if (active) MockColors.accent else MockColors.textPrimary,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun ToastOverlay(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        androidx.compose.material3.Surface(
            color = MockColors.textPrimary,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                message,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}
