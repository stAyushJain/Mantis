@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.mockmaster.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mockmaster.app.MockColors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private val prettyJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    ignoreUnknownKeys = true
}

/**
 * Try to format a JSON string. Returns the prettified version, or null if the
 * input is not valid JSON. Empty input returns "{}".
 */
fun formatJsonOrNull(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return "{}"
    return try {
        val element = Json.parseToJsonElement(trimmed)
        prettyJson.encodeToString(JsonElement.serializer(), element)
    } catch (_: Throwable) {
        null
    }
}

fun isValidJson(input: String): Boolean = formatJsonOrNull(input) != null

@Composable
fun MethodPill(method: String) {
    val (bg, fg) = when (method.uppercase()) {
        "GET" -> Color(0xFFDCFCE7) to Color(0xFF166534)
        "POST" -> Color(0xFFDBEAFE) to Color(0xFF1E40AF)
        "PUT" -> Color(0xFFFEF3C7) to Color(0xFF92400E)
        "DELETE" -> Color(0xFFFFE4E6) to Color(0xFF991B1B)
        "PATCH" -> Color(0xFFEDE9FE) to Color(0xFF5B21B6)
        else -> Color(0xFFE5E7EB) to Color(0xFF374151)
    }
    Surface(color = bg, shape = RoundedCornerShape(4.dp)) {
        Text(
            method.uppercase(),
            color = fg,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
fun StatusPill(status: Int) {
    val color = when {
        status in 200..299 -> MockColors.success
        status in 300..399 -> MockColors.accent
        status in 400..499 -> MockColors.warning
        status >= 500 -> MockColors.danger
        else -> MockColors.textSecondary
    }
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(4.dp)) {
        Text(
            status.toString(),
            color = color,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
fun StatusDot(running: Boolean, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(if (running) MockColors.success else MockColors.danger, RoundedCornerShape(50)),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun Card(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .border(1.dp, MockColors.border, RoundedCornerShape(8.dp)),
        color = MockColors.surface,
        shape = RoundedCornerShape(8.dp),
    ) {
        Box(Modifier.padding(padding)) { content() }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
        color = MockColors.textPrimary,
        modifier = modifier,
    )
}
