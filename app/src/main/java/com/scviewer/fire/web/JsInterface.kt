package com.scviewer.fire.web

import android.webkit.JavascriptInterface
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * JavaScript bridge exposed to the page (all frames) by the WebView.
 *
 * Methods are public and annotated with @JavascriptInterface because that's
 * what the WebView's JS engine requires on Android M+. They are invoked on
 * the WebView's binder thread — anything that touches UI must hop to the
 * main thread via the callbacks supplied in the constructor.
 *
 * Native -> JS: the video agent (running inside the cross-origin player
 * iframe, unreachable by evaluateJavascript) polls [takeCommand]; requests
 * nobody consumes within their TTL simply expire.
 *
 * Proguard rules keep this class intact for release builds — see app/proguard-rules.pro.
 */
class JsInterface(
    private val onSkipIntro: (Boolean) -> Unit,
    private val onAdBlockHit: (String) -> Unit,
    private val onVideoState: (Boolean) -> Unit,
    private val onPageInfo: (String) -> Unit,
    private val onProgress: (Int, Int) -> Unit
) {

    private data class Cmd(val text: String, val atMs: Long, val ttlMs: Long)

    private val cmdQueue = ConcurrentLinkedQueue<Cmd>()

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

    /** Called by page_inject.js after every (SPA) navigation with the
     *  Inertia page state — name, type, season/episode of the current page. */
    @JavascriptInterface
    fun onPageInfo(json: String) {
        if (json.isNotBlank()) onPageInfo.invoke(json)
    }

    /** Called by the video agent every ~10 s while a film plays (and once on
     *  pause): position and duration in seconds of the active video. */
    @JavascriptInterface
    fun onProgress(positionSec: Int, durationSec: Int) {
        if (durationSec > 0 && positionSec >= 0) onProgress.invoke(positionSec, durationSec)
    }

    /** Called by the video agent (80 ms poll). Returns and clears the next
     *  FRESH command ("toggle-play" / "seek-to:<sec>"), or "" when none. */
    @JavascriptInterface
    fun takeCommand(): String {
        val now = System.currentTimeMillis()
        while (true) {
            val head = cmdQueue.peek() ?: return ""
            if (now - head.atMs > head.ttlMs) {
                cmdQueue.poll()                       // stale — drop, keep looking
                continue
            }
            if (cmdQueue.remove(head)) return head.text
        }
    }

    /** Native side: remote play/pause key — toggle the active video. */
    fun requestPauseToggle() {
        enqueue("toggle-play", TOGGLE_TTL_MS)
    }

    /** Native side: "Riprendi" — jump the active video to [sec]. Longer TTL
     *  than toggle: the player may still be starting when it's requested. */
    fun requestSeekTo(sec: Int) {
        enqueue("seek-to:$sec", SEEK_TTL_MS)
    }

    private fun enqueue(text: String, ttlMs: Long) {
        if (cmdQueue.size > 8) cmdQueue.clear()
        cmdQueue.add(Cmd(text, System.currentTimeMillis(), ttlMs))
    }

    companion object {
        private const val TOGGLE_TTL_MS = 1500L
        private const val SEEK_TTL_MS = 12_000L
    }
}
