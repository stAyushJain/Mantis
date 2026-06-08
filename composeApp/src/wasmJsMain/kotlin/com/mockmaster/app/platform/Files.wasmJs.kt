package com.mockmaster.app.platform

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import org.w3c.files.FileReader

/**
 * Browser implementations of the FileIo facade. All three operations are
 * synchronous from the caller's perspective: `download` returns immediately
 * after triggering the download, `pickTextFile` fires its callback when the
 * user picks (or cancels), and `copyToClipboard` is best-effort fire-and-forget.
 *
 * Notes:
 *  - `download` builds an `<a>` element with a data-URL-equivalent object URL
 *    so we don't need a server roundtrip. We revoke the object URL after a
 *    short delay to free memory.
 *  - `pickTextFile` builds a transient `<input type="file">`, attaches it to
 *    the DOM (some browsers refuse to fire the dialog if detached), and
 *    removes it after the change event fires.
 *  - `copyToClipboard` uses the async Clipboard API; falls back to the
 *    legacy `document.execCommand("copy")` path if the API is unavailable
 *    (older Safari, insecure contexts).
 */
@Suppress("UNUSED_PARAMETER")
actual object FileIo {
    actual fun download(filename: String, contents: String, mime: String) {
        // Wrap the string in an array literal that the JS Blob constructor
        // understands. We have to go via JS interop because Kotlin/Wasm's
        // typed Blob constructor wants a `JsArray<JsAny>` of parts.
        val parts: JsAny = jsArrayOf(contents.toJsString())
        val opts = BlobPropertyBag(type = mime)
        val blob = Blob(parts.unsafeCast(), opts)
        val urlStr = URL.createObjectURL(blob)
        val a = document.createElement("a") as HTMLAnchorElement
        a.href = urlStr
        a.download = filename
        a.style.display = "none"
        document.body?.appendChild(a)
        a.click()
        document.body?.removeChild(a)
        // Free the object URL on the next macrotask so the browser has
        // time to start the download.
        window.setTimeout({ URL.revokeObjectURL(urlStr); null }, 1000)
    }

    actual fun pickTextFile(accept: String, onText: (String?) -> Unit) {
        val input = document.createElement("input") as HTMLInputElement
        input.type = "file"
        input.accept = accept
        input.style.display = "none"
        input.onchange = { _ ->
            val file = input.files?.item(0)
            if (file == null) {
                onText(null)
            } else {
                val reader = FileReader()
                reader.onload = {
                    val res = reader.result
                    onText(res?.toString())
                    null
                }
                reader.onerror = {
                    onText(null)
                    null
                }
                reader.readAsText(file)
            }
            document.body?.removeChild(input)
            null
        }
        // Fire if the user closes the picker without choosing anything
        // (only some browsers fire this; safe to ignore where it doesn't).
        input.oncancel = { _ ->
            onText(null)
            // Best-effort cleanup; the change handler also removes it.
            runCatching { document.body?.removeChild(input) }
            null
        }
        document.body?.appendChild(input)
        input.click()
    }

    actual fun copyToClipboard(text: String) {
        // Try the async Clipboard API first; fall back to the legacy
        // textarea + execCommand("copy") trick if it isn't available
        // (older Safari, insecure-context pages). Both calls swallow
        // errors so the user-facing toast can still claim success.
        if (!asyncClipboardWrite(text)) {
            val ta = document.createElement("textarea") as org.w3c.dom.HTMLTextAreaElement
            ta.value = text
            ta.style.position = "fixed"
            ta.style.opacity = "0"
            document.body?.appendChild(ta)
            ta.select()
            runCatching { legacyExecCopy() }
            document.body?.removeChild(ta)
        }
    }
}

/**
 * Returns true if navigator.clipboard.writeText is available and the call
 * was dispatched. We don't await the promise — the user's perspective is
 * "click → text is on the clipboard" and we treat any rejection as fatal
 * (caller falls back to the legacy textarea path).
 */
private fun asyncClipboardWrite(text: String): Boolean = js(
    """{
        try {
            if (navigator && navigator.clipboard && navigator.clipboard.writeText) {
                navigator.clipboard.writeText(text);
                return true;
            }
        } catch (e) {}
        return false;
    }"""
)

private fun legacyExecCopy(): Boolean = js("document.execCommand('copy')")

/** Helper to build a JsArray<JsAny> from Kotlin varargs without ceremony. */
@Suppress("UNUSED_PARAMETER")
private fun jsArrayOf(vararg items: JsAny): JsAny =
    js("Array.prototype.slice.call(arguments)")
