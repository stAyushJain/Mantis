package com.mockmaster.app.platform

import kotlinx.browser.window

actual object LocalStorage {
    actual fun get(key: String): String? = window.localStorage.getItem(key)
    actual fun set(key: String, value: String) {
        window.localStorage.setItem(key, value)
    }
    actual fun remove(key: String) {
        window.localStorage.removeItem(key)
    }
}

actual fun defaultApiBaseUrl(): String {
    // window.location.protocol → "http:" or "https:"
    // window.location.hostname → "127.0.0.1" / "localhost" / "192.168.0.5"
    val proto = window.location.protocol.ifBlank { "http:" }
    val host = window.location.hostname.ifBlank { "127.0.0.1" }
    return "$proto//$host:3000"
}
