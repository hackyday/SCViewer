package com.scviewer.fire.ui

import android.view.View
import android.widget.Button
import android.widget.FrameLayout

/**
 * Controls the "Salta intro" button shown when the injected JS reports
 * a video element with currentTime < 30s.
 *
 * The button is initially hidden. When the JS bridge calls back with
 * available=true, we make it visible and request focus so the Fire TV
 * remote's DPAD lands on it. Tapping the button calls the JS-side
 * `AndroidJs.skipIntro()` which we exposed in page_inject.js — we route
 * that call through the WebView (Button has no evaluateJavascript).
 */
class SkipIntroController(
    private val container: FrameLayout,
    private val button: Button,
    private val onSkip: () -> Unit
) {
    init {
        container.visibility = View.GONE
        button.setOnClickListener {
            // Il video vive nell'iframe del player: il salto passa per il
            // bridge (comando 'skip-intro' consumato dal video agent).
            onSkip()
            hide()
        }
    }

    fun setAvailable(available: Boolean) {
        if (available) show() else hide()
    }

    /** True se il chip "Salta intro" è attualmente visibile. */
    val isAvailable: Boolean get() = container.visibility == View.VISIBLE

    private fun show() {
        if (container.visibility != View.VISIBLE) {
            container.visibility = View.VISIBLE
            button.requestFocus()
        }
    }

    private fun hide() {
        if (container.visibility == View.VISIBLE) {
            container.visibility = View.GONE
            // NOTE: deliberately NOT calling webView.requestFocus() — on SPA
            // sites it makes Chromium scroll the focused element (a header
            // link) into view, jumping the page back to the top. The virtual
            // cursor doesn't depend on WebView focus.
        }
    }
}
