package com.mockmaster.app.platform

/**
 * Tiny cross-platform key/value persistence facade. On wasmJs this maps onto
 * `window.localStorage`; other targets can supply their own backing if we
 * ever add native UIs.
 */
expect object LocalStorage {
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun remove(key: String)
}

/**
 * Best guess at where the BE lives if the user hasn't configured one. On
 * wasmJs we look at the browser's current origin and swap the UI port for the
 * BE port — so when the team runs Mantis in Docker (UI on :5173, API on :3000)
 * everything Just Works regardless of whether they hit localhost or a LAN IP.
 */
expect fun defaultApiBaseUrl(): String
