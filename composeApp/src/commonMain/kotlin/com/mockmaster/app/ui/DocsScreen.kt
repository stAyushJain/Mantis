package com.mockmaster.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mockmaster.app.MockColors
import com.mockmaster.app.api.MockMasterApi
import com.mockmaster.app.state.AppState

@Composable
fun DocsScreen(state: AppState) {
    val info = state.serverInfo
    val proxyHost = info?.localIp ?: "<your-mac-ip>"
    val proxyPort = info?.proxyPort ?: 8080

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column {
                Text("Mantis — quick start", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Mantis is an HTTPS-aware mock proxy. Point your phone or browser at it, then mock individual endpoints from folders or run an entire flow of mocks in sequence.",
                    color = MockColors.textSecondary,
                )
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    InfoChip("Proxy", "$proxyHost:$proxyPort")
                    InfoChip("API", "127.0.0.1:${info?.apiPort ?: 3000}")
                    InfoChip("Status", info?.status ?: "unknown")
                    Button(
                        onClick = { kotlinx.browser.window.open(MockMasterApi.certUrl(), "_self") },
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Download root CA")
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column {
                Text("What can it do?", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Bullet("Open this dashboard, click \"Connect\" — the MITM proxy starts on your machine.")
                Bullet("Add folders / sub-folders to organise mocks. Toggling a folder cascades to everything inside.")
                Bullet("Add endpoints (URL path + method + status + JSON body). Adding the same path+method again replaces the previous one.")
                Bullet("Run flows for stateful sequences (e.g. an entire booking journey). Steps are consumed in order, then the last one sticks.")
                Bullet("Download the root CA and install it on your phone, laptop, or simulator to intercept HTTPS traffic.")
                Bullet("Watch every request in real time on the \"Intercepted Calls\" tab — including passthroughs.")
            }
        }

        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column {
                Text("Set up your phone (Android)", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Bullet("Connect the phone to the SAME Wi-Fi as this machine.")
                Bullet("Tap and hold the Wi-Fi network → Modify network → Advanced → Proxy → Manual.")
                Bullet("Hostname: $proxyHost · Port: $proxyPort. Save.")
                Bullet("Open Chrome on the phone, go to chrome://settings (or the cert app) and install the downloaded mockmaster-ca.crt as a User CA.")
                Bullet("On Android 7+, app traffic only trusts user CAs if the app explicitly opts in. For non-debuggable apps, install on a rooted device or as a system CA. Debug builds typically work out of the box.")
            }
        }

        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column {
                Text("Set up your phone (iOS)", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Bullet("Same Wi-Fi as this machine.")
                Bullet("Settings → Wi-Fi → tap the (i) → Configure Proxy → Manual.")
                Bullet("Server: $proxyHost · Port: $proxyPort. Save.")
                Bullet("Open the cert URL in Safari to install the profile, then Settings → General → VPN & Device Management → install the Mantis profile.")
                Bullet("Settings → General → About → Certificate Trust Settings → enable full trust for Mantis.")
            }
        }

        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column {
                Text("Set up your laptop / browser", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Bullet("System proxy or browser proxy → 127.0.0.1:$proxyPort.")
                Bullet("Add the downloaded mockmaster-ca.crt as a trusted root in your OS keychain.")
                Bullet("On macOS: open Keychain Access → System → drag the .crt in → set to \"Always Trust\".")
            }
        }

        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column {
                Text("Folders vs. flows — when to use what", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Bullet("Use FOLDERS for stable, always-on mocks. Each (method+path) inside a folder gives one response. Multiple folders can stay enabled at once and the most recently saved rule wins.")
                Bullet("Use FLOWS for stateful sequences where the SAME endpoint should return DIFFERENT bodies on each call (booking → pickup → complete → summary). While a flow runs, folder mocks are paused so they don't interfere.")
                Bullet("Inside a flow, multiple steps may share the same path+method. They're consumed one-per-call, in order. After all are consumed, the last step is replayed.")
            }
        }

        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column {
                Text("Troubleshooting", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Bullet("\"Cert error\" on the phone → CA not installed or not trusted. Re-download and follow the trust steps.")
                Bullet("\"No connection\" → make sure phone and laptop are on the same Wi-Fi, and the proxy status above shows running.")
                Bullet("Mock not hitting → check folder enable toggles and ancestor folders, and confirm METHOD matches (GET vs POST).")
                Bullet("Flow steps not consumed → flow must be started; folder rules are paused while a flow runs.")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun InfoChip(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(MockColors.surfaceMuted, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(label, color = MockColors.textSecondary, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.width(6.dp))
        Text(value, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun Bullet(text: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Text("•  ", color = MockColors.accent, fontWeight = FontWeight.Bold)
        Text(text, color = MockColors.textPrimary, modifier = Modifier.weight(1f))
    }
}
