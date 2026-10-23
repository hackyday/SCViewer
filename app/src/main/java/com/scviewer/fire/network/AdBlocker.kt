package com.scviewer.fire.network

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Hosts-style ad blocker.
 *
 * Source of truth: bundled asset `adblock_hosts.txt`. The file follows the
 * standard hosts syntax — one entry per line, leading IP optional, anything
 * after `#` is a comment:
 *
 *   0.0.0.0   ads.example.com
 *   # comment
 *   tracker.io
 *
 * At first launch the asset is copied into internal storage; from then on
 * the app always reads from disk so the list can be refreshed at runtime
 * (e.g. by pulling an updated list from a GitHub gist).
 *
 * Blocking is enforced via WebViewClient.shouldInterceptRequest: any
 * sub-resource whose host matches is returned an empty 403 response, which
 * causes the WebView to drop it on the floor — no iframe, no script, no
 * tracking pixel.
 *
 * The list is intentionally coarse (network block + anti-redirect). For
 * cosmetic hiding, see [com.scviewer.fire.web.SCWebViewClient] which
 * injects a small element-removal script after page load.
 */
class AdBlocker private constructor(context: Context) {

    /** Map host -> true for O(1) lookup. */
    private val blockedHosts = ConcurrentHashMap<String, Boolean>()

    /** Sorted suffix list, used for suffix matching (e.g. *.doubleclick.net). */
    private val blockedSuffixes = mutableListOf<String>()

    private val hostsFile: File = File(context.filesDir, "adblock_hosts.txt")

    init {
        if (!hostsFile.exists()) {
            copyAssetToDisk(context)
        }
        loadFromDisk()
    }

    /**
     * Returns true if the request should be blocked at the network layer.
     * Called on the WebResourceRequest thread — must be fast (hash lookup).
     */
    fun shouldBlock(request: WebResourceRequest): Boolean {
        val url = request.url ?: return false
        val host = url.host?.lowercase() ?: return false
        if (blockedHosts.containsKey(host)) return true
        // Suffix match — covers subdomains of blocked domains.
        synchronized(blockedSuffixes) {
            for (suffix in blockedSuffixes) {
                if (host.endsWith(suffix, ignoreCase = true)) return true
            }
        }
        return false
    }

    /**
     * Returns the canned "empty 403" response handed back to the WebView
     * when a request is blocked. The body is empty so the WebView cannot
     * render anything from the blocked host.
     */
    fun blockedResponse(): WebResourceResponse {
        val empty = ByteArrayInputStream(ByteArray(0))
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            403,
            "Blocked by SC Viewer",
            mapOf("X-SCViewer-AdBlock" to "1"),
            empty
        )
    }

    /**
     * Reload the list from disk. Safe to call from a background thread.
     */
    fun reload() {
        loadFromDisk()
    }

    /**
     * Append a runtime-learned host (e.g. detected via an anti-redirect
     * rule firing on a domain we hadn't seen yet).
     */
    fun addRuntime(host: String) {
        val h = host.lowercase().trim()
        if (h.isEmpty()) return
        if (!blockedHosts.containsKey(h)) {
            blockedHosts[h] = true
            // Persist by appending to the file.
            runCatching { hostsFile.appendText("\n0.0.0.0 $h") }
        }
    }

    // -- loading --------------------------------------------------------------

    private fun loadFromDisk() {
        if (!hostsFile.exists()) return
        blockedHosts.clear()
        synchronized(blockedSuffixes) { blockedSuffixes.clear() }

        hostsFile.readLines().forEach { rawLine ->
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach

            // Drop leading IP if present (e.g. "0.0.0.0 ads.example.com")
            val host = if (line.startsWith("0.0.0.0") || line.startsWith("127.0.0.1")) {
                line.substringAfter(' ', "").trim()
            } else {
                line
            }
            if (host.isEmpty()) return@forEach

            if (host.startsWith("*.")) {
                synchronized(blockedSuffixes) { blockedSuffixes.add(host.removePrefix("*.")) }
            } else {
                blockedHosts[host.lowercase()] = true
            }
        }
    }

    private fun copyAssetToDisk(context: Context) {
        runCatching {
            val text = context.assets.open("adblock_hosts.txt").bufferedReader().use { it.readText() }
            hostsFile.parentFile?.mkdirs()
            hostsFile.writeText(text)
        }
    }

    companion object {
        @Volatile private var INSTANCE: AdBlocker? = null

        fun get(context: Context): AdBlocker =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AdBlocker(context.applicationContext).also { INSTANCE = it }
            }
    }
}
