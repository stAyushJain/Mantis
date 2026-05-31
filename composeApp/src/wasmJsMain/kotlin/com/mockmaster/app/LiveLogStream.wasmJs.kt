package com.mockmaster.app

import com.mockmaster.app.api.MockMasterApi
import com.mockmaster.app.state.AppState
import com.mockmaster.shared.InterceptedCall
import kotlinx.serialization.json.Json
import org.w3c.dom.events.Event

/**
 * Wires the BE's /logs/stream Server-Sent-Events endpoint into the app state.
 * Each message is parsed as a JSON [InterceptedCall] and prepended to the log
 * list. The browser will auto-reconnect if the BE drops.
 */
@JsFun(
    """(url, onMessage, onError) => {
        const es = new EventSource(url);
        es.onmessage = (e) => onMessage(e.data);
        es.onerror = (e) => onError();
        return es;
    }"""
)
external fun openEventSource(
    url: String,
    onMessage: (String) -> Unit,
    onError: () -> Unit,
): JsAny

private val json = Json { ignoreUnknownKeys = true }

actual fun installLiveLogStream(state: AppState) {
    try {
        openEventSource(
            url = MockMasterApi.streamLogsUrl(),
            onMessage = { raw ->
                try {
                    val call = json.decodeFromString(InterceptedCall.serializer(), raw)
                    state.appendLog(call)
                } catch (_: Throwable) {
                }
            },
            onError = { /* let the browser reconnect */ },
        )
    } catch (_: Throwable) {
        // best effort
    }
}
