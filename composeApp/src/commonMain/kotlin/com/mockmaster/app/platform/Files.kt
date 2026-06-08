package com.mockmaster.app.platform

/**
 * Platform-specific helpers for file IO and clipboard access. On wasmJs
 * these delegate to the browser (Blob/URL.createObjectURL for downloads,
 * a hidden <input type="file"> for opens, and Clipboard API for copy).
 * Other targets (e.g. a future Desktop build) would provide their own
 * actual implementations.
 */
expect object FileIo {
    /**
     * Trigger a browser download with the given filename + textual contents
     * (UTF-8). Returns immediately; the actual download happens on the next
     * event-loop tick.
     */
    fun download(filename: String, contents: String, mime: String = "application/json")

    /**
     * Open the system file picker, restricted by accept hint (e.g.
     * "application/json,.json"). Calls [onText] with the file's UTF-8
     * contents on success, or with null on cancel/error.
     */
    fun pickTextFile(accept: String, onText: (String?) -> Unit)

    /** Copy text to the OS clipboard. Silent on failure. */
    fun copyToClipboard(text: String)
}
