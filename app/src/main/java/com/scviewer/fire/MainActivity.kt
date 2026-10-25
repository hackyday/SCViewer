package com.scviewer.fire

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ComponentCallbacks2
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.scviewer.fire.data.DomainRepository
import com.scviewer.fire.data.FavoritesStore
import com.scviewer.fire.data.HistoryStore
import com.scviewer.fire.data.PageInfo
import com.scviewer.fire.data.ProgressStore
import com.scviewer.fire.data.WatchProgress
import com.scviewer.fire.network.AdBlocker
import com.scviewer.fire.ui.CursorController
import com.scviewer.fire.ui.OverlayDialog
import com.scviewer.fire.ui.OverlayItem
import com.scviewer.fire.ui.SettingsDialog
import com.scviewer.fire.ui.SkipIntroController
import com.scviewer.fire.web.JsInterface
import com.scviewer.fire.web.SCWebChromeClient
import com.scviewer.fire.web.SCWebViewClient
import com.scviewer.fire.work.DomainRefreshScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class MainActivity : AppCompatActivity(), SCWebViewClient.PageListener {

    private lateinit var webView: WebView
    private lateinit var topStrip: LinearLayout
    private lateinit var domainLabel: TextView
    private lateinit var loadingOverlay: View
    private lateinit var menuBar: LinearLayout
    private lateinit var skipIntroController: SkipIntroController
    private lateinit var cursorCtl: CursorController
    private lateinit var jsBridge: JsInterface
    private lateinit var resumeChip: TextView

    /** True while a film is being watched (reported by the video agent
     *  running inside the player iframe). While set, D-pad left/right seeks
     *  and the cursor auto-hides after a few idle seconds. */
    @Volatile private var cinemaMode = false

    /** Page state from the site's Inertia data (reported by page_inject.js on
     *  every navigation): which show, movie/series, season/episode. */
    @Volatile private var currentPageInfo: PageInfo? = null

    /** Playback position last reported by the video agent (seconds). */
    @Volatile private var lastVideoPos = 0
    @Volatile private var lastVideoDur = 0

    /** Set when the user picks "Riprendi": titleId -> position to seek to
     *  once the video actually starts (native cannot push into the cross-
     * origin player iframe; the video agent polls for the seek command). */
    @Volatile private var pendingResume: Pair<Int, Int>? = null

    /** titleId the resume chip was already shown for on the current page. */
    private var resumeChipShownFor = 0

    /** The modal dialog currently on screen (favorites / history / resume /
     *  settings / resume-question), if any. While one is up, D-pad keys go to
     *  its native focus and cursor taps are routed to its window. */
    private var activeDialog: Dialog? = null

    /** Uptime of the last D-pad interaction — drives cursor auto-hide. */
    private var lastDpadAt = 0L

    /** Generation counter for the delayed "video really stopped" check, so a
     *  brief buffering stall doesn't flap the cursor back and forth. */
    private var videoOffGeneration = 0

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val domainRepo: DomainRepository by lazy { DomainRepository.get(this) }
    private val favorites: FavoritesStore by lazy { FavoritesStore.get(this) }
    private val history: HistoryStore by lazy { HistoryStore.get(this) }
    private val adBlocker: AdBlocker by lazy { AdBlocker.get(this) }
    private val progress: ProgressStore by lazy { ProgressStore.get(this) }

    private val handler = Handler(Looper.getMainLooper())

    /** Lenient parser for the PageInfo JSON coming from the page bridge. */
    private val pageJson = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Consecutive failed main-frame loads. Each failure triggers one
     *  domain refresh + reload until this hits zero; without the cap a dead
     *  domain produces a loadUrl storm (dozens of navigations per second)
     *  that hammers the CDN and starves the renderer on a 1 GB stick. */
    private var loadRetriesLeft = MAX_LOAD_RETRIES

    /** Guards the single automatic reload after a load timeout. */
    private var autoReloadUsed = false

    /** Fires when the main frame hasn't finished loading within
     *  [LOAD_TIMEOUT_MS] — WebView has no built-in load timeout, and a hung
     *  load otherwise leaves the opaque loading overlay up forever (black
     *  screen, nothing clickable). */
    private val loadTimeoutRunnable = Runnable { onMainLoadTimeout() }

    /** Holding OK longer than the system long-press timeout opens favorites;
     *  a short press clicks with the virtual mouse cursor instead. */
    private val okLongPressRunnable = Runnable {
        okLongPressFired = true
        showFavorites()
    }
    private var okLongPressFired = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        configureWebView()
        bindMenuButtons()

        // Schedule the 30-min periodic domain refresh here (rather than in
        // App.onCreate) so WorkManager's Configuration.Provider has a chance
        // to wire up first. Safe to call multiple times — enqueueUniquePeriodic
        // work uses ExistingPeriodicWorkPolicy.KEEP.
        try {
            DomainRefreshScheduler.schedule(this)
        } catch (t: Throwable) {
            Log.w(TAG, "WorkManager scheduling failed; periodic domain refresh disabled", t)
        }

        // Initial one-shot domain refresh (VibraVid-style: runs on every app launch).
        mainScope.launch { refreshAndLoad() }
    }

    private fun bindViews() {
        webView = findViewById(R.id.webView)
        topStrip = findViewById(R.id.topStrip)
        domainLabel = findViewById(R.id.domainLabel)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        menuBar = findViewById(R.id.menuBar)

        val skipContainer = findViewById<FrameLayout>(R.id.skipIntroContainer)
        val skipButton = findViewById<Button>(R.id.skipIntroButton)
        skipIntroController = SkipIntroController(skipContainer, skipButton, webView)

        // Virtual mouse cursor, attached to the activity's content root so it
        // floats above the WebView. Elevation keeps it on top even when other
        // views (fullscreen video, overlays) are re-added at runtime.
        cursorCtl = CursorController(
            this, webView,
            findViewById<FrameLayout>(android.R.id.content)
        )
        // While a dialog is on screen, cursor taps must land on the dialog's
        // own window (the WebView below is hidden behind it and dead).
        cursorCtl.overlayTargetProvider = { activeDialog?.window?.decorView }
        cursorCtl.attach()
        cursorCtl.show()

        // "Riprendi" chip (top-right): offered when the user lands on a page
        // of a show whose playback position we remember.
        val density = resources.displayMetrics.density
        resumeChip = TextView(this).apply {
            visibility = View.GONE
            text = getString(R.string.resume_chip_prefix)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setBackgroundResource(R.drawable.overlay_rounded)
            val pad = (10 * density).toInt()
            setPadding(pad * 2, pad, pad * 2, pad)
            elevation = 10f * density
            isClickable = true
            isFocusable = true
            setOnClickListener { onResumeChipClicked() }
        }
        findViewById<FrameLayout>(android.R.id.content).addView(
            resumeChip,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP or android.view.Gravity.END
            ).apply { topMargin = (24 * density).toInt(); marginEnd = (24 * density).toInt() }
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        // Chrome DevTools over ADB (chrome://inspect / adb forward) for
        // debugging the live page on the stick.
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        webView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mediaPlaybackRequiresUserGesture = false  // autoplay allowed
                loadWithOverviewMode = true
                useWideViewPort = true
                cacheMode = WebSettings.LOAD_DEFAULT

                // Disable geolocation, never used by the legit streaming site.
                setGeolocationEnabled(false)

                // Crucial: this is what tells streamingcommunity we're a desktop.
                // Win11 Chrome UA — see SCWebViewClient.DESKTOP_UA.
                userAgentString = SCWebViewClient.DESKTOP_UA
            }

            webViewClient = SCWebViewClient(this@MainActivity, this@MainActivity)
            webChromeClient = SCWebChromeClient(
                activity = this@MainActivity,
                root = this@MainActivity.findViewById(android.R.id.content),
                callback = object : SCWebChromeClient.FullscreenCallback {
                    override fun onFullscreenEntered() {
                        topStrip.visibility = View.GONE
                        cursorCtl.hide()
                    }

                    override fun onFullscreenExited() {
                        topStrip.visibility = View.GONE
                        cursorCtl.show()
                    }
                }
            )

            // JS bridge — must be added before the first page loads so
            // page_inject.js can find it on first run.
            jsBridge = JsInterface(
                onSkipIntro = { available -> runOnUiThread { skipIntroController.setAvailable(available) } },
                onAdBlockHit = { host -> adBlocker.addRuntime(host) },
                onVideoState = { active -> runOnUiThread { onVideoStateChanged(active) } },
                onPageInfo = { json -> runOnUiThread { onPageInfoChanged(json) } },
                onProgress = { pos, dur -> runOnUiThread { onVideoProgress(pos, dur) } },
                onPlayClicked = { tid ->
                    // JS bridge thread — synchronous verdict. "resume" makes
                    // the page hold the navigation while the popup asks.
                    // NOTE: this@MainActivity — inside webView.apply the
                    // unqualified `progress` is WebView.getProgress()!
                    val p = if (tid != 0) this@MainActivity.progress.get(tid) else null
                    if (p != null && p.isResumeable() && activeDialog == null) {
                        runOnUiThread { showResumeQuestion(p) }
                        "resume"
                    } else {
                        "none"
                    }
                }
            )
            addJavascriptInterface(jsBridge, "AndroidJs")

            // The streaming player lives inside the vixcloud IFRAME, which
            // evaluateJavascript (main frame only) cannot reach. Register the
            // video agent to run at document start in EVERY frame so it can
            // watch the player's <video> and apply play/pause commands from
            // the remote. Fallback for unsupported WebViews: the agent is
            // also evaluated in the main frame on every onPageFinished
            // (see SCWebViewClient).
            try {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    // Video agent (every frame) + early banner hide, so the
                    // Telegram bar never paints at all.
                    val agent = assets.open("video_agent.js").bufferedReader().use { it.readText() }
                    val earlyHide = assets.open("early_hide.js").bufferedReader().use { it.readText() }
                    WebViewCompat.addDocumentStartJavaScript(this, agent + "\n" + earlyHide, setOf("*"))
                    Log.i(TAG, "video agent + early-hide registered for all frames")
                } else {
                    Log.w(TAG, "DOCUMENT_START_SCRIPT unsupported — agent limited to main frame")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "video agent registration failed", t)
            }
        }
    }

    private fun bindMenuButtons() {
        findViewById<Button>(R.id.menuBtnHome).setOnClickListener {
            closeMenu()
            mainScope.launch { loadHome() }
        }
        findViewById<Button>(R.id.menuBtnFavorites).setOnClickListener {
            closeMenu()
            showFavorites()
        }
        findViewById<Button>(R.id.menuBtnHistory).setOnClickListener {
            closeMenu()
            showHistory()
        }
        findViewById<Button>(R.id.menuBtnResume).setOnClickListener {
            closeMenu()
            showResumeDialog()
        }
        findViewById<Button>(R.id.menuBtnReload).setOnClickListener {
            closeMenu()
            webView.reload()
        }
        findViewById<Button>(R.id.menuBtnBack).setOnClickListener {
            closeMenu()
            if (webView.canGoBack()) webView.goBack()
        }
        findViewById<Button>(R.id.menuBtnSettings).setOnClickListener {
            closeMenu()
            SettingsDialog(this, onSaved = { _ -> }, onActiveChange = { d -> activeDialog = d }).show()
        }
    }

    // -- Domain + initial load -----------------------------------------------
    //
    // Load order matters on a TV: the site must appear ASAP. The cached
    // domain is loaded IMMEDIATELY; the online refresh (up to ~12 s when
    // GitHub is slow) runs in parallel and only matters when the cached
    // domain turns out to be dead — that case is handled by the capped
    // retry in [onPageError].

    private suspend fun refreshAndLoad() {
        showLoading(true)
        // After a renderer crash the activity is recreated — restore the
        // page the user was on instead of jumping back to home.
        val recovery = rendererRecoveryUrl
        rendererRecoveryUrl = null
        val loadedUrl = recovery ?: withContext(Dispatchers.IO) { domainRepo.currentDomain() }
        Log.i(TAG, "initial load: $loadedUrl")
        webView.loadUrl(loadedUrl)
        updateDomainLabel()

        val result = domainRepo.refreshOnline()
        result.onSuccess { fresh ->
            showToast(getString(R.string.domain_refresh_success))
            // If the fresh domain differs and the initial load already died
            // (retries exhausted or timed out), give the new domain a chance.
            val current = withContext(Dispatchers.IO) { domainRepo.currentDomain() }
            if (fresh != loadedUrl && loadRetriesLeft <= 0) {
                Log.i(TAG, "fresh domain after failed load: $fresh")
                loadRetriesLeft = 1
                webView.loadUrl(fresh)
                updateDomainLabel()
            }
        }.onFailure {
            showToast(getString(R.string.domain_refresh_failed))
        }
    }

    private suspend fun loadHome() {
        val url = withContext(Dispatchers.IO) { domainRepo.currentDomain() }
        webView.loadUrl(url)
        updateDomainLabel()
    }

    private fun updateDomainLabel() {
        val url = domainRepo.currentDomain()
        val host = android.net.Uri.parse(url).host ?: url
        domainLabel.text = "› $host"
    }

    // -- PageListener --------------------------------------------------------

    override fun onPageStarted(url: String?) {
        showLoading(true)
        handler.removeCallbacks(loadTimeoutRunnable)
        handler.postDelayed(loadTimeoutRunnable, LOAD_TIMEOUT_MS)
        skipIntroController.setAvailable(false)
        topStrip.visibility = View.VISIBLE
        topStrip.postDelayed({ topStrip.visibility = View.GONE }, 3500)
    }

    override fun onPageLoaded(url: String, title: String) {
        Log.i(TAG, "page loaded: $url")
        handler.removeCallbacks(loadTimeoutRunnable)
        showLoading(false)
        loadRetriesLeft = MAX_LOAD_RETRIES
        autoReloadUsed = false
        // History is recorded by onPageInfoChanged — it knows the REAL show
        // name and which pages are worth remembering (document.title here is
        // just "StreamingCommunity" for every page).
        resumeChip.visibility = View.GONE
    }

    override fun onPageError(errorCode: Int, description: String) {
        Log.i(TAG, "page error $errorCode ($description), retries left: $loadRetriesLeft")
        handler.removeCallbacks(loadTimeoutRunnable)
        if (errorCode == ERROR_NAME_NOT_RESOLVED || errorCode == ERROR_CONNECT_FAILED) {
            if (loadRetriesLeft > 0) {
                loadRetriesLeft--
                showLoading(true)
                // Single delayed retry — never a tight loop.
                handler.postDelayed({
                    mainScope.launch {
                        domainRepo.refreshOnline()
                        loadHome()
                    }
                }, RETRY_DELAY_MS)
                return
            }
            showLoading(false)
            showToast(getString(R.string.load_failed_hint))
        } else {
            showLoading(false)
        }
    }

    override fun onHttpError(statusCode: Int) {
        // The WebView renders the error body itself (e.g. a Cloudflare block
        // page); the overlay must go so the user can actually see it.
        Log.i(TAG, "page HTTP error: $statusCode")
        handler.removeCallbacks(loadTimeoutRunnable)
        showLoading(false)
    }

    override fun onRenderGone() {
        // See SCWebViewClient.onRenderProcessGone — recreate the activity,
        // which rebuilds the WebView, cursor and controllers from scratch.
        Log.e(TAG, "renderer gone -> recreating activity")
        rendererRecoveryUrl = webView.url
        cinemaMode = false
        recreate()
    }

    // -- Cinema mode (film watching) ------------------------------------------

    /** Called from the JsInterface callback (already hopped to the UI thread). */
    private fun onVideoStateChanged(active: Boolean) {
        if (active) {
            val wasOff = !cinemaMode
            videoOffGeneration++                       // cancel any pending off-check
            if (wasOff) {
                cinemaMode = true
                Log.i(TAG, "cinema mode ON (cursor auto-hides when idle)")
                handler.removeCallbacks(cursorAutoHideRunnable)
                handler.postDelayed(cursorAutoHideRunnable, CURSOR_HIDE_AFTER_MS)
                // "Riprendi" in flight? Jump to the saved position now that
                // the video is actually running.
                if (pendingResume != null) pushPendingResumeSeek()
            }
        } else {
            // Debounce: buffering stalls flap the state; only leave cinema
            // mode after the video has been gone for a moment.
            val gen = ++videoOffGeneration
            handler.postDelayed({
                if (gen == videoOffGeneration && cinemaMode) {
                    cinemaMode = false
                    Log.i(TAG, "cinema mode OFF (cursor back)")
                    handler.removeCallbacks(cursorAutoHideRunnable)
                    cursorCtl.show()
                }
            }, VIDEO_OFF_CONFIRM_MS)
        }
    }

    /** While in cinema mode, hides the cursor a few seconds after the last
     *  D-pad interaction. */
    private val cursorAutoHideRunnable = object : Runnable {
        override fun run() {
            if (!cinemaMode) return
            if (cursorCtl.isActive() &&
                android.os.SystemClock.uptimeMillis() - lastDpadAt >= CURSOR_HIDE_AFTER_MS
            ) {
                cursorCtl.hide()
            }
            handler.postDelayed(this, 1000)
        }
    }

    // -- Riprendi (continue watching) -----------------------------------------

    /** Called from the JsInterface callback (UI thread): the Inertia page
     *  state changed — the user navigated to a new page of the site. */
    private fun onPageInfoChanged(json: String) {
        val info = runCatching { pageJson.decodeFromString<PageInfo>(json) }.getOrNull() ?: return
        if (info.titleId == 0) return
        val prev = currentPageInfo
        currentPageInfo = info

        // A pending resume that doesn't match the page the user actually went
        // to is stale — drop it (they aborted the resume).
        pendingResume?.let { (tid, _) -> if (tid != info.titleId) pendingResume = null }

        // History: record only meaningful pages, with the REAL show name (the
        // document title alone is just "StreamingCommunity"). Watch pages get
        // the season/episode suffix so the list is actually usable.
        val url = webView.url ?: return
        when {
            info.isWatchPage -> history.add(url, info.displayLabel())
            info.isTitlePage -> history.add(url, info.name)
            else -> {} // home / genres / search: not history-worthy
        }

        // Resume chip: only on title/watch pages of a show we have progress
        // for, once per page, and not when a resume is already in flight.
        val p = progress.get(info.titleId)
        if (p != null && p.isResumeable() && pendingResume == null &&
            resumeChipShownFor != info.titleId
        ) {
            resumeChipShownFor = info.titleId
            val tag = p.seasonEpisodeTag()
            resumeChip.text = getString(R.string.resume_chip_prefix) + " " +
                p.positionLabel() + (if (tag.isNotEmpty()) " ($tag)" else "")
            resumeChip.visibility = View.VISIBLE
            resumeChip.bringToFront()
            Log.i(TAG, "resume chip shown for ${info.name} at ${p.positionLabel()}")
        } else if (p == null || pendingResume != null) {
            resumeChip.visibility = View.GONE
        }
        if (prev?.titleId != info.titleId) Log.i(TAG, "page info: $info")
    }

    /** Called from the JsInterface callback (UI thread) ~every 10 s while a
     *  film plays (and once when it pauses). */
    private fun onVideoProgress(posSec: Int, durSec: Int) {
        lastVideoPos = posSec
        lastVideoDur = durSec

        // Seek confirmation: once playback is near the requested resume
        // position, the resume is fulfilled.
        pendingResume?.let { (tid, target) ->
            if (tid == currentPageInfo?.titleId && kotlin.math.abs(posSec - target) < 20) {
                Log.i(TAG, "resume seek confirmed at $posSec")
                pendingResume = null
            }
        }

        val info = currentPageInfo ?: return
        if (!info.isWatchPage || durSec < 60) return
        progress.save(
            WatchProgress(
                titleId = info.titleId,
                name = info.name,
                slug = info.slug,
                type = info.type,
                season = info.season,
                episode = info.episode,
                episodeName = info.episodeName,
                positionSec = posSec,
                durationSec = durSec,
                url = webView.url ?: "",
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /** User picked a resume entry: reload its exact watch URL and remember
     *  where to seek once the video is running. */
    private fun startResume(p: WatchProgress) {
        Log.i(TAG, "resume -> ${p.name} ${p.seasonEpisodeTag()} at ${p.positionLabel()}")
        pendingResume = p.titleId to p.positionSec
        resumeChip.visibility = View.GONE
        resumeChipShownFor = p.titleId   // don't re-offer what we just started
        if (p.url.isNotBlank() && p.url.startsWith("http")) {
            webView.loadUrl(p.url)
        } else {
            showToast(getString(R.string.resume_unavailable))
            pendingResume = null
        }
    }

    private fun onResumeChipClicked() {
        val info = currentPageInfo ?: return
        val p = progress.get(info.titleId) ?: return
        startResume(p)
    }

    /** "Guarda" clicked on a title we have progress for: ask whether to
     *  continue from the saved minute or start over. */
    private fun showResumeQuestion(p: WatchProgress) {
        val tag = p.seasonEpisodeTag()
        val name = p.name + (if (tag.isNotEmpty()) " · $tag" else "")
        val dlg = android.app.AlertDialog.Builder(this, R.style.SCOverlay)
            .setTitle(R.string.resume_question_title)
            .setMessage(getString(R.string.resume_question_msg, name, p.positionLabel()))
            .setPositiveButton(R.string.resume_yes) { _, _ -> startResume(p) }
            .setNeutralButton(R.string.resume_restart) { _, _ ->
                progress.remove(p.titleId)
                resumeChip.visibility = View.GONE
                // Let the original navigation proceed to the play page.
                webView.evaluateJavascript(
                    "(window.__scvContinuePlay&&window.__scvContinuePlay());", null
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        activeDialog = dlg
        dlg.setOnDismissListener { activeDialog = null }
        dlg.show()
        // Default focus on "Riprendi" — the safe choice. (AlertDialog would
        // otherwise land on a different button, and accidentally confirming
        // "Ricomincia da capo" would throw the saved position away.)
        dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.requestFocus()
    }

    /** Fires after the video started playing following a "Riprendi": push the
     *  seek a few times (the player may still be buffering when the first
     *  command lands) until a progress report confirms it. */
    private fun pushPendingResumeSeek() {
        val (tid, target) = pendingResume ?: return
        if (tid != currentPageInfo?.titleId) return
        jsBridge.requestSeekTo(target)
        handler.postDelayed({ if (pendingResume != null) jsBridge.requestSeekTo(target) }, 3500)
        handler.postDelayed({ if (pendingResume != null) jsBridge.requestSeekTo(target) }, 8000)
    }

    /** "Riprendi" dialog: everything in progress, most recent first. */
    private fun showResumeDialog() {
        val entries = progress.resumeable()
        val items = entries.map { p ->
            val tag = p.seasonEpisodeTag()
            OverlayItem(
                title = p.name + (if (tag.isNotEmpty()) " · $tag" else ""),
                subtitle = "riprendi da ${p.positionLabel()}",
                tag = p.titleId.toString()
            )
        }
        val byId = entries.associateBy { it.titleId.toString() }
        OverlayDialog(
            context = this,
            title = getString(R.string.menu_resume),
            items = items,
            emptyMessage = getString(R.string.empty_resume),
            onItemClick = { item -> byId[item.tag]?.let { startResume(it) } },
            onAddCurrent = null,
            onActiveChange = { d -> activeDialog = d }
        ).show()
    }

    private fun onMainLoadTimeout() {
        Log.i(TAG, "main-frame load timed out after ${LOAD_TIMEOUT_MS}ms")
        showLoading(false)
        if (!autoReloadUsed) {
            autoReloadUsed = true
            showToast(getString(R.string.load_timeout_retry))
            handler.postDelayed({ webView.reload() }, RETRY_DELAY_MS)
        } else {
            showToast(getString(R.string.load_failed_hint))
        }
    }

    // -- Overlays ------------------------------------------------------------

    private fun showFavorites() {
        val items = favorites.all().map {
            // No URL in the list — the name is the info the user needs; the
            // link travels in the tag.
            OverlayItem(title = it.title, subtitle = "", tag = it.url)
        }
        val dialog = OverlayDialog(
            context = this,
            title = getString(R.string.favorites_title),
            items = items,
            emptyMessage = getString(R.string.empty_favorites),
            onItemClick = { item -> webView.loadUrl(item.tag) },
            onAddCurrent = {
                val url = webView.url ?: return@OverlayDialog
                // Prefer the real show name from the page bridge; the
                // document title is just "StreamingCommunity".
                val title = currentPageInfo?.takeIf { it.titleId != 0 }?.displayLabel()
                    ?: (webView.title ?: url)
                favorites.add(url, title)
                Toast.makeText(this, "Aggiunto ai preferiti", Toast.LENGTH_SHORT).show()
            },
            onActiveChange = { d -> activeDialog = d }
        )
        dialog.show()
    }

    private fun showHistory() {
        val items = history.all().map {
            OverlayItem(title = it.title, subtitle = "", tag = it.url)
        }
        val dialog = OverlayDialog(
            context = this,
            title = getString(R.string.history_title),
            items = items,
            emptyMessage = getString(R.string.empty_history),
            onItemClick = { item -> webView.loadUrl(item.tag) },
            onAddCurrent = null,
            onActiveChange = { d -> activeDialog = d }
        )
        dialog.show()
    }

    // -- Fire TV remote key handling -----------------------------------------
    //
    // The D-pad now drives a virtual mouse cursor (CursorController):
    //   arrows          → move the pointer (smooth acceleration while held)
    //   OK / Enter      → click at the pointer; held → open favorites
    //   MENU (hamburger)→ toggle the bottom menu bar (native focus inside it)
    //   BACK            → close menu / web history back / exit
    //   Rewind/Fwd keys → scroll the page (handy for long catalogues)
    //
    // We intercept in dispatchKeyEvent because it sees every event of the
    // activity window before any view, and because Android drops D-pad events
    // entirely whenever no view has focus (which happens after menu/dialog
    // close) — the cursor must never depend on view focus.

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val action = event.action
        val keyCode = event.keyCode

        if (keyCode == KeyEvent.KEYCODE_MENU && action == KeyEvent.ACTION_DOWN) {
            if (isMenuOpen()) closeMenu() else openMenu()
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_BACK && action == KeyEvent.ACTION_DOWN) {
            if (isMenuOpen()) {
                closeMenu()
                return true
            }
            if (webView.canGoBack()) {
                webView.goBack()
                return true
            }
            return super.dispatchKeyEvent(event)   // default: leave the app
        }

        // While the menu bar or a modal dialog is open, the native focus
        // system walks it (menu buttons / list rows / dialog buttons) — the
        // virtual cursor must not steal the keys.
        if (activeDialog != null || isMenuOpen()) return super.dispatchKeyEvent(event)

        // Any D-pad touch counts as "user is interacting" for cursor auto-hide.
        if (action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
            keyCode in DPAD_KEYS
        ) {
            lastDpadAt = android.os.SystemClock.uptimeMillis()
        }

        // -- Cinema mode: a film is playing and the cursor is hidden ----------
        // Any D-pad press brings the cursor back so the player's options can
        // be reached with the pointer (it re-hides after a few idle seconds).
        if (cinemaMode && !cursorCtl.isActive()) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    // Reveal the cursor AND process the press as a move.
                    cursorCtl.show()
                    if (action == KeyEvent.ACTION_DOWN) {
                        cursorCtl.onDirectionDown(keyCode, event.repeatCount)
                    } else if (action == KeyEvent.ACTION_UP) {
                        cursorCtl.onDirectionUp(keyCode)
                    }
                    return true
                }

                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    // First OK only reveals the cursor — no blind click at an
                    // unknown position. Subsequent OKs click normally.
                    if (action == KeyEvent.ACTION_DOWN) cursorCtl.show()
                    return true
                }
            }
        }

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (action == KeyEvent.ACTION_DOWN) {
                    cursorCtl.onDirectionDown(keyCode, event.repeatCount)
                } else if (action == KeyEvent.ACTION_UP) {
                    cursorCtl.onDirectionUp(keyCode)
                }
                return true
            }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                if (action == KeyEvent.ACTION_DOWN) {
                    if (event.repeatCount == 0) {
                        okLongPressFired = false
                        // 600 ms: comfortably above a natural OK tap (so
                        // every normal press clicks) but still a snappy
                        // long-press for the favorites overlay.
                        handler.postDelayed(okLongPressRunnable, FAVORITES_HOLD_MS)
                    }
                } else if (action == KeyEvent.ACTION_UP) {
                    handler.removeCallbacks(okLongPressRunnable)
                    Log.i(TAG, "OK up -> cursor click (longPressFired=$okLongPressFired)")
                    // NOTE: no visibility gating here — a stuck overlay must
                    // never silently eat OK presses. If something is genuinely
                    // covering the screen the tap is harmless anyway.
                    if (!okLongPressFired) {
                        cursorCtl.click()
                    }
                }
                return true
            }

            // Remote play/pause key toggles the film's <video> (via the JS
            // bridge — the player lives inside the vixcloud iframe). Harmless
            // on catalogue pages: with no active video the request expires.
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    jsBridge.requestPauseToggle()
                }
                return true
            }

            // Optional playback keys (present on some Fire TV remotes):
            // fling the page up/down like a swipe (flingScroll is a public
            // WebView API — View.smoothScrollBy is hidden and NOT callable).
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    webView.flingScroll(0, -PAGE_SCROLL_FLING)
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    webView.flingScroll(0, PAGE_SCROLL_FLING)
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // -- Menu bar ------------------------------------------------------------

    private fun isMenuOpen(): Boolean = menuBar.visibility == View.VISIBLE

    private fun openMenu() {
        menuBar.visibility = View.VISIBLE
        cursorCtl.hide()
        // Keep D-pad traversal inside the bar: without this the focus could
        // wander into the WebView where nothing is reachable.
        webView.isFocusable = false
        findViewById<Button>(R.id.menuBtnHome).requestFocus()
        menuBar.postDelayed({
            if (isMenuOpen() && !menuBar.hasFocus()) closeMenu()
        }, MENU_AUTO_HIDE_MS)
    }

    private fun closeMenu() {
        menuBar.visibility = View.GONE
        webView.isFocusable = true
        cursorCtl.show()
    }

    // -- Lifecycle -----------------------------------------------------------

    override fun onPause() {
        super.onPause()
        webView.onPause()
        cursorCtl.stopMovement()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        // VibraVid-style: refresh domain every time we come back to foreground
        // in case the WorkManager cycle hasn't fired in a while.
        mainScope.launch { domainRepo.refreshOnline() }
        updateDomainLabel()
    }

    override fun onDestroy() {
        cursorCtl.destroy()
        handler.removeCallbacksAndMessages(null)
        webView.apply {
            stopLoading()
            removeAllViews()
            destroy()
        }
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The stick has ~900 MB total; the system LMK kills us without any
        // crash log when memory runs dry. Free what we can when asked.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            Log.i(TAG, "onTrimMemory($level) -> clearing WebView RAM cache")
            webView.clearCache(false)
        }
    }

    // -- Helpers -------------------------------------------------------------

    private fun showLoading(show: Boolean) {
        loadingOverlay.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showToast(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        private const val TAG = "MainActivity"
        /** Page to restore after a renderer crash (survives recreate()). */
        @Volatile private var rendererRecoveryUrl: String? = null
        private const val MENU_AUTO_HIDE_MS = 4500L
        /** OK hold time that opens favorites (short press = cursor click).
         *  750 ms: a deliberately slow OK press (~400-600 ms on Fire TV
         *  remotes) still counts as a click; only a true hold opens
         *  favorites. */
        private const val FAVORITES_HOLD_MS = 750L
        /** Fling velocity (px/s) for the Rewind / Fast-forward page scroll. */
        private const val PAGE_SCROLL_FLING = 1800

        /** Max automatic domain-refresh+reload cycles per failed load. */
        private const val MAX_LOAD_RETRIES = 3
        /** Cooldown between automatic retries. */
        private const val RETRY_DELAY_MS = 3000L
        /** WebView has no load timeout of its own — this is ours. */
        private const val LOAD_TIMEOUT_MS = 25_000L

        /** android.webkit.WebViewClient legacy error codes. */
        private const val ERROR_NAME_NOT_RESOLVED = -2
        private const val ERROR_CONNECT_FAILED = -6

        /** Hide the cursor this long after the last D-pad press in cinema mode. */
        private const val CURSOR_HIDE_AFTER_MS = 4000L
        /** Video must be gone this long before cinema mode actually ends. */
        private const val VIDEO_OFF_CONFIRM_MS = 2500L

        private val DPAD_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER
        )
    }
}
