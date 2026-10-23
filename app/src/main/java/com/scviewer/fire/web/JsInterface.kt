package com.scviewer.fire.web

import android.webkit.JavascriptInterface

/**
 * JavaScript bridge exposed to the page (all frames) by the WebView.
 *
 * Methods are public and annotated with @JavascriptInterface because that's
 * what the WebView's JS engine requires on Android M+. They are invoked on
 * the WebView's binder thread — anything that touches UI must hop to the
 * main thread via the callbacks supplied in the constructor.
 *
 * Native -> JS is implemented with a tiny polled command slot: the video
 * agent (running inside the cross-origin player iframe, unreachable by
 * evaluateJavascript) polls [takeCommand]; a request nobody consumes within
 * [CMD_TTL_MS] simply expires.
 *
 * Proguard rules keep this class intact for release builds — see app/proguard-rules.pro.
 */
class JsInterface(
    private val onSkipIntro: (Boolean) -> Unit,
    private val onAdBlockHit: (String) -> Unit,
    private val onVideoState: (Boolean) -> Unit
) {

    @Volatile private var pendingCmd: String? = null
    @Volatile private var pendingCmdAt = 0L

    /** Called by page_inject.js when a video element with currentTime<30s is found. */
    @JavascriptInterface
    fun onSkipIntroAvailable(available: Boolean) {
        onSkipIntro.invoke(available)
    }

    /** Called by page_inject.js whenever it observes an ad-like DOM node it
     *  couldn't fully hide — so the AdBlocker can learn the host. */
    @JavascriptInterface
    fun reportAdHost(host: String) {
        if (host.isNotBlank()) onAdBlockHit.invoke(host)
    }

    /** Called by the video agent in any frame: a long, large video is (or
     *  stopped) being watched — drives cursor auto-hide. */
    @JavascriptInterface
    fun onVideoState(active: Boolean) {
        onVideoState.invoke(active)
    }

    /** Called by the video agent (80 ms poll). Returns and clears the pending
     *  command ("toggle-play"), or "" when there is nothing fresh. */
    @JavascriptInterface
    fun takeCommand(): String {
        val cmd = pendingCmd ?: return ""
        if (System.currentTimeMillis() - pendingCmdAt > CMD_TTL_MS) {
            pendingCmd = null
            return ""
        }
        pendingCmd = null
        return cmd
    }

    /** Native side: remote play/pause key pressed — toggle the active video. */
    fun requestPauseToggle() {
        pendingCmd = CMD_TOGGLE_PLAY
        pendingCmdAt = System.currentTimeMillis()
    }

    companion object {
        private const val CMD_TOGGLE_PLAY = "toggle-play"
        /** Commands older than this are dropped (no frame consumed them). */
        private const val CMD_TTL_MS = 1500L
    }
}
