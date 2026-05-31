@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.mockmaster.app

import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document

fun main() {
    ComposeViewport(document.body!!) {
        App()
    }
}
