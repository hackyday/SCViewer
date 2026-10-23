package com.scviewer.fire.web

import android.content.Context
import android.webkit.WebView
import java.io.BufferedReader

/**
 * Loads page_inject.js from assets and runs it via WebView.evaluateJavascript
 * after every page load. The script is idempotent (it short-circuits if it
 * has already been injected), so running it on every SPA navigation is safe.
 */
class PageInjector(private val context: Context) {

    private val injectScript: String by lazy { readAsset("page_inject.js") }
    val videoAgentScript: String by lazy { readAsset("video_agent.js") }

    fun injectInto(webView: WebView) {
        if (injectScript.isBlank()) return
        // Wrap in an IIFE call to keep global scope clean.
        val wrapped = "(function(){\n$injectScript\n})();"
        webView.evaluateJavascript(wrapped, null)
    }

    /** Main-frame fallback for the video agent when
     *  WebViewCompat.addDocumentStartJavaScript is unavailable. */
    fun injectVideoAgent(webView: WebView) {
        if (videoAgentScript.isBlank()) return
        webView.evaluateJavascript("(function(){\n${videoAgentScript}\n})();", null)
    }

    private fun readAsset(name: String): String =
        context.assets.open(name).bufferedReader().use(BufferedReader::readText)
}
