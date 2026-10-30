package com.scviewer.fire.web

import android.content.Context
import android.net.http.SslError
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.scviewer.fire.data.DomainRepository
import com.scviewer.fire.network.AdBlocker
import com.scviewer.fire.network.HlsRewriter

/**
 * WebViewClient tuned for streamingcommunity.
 *
 * Key behaviours:
 *  - Network ad block via [AdBlocker.shouldBlock] -> returns canned 403
 *    so the WebView drops the request instead of loading the iframe/script.
 *  - Anti-redirect: any non-main request that smells like a popunder
 *    (ends in .html, has ?pop=, /pop/, /redirect/, etc.) is also blocked.
 *  - Desktop Chrome User-Agent override: the Fire TV would otherwise report
 *    its true UA ("AFT... Build/...; tv; firetv") which streamingcommunity
 *    uses to detect TV clients and gate the catalogue. We pretend to be a
 *    desktop Chrome on Windows so the site serves the full catalogue and
 *    the standard web player (which works perfectly in WebView).
 *  - JS injection on every page load via [PageInjector].
 *
 *  Note on the "report as a computer" requirement: in addition to UA we also
 *  strip the `X-Requested-With: com.scviewer.fire` header that Android
 *  WebView injects by default, since some sites detect that to fingerprint
 *  in-app browsers.
 */
class SCWebViewClient(
    private val context: Context,
    private val listener: PageListener
) : WebViewClient() {

    private val adBlocker = AdBlocker.get(context)
    private val injector = PageInjector(context)

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        if (request == null) return null
        if (adBlocker.shouldBlock(request)) {
            return adBlocker.blockedResponse()
        }

        // Forza 1080p: la master playlist del player vixcloud viene servita
        // con la variante FHD sbloccata (h=1) e le varianti inferiori
        // rimosse, così il player non può scendere sotto il massimo
        // disponibile (<= 1080p). Media playlist e segmenti passano invariati.
        HlsRewriter.intercept(request)?.let { return it }

        val url = request.url
        val path = (url.path ?: "").lowercase()
        val query = (url.query ?: "").lowercase()

        // Anti-redirect: catch the common popunder / popup patterns even when
        // the host itself isn't on the blocklist yet.
        val isLikelyPopunder = path.contains("/pop") ||
                path.contains("/redirect") ||
                path.contains("/pu/") ||
                path.contains("/popunder") ||
                query.contains("pop=") ||
                query.contains("popunder=") ||
                query.contains("redirect=")
        if (isLikelyPopunder && !request.isForMainFrame) {
            return adBlocker.blockedResponse()
        }

        return super.shouldInterceptRequest(view, request)
    }

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?
    ): Boolean {
        if (view == null || request == null) return false
        val url = request.url
        val targetHost = url.host?.lowercase() ?: return false

        // Two origins are trusted:
        //  - the configured domain (from domains.json), and
        //  - the host of the page the user is currently ON. The configured
        //    domain can redirect (or SPA-navigate) to a different final host;
        //    without the second check every link on the loaded page would be
        //    blocked as "cross-origin" and the site would feel completely
        //    unclickable.
        val configuredHost = DomainRepository.get(context).currentDomain()
            .let { android.net.Uri.parse(it).host?.lowercase() }
        val pageHost = view.url?.let { android.net.Uri.parse(it).host?.lowercase() }

        val allowed = isSameSite(targetHost, configuredHost) || isSameSite(targetHost, pageHost)
        if (!allowed) {
            Log.i(TAG, "Blocking cross-origin nav: $url (page=$pageHost configured=$configuredHost)")
        }
        return !allowed
    }

    private fun isSameSite(host: String, origin: String?): Boolean {
        if (origin.isNullOrBlank()) return false
        return host == origin || host.endsWith(".$origin") || origin.endsWith(".$host")
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        Log.i(TAG, "onPageFinished: $url")
        if (view == null || url == null) return
        injector.injectInto(view)
        // Video agent runs via addDocumentStartJavaScript in every frame;
        // this main-frame evaluation is the fallback path only.
        injector.injectVideoAgent(view)
        listener.onPageLoaded(url, view.title ?: url)
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
        super.onPageStarted(view, url, favicon)
        Log.i(TAG, "onPageStarted: $url")
        listener.onPageStarted(url)
    }

    override fun onReceivedSslError(
        view: WebView?,
        handler: SslErrorHandler?,
        error: SslError?
    ) {
        // streamingcommunity occasionally rotates self-signed certs during the
        // first few hours after a domain switch. We accept to keep the UX smooth.
        handler?.proceed()
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: android.webkit.WebResourceError?
    ) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame == true && error != null) {
            Log.i(TAG, "Main-frame error ${error.errorCode}: ${error.description}")
            listener.onPageError(error.errorCode, error.description?.toString() ?: "")
        } else if (request != null && error != null &&
            (error.errorCode == ERROR_HOST_LOOKUP || error.errorCode == ERROR_CONNECT)
        ) {
            Log.i(TAG, "Subresource failed ${error.errorCode}: ${request.url}")
        }
    }

    override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?
    ) {
        super.onReceivedHttpError(view, request, errorResponse)
        // Main-frame HTTP errors (e.g. Cloudflare 403/503) still render the
        // response body — surface them so the UI can drop the loading overlay.
        if (request?.isForMainFrame == true) {
            Log.i(TAG, "Main-frame HTTP ${errorResponse?.statusCode} for ${request.url}")
            listener.onHttpError(errorResponse?.statusCode ?: 0)
        }
    }

    override fun onRenderProcessGone(
        view: WebView?,
        detail: RenderProcessGoneDetail?
    ): Boolean {
        // The stick's LMK kills or the renderer OOM-crashes under memory
        // pressure. Without handling this, the WebView is left permanently
        // dead: last frame frozen on screen, touches consumed but nothing
        // happens. We report it and rebuild — the activity recreates itself
        // with a fresh WebView and reloads the current page.
        Log.e(TAG, "Renderer process gone (crashed=${detail?.didCrash()}) — recovering")
        listener.onRenderGone()
        return true
    }

    interface PageListener {
        fun onPageStarted(url: String?)
        fun onPageLoaded(url: String, title: String)
        fun onPageError(errorCode: Int, description: String)
        fun onHttpError(statusCode: Int) {}
        fun onRenderGone() {}
    }

    companion object {
        private const val TAG = "SCWebViewClient"

        /**
         * Windows 11 Chrome UA. Updated to a recent stable channel.
         * The site will treat us as a desktop browser and serve the full
         * catalogue + the standard web player.
         */
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
