package com.scviewer.fire

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ComponentCallbacks2
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
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
import com.scviewer.fire.data.PosterCache
import com.scviewer.fire.data.ProgressStore
import com.scviewer.fire.data.SettingsStore
import com.scviewer.fire.data.SiteUrl
import com.scviewer.fire.data.WatchProgress
import com.scviewer.fire.network.AdBlocker
import com.scviewer.fire.network.HlsRewriter
import com.scviewer.fire.ui.CursorController
import com.scviewer.fire.ui.OverlayDialog
import com.scviewer.fire.ui.OverlayItem
import com.scviewer.fire.ui.SettingsDialog
import com.scviewer.fire.ui.SkipIntroController
import com.scviewer.fire.ui.TvSidebarController
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
    private lateinit var skipIntroController: SkipIntroController
    private lateinit var cursorCtl: CursorController
    private lateinit var sidebarCtl: TvSidebarController
    private lateinit var jsBridge: JsInterface
    private lateinit var resumeChip: TextView

    @Volatile private var cinemaMode = false
    @Volatile private var currentPageInfo: PageInfo? = null
    @Volatile private var lastVideoPos = 0
    @Volatile private var lastVideoDur = 0
    @Volatile private var pendingResume: Pair<Int, Int>? = null

    private var resumeChipShownFor = 0
    private var activeDialog: Dialog? = null
    private var updateChip: TextView? = null

    private val isTouchDevice by lazy {
        packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)
    }
    private lateinit var phoneMenuButton: ImageView

    private var lastDpadAt = 0L
    private var videoOffGeneration = 0

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val domainRepo: DomainRepository by lazy { DomainRepository.get(this) }
    private val favorites: FavoritesStore by lazy { FavoritesStore.get(this) }
    private val history: HistoryStore by lazy { HistoryStore.get(this) }
    private val adBlocker: AdBlocker by lazy { AdBlocker.get(this) }
    private val progress: ProgressStore by lazy { ProgressStore.get(this) }

    private val handler = Handler(Looper.getMainLooper())
    private val pageJson = Json { ignoreUnknownKeys = true; isLenient = true }

    private var loadRetriesLeft = MAX_LOAD_RETRIES
    private var autoReloadUsed = false
    private var blockRecoveriesLeft = MAX_BLOCK_RECOVERIES
    private var autoUaFallback = false
    private var recovering = false

    private var updateTag: String? = null
    private var autoUpdateRunning = false

    private val loadTimeoutRunnable = Runnable { onMainLoadTimeout() }

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
        bindSidebar()

        try {
            DomainRefreshScheduler.schedule(this)
        } catch (t: Throwable) {
            Log.w(TAG, "WorkManager scheduling failed", t)
        }

        mainScope.launch { refreshAndLoad() }

        HlsRewriter.maxHeightOverride = SettingsStore.qualityCapHeight(this)
        mainScope.launch(Dispatchers.IO) {
            runCatching { AdBlocker.get(this@MainActivity).syncFromGitHub() }
        }
        checkForUpdates()
    }

    // -- Updates --------------------------------------------------------------

    private fun checkForUpdates() {
        mainScope.launch(Dispatchers.IO) {
            val prefs = getSharedPreferences("scviewer_updates", MODE_PRIVATE)
            val last = prefs.getLong("last_check", 0L)
            if (System.currentTimeMillis() - last < 12 * 3600_000L) return@launch
            val latest = runCatching {
                val client = okhttp3.OkHttpClient.Builder()
                    .callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
                client.newCall(
                    okhttp3.Request.Builder()
                        .url("https://api.github.com/repos/hackyday/SCViewer/releases/latest")
                        .header("Accept", "application/vnd.github+json")
                        .build()
                ).execute().use { resp ->
                    if (!resp.isSuccessful) null
                    else resp.body?.string()?.let {
                        org.json.JSONObject(it).optString("tag_name")
                    }
                }
            }.getOrNull() ?: return@launch
            prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
            val current = "v" + BuildConfig.VERSION_NAME
            if (latest.isNotBlank() && latest != current) {
                runOnUiThread { showUpdateChip(latest) }
                if (isVersionNewer(latest, current)) {
                    runOnUiThread { startAutoUpdate(latest) }
                }
            }
        }
    }

    private fun isVersionNewer(latest: String, current: String): Boolean {
        fun nums(v: String) = v.removePrefix("v").split('.').let {
            (it.getOrNull(0)?.filter { c -> c.isDigit() }?.toLongOrNull() ?: 0L) * 1_000_000L +
            (it.getOrNull(1)?.filter { c -> c.isDigit() }?.toLongOrNull() ?: 0L) * 1_000L +
            (it.getOrNull(2)?.filter { c -> c.isDigit() }?.toLongOrNull() ?: 0L)
        }
        return runCatching { nums(latest) > nums(current) }.getOrDefault(false)
    }

    private fun startAutoUpdate(tag: String) {
        if (tag.isBlank()) return
        updateTag = tag
        if (autoUpdateRunning) return
        autoUpdateRunning = true
        val url = "https://github.com/hackyday/SCViewer/releases/download/$tag/app-release.apk"
        showToast(getString(R.string.update_downloading, tag))
        mainScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                val client = okhttp3.OkHttpClient.Builder()
                    .callTimeout(90, java.util.concurrent.TimeUnit.SECONDS).build()
                client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val dst = java.io.File(cacheDir, "update-$tag.apk")
                    dst.parentFile?.mkdirs()
                    dst.outputStream().use { out -> resp.body!!.byteStream().copyTo(out) }
                    dst.length() > 100_000L &&
                        dst.inputStream().use { ins -> ins.read() == 'P'.code && ins.read() == 'K'.code }
                }
            }.getOrDefault(false)
            autoUpdateRunning = false
            if (!ok) {
                runOnUiThread { showToast(getString(R.string.update_download_failed)) }
                return@launch
            }
            runOnUiThread {
                if (cinemaMode) {
                    showToast(getString(R.string.update_ready))
                    return@runOnUiThread
                }
                showToast(getString(R.string.update_ready))
                launchInstaller(tag)
            }
        }
    }

    private fun launchInstaller(tag: String) {
        runCatching {
            val apk = java.io.File(cacheDir, "update-$tag.apk")
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", apk
            )
            startActivity(
                android.content.Intent(android.content.Intent.ACTION_INSTALL_PACKAGE).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                }
            )
        }.onFailure { Log.w(TAG, "installer failed", it) }
    }

    private fun showUpdateChip(version: String) {
        val content = findViewById<FrameLayout>(android.R.id.content)
        val density = resources.displayMetrics.density
        updateChip = TextView(this).apply {
            text = getString(R.string.update_chip, version)
            setTextColor(0xFFF5F7FA.toInt())
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setBackgroundResource(R.drawable.bg_sctv_chip)
            val padH = (16 * density).toInt()
            val padV = (9 * density).toInt()
            setPadding(padH, padV, padH, padV)
            elevation = 10f * density
            isClickable = true
            isFocusable = true
            setOnClickListener { startAutoUpdate(updateTag ?: "") }
        }
        content.addView(
            updateChip,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP or android.view.Gravity.START
            ).apply {
                topMargin = (20 * density).toInt()
                marginStart = (88 * density).toInt()
            }
        )
    }

    // -- View Binding & Sidebar -----------------------------------------------

    private fun bindViews() {
        webView = findViewById(R.id.webView)
        topStrip = findViewById(R.id.topStrip)
        domainLabel = findViewById(R.id.domainLabel)
        loadingOverlay = findViewById(R.id.loadingOverlay)

        val skipContainer = findViewById<FrameLayout>(R.id.skipIntroContainer)
        val skipButton = findViewById<Button>(R.id.skipIntroButton)
        skipIntroController = SkipIntroController(skipContainer, skipButton) { jsBridge.requestSkipIntro() }

        cursorCtl = CursorController(
            this, webView,
            findViewById<FrameLayout>(android.R.id.content)
        )
        cursorCtl.overlayTargetProvider = {
            activeDialog?.window?.decorView
                ?: findViewById<FrameLayout>(android.R.id.content)
        }
        cursorCtl.onLeftEdgeTrigger = {
            if (!isTouchDevice && !cinemaMode) {
                sidebarCtl.focusSidebar()
            }
        }

        if (!isTouchDevice) {
            cursorCtl.attach()
        } else {
            // Touch device: full-bleed WebView + floating studio menu trigger
            (webView.layoutParams as? FrameLayout.LayoutParams)?.marginStart = 0
            findViewById<View>(R.id.tvSidebarRoot).visibility = View.GONE

            val density = resources.displayMetrics.density
            phoneMenuButton = ImageView(this).apply {
                setImageResource(R.drawable.ic_menu)
                scaleType = ImageView.ScaleType.CENTER
                setBackgroundResource(R.drawable.bg_sctv_chip)
                elevation = 10f * density
                isClickable = true
                isFocusable = true
                setOnClickListener { showPhoneMenu() }
            }
            findViewById<FrameLayout>(android.R.id.content).addView(
                phoneMenuButton,
                FrameLayout.LayoutParams(
                    (48 * density).toInt(), (48 * density).toInt(),
                    android.view.Gravity.BOTTOM or android.view.Gravity.END
                ).apply {
                    marginEnd = (20 * density).toInt()
                    bottomMargin = (20 * density).toInt()
                }
            )
        }

        val density = resources.displayMetrics.density
        resumeChip = TextView(this).apply {
            visibility = View.GONE
            text = getString(R.string.resume_chip_prefix)
            setTextColor(0xFFF5F7FA.toInt())
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setBackgroundResource(R.drawable.bg_sctv_chip)
            val padH = (18 * density).toInt()
            val padV = (10 * density).toInt()
            setPadding(padH, padV, padH, padV)
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
            ).apply {
                topMargin = (20 * density).toInt()
                marginEnd = (24 * density).toInt()
            }
        )
    }

    private fun bindSidebar() {
        val sidebarRoot = findViewById<LinearLayout>(R.id.tvSidebarRoot)
        sidebarCtl = TvSidebarController(
            root = sidebarRoot,
            onSearch = { showSearchDialog() },
            onHome = { mainScope.launch { loadHome() } },
            onResume = { showResumeDialog() },
            onFavorites = { showFavorites() },
            onHistory = { showHistory() },
            onReload = { webView.reload() },
            onBack = { if (webView.canGoBack()) webView.goBack() },
            onSettings = {
                SettingsDialog(
                    this,
                    onSaved = { _ ->
                        HlsRewriter.maxHeightOverride = SettingsStore.qualityCapHeight(this)
                        applyUserAgent()
                    },
                    onActiveChange = { d -> activeDialog = d }
                ).show()
            },
            onExpandedChanged = { expanded ->
                if (!isTouchDevice && expanded) {
                    cursorCtl.hide()
                }
            }
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
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
                mediaPlaybackRequiresUserGesture = false
                loadWithOverviewMode = true
                useWideViewPort = true
                cacheMode = WebSettings.LOAD_DEFAULT
                setGeolocationEnabled(false)
                applyUserAgent()
            }

            webViewClient = SCWebViewClient(this@MainActivity, this@MainActivity)
            webChromeClient = SCWebChromeClient(
                activity = this@MainActivity,
                root = this@MainActivity.findViewById(android.R.id.content),
                callback = object : SCWebChromeClient.FullscreenCallback {
                    override fun onFullscreenEntered() {
                        topStrip.visibility = View.GONE
                        sidebarCtl.setCinemaHidden(true)
                        (webView.layoutParams as? FrameLayout.LayoutParams)?.marginStart = 0
                        cursorCtl.hide()
                    }

                    override fun onFullscreenExited() {
                        topStrip.visibility = View.GONE
                        if (!isTouchDevice) {
                            (webView.layoutParams as? FrameLayout.LayoutParams)?.marginStart =
                                resources.getDimensionPixelSize(R.dimen.sctv_rail_collapsed_width)
                            sidebarCtl.setCinemaHidden(false)
                        }
                    }
                }
            )

            jsBridge = JsInterface(
                onSkipIntro = { available ->
                    runOnUiThread {
                        val isWatch = webView.url?.contains("/watch/") == true
                        skipIntroController.setAvailable(available && isWatch)
                    }
                },
                onAdBlockHit = { host -> adBlocker.addRuntime(host) },
                onVideoState = { active -> runOnUiThread { onVideoStateChanged(active) } },
                onPageInfo = { json -> runOnUiThread { onPageInfoChanged(json) } },
                onProgress = { pos, dur -> runOnUiThread { onVideoProgress(pos, dur) } },
                onPosterFound = { tid, url -> runOnUiThread { PosterCache.put(this@MainActivity, tid, url) } },
                onCloudflareBlock = { runOnUiThread { recoverFromBlock("JS block page") } },
                onPlayClicked = { tid ->
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

            try {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    val agent = assets.open("video_agent.js").bufferedReader().use { it.readText() }
                    val earlyHide = assets.open("early_hide.js").bufferedReader().use { it.readText() }
                    WebViewCompat.addDocumentStartJavaScript(this, agent + "\n" + earlyHide, setOf("*"))
                }
            } catch (t: Throwable) {
                Log.w(TAG, "video agent registration failed", t)
            }
        }
    }

    // -- Domain & Navigation --------------------------------------------------

    private suspend fun refreshAndLoad() {
        showLoading(true)
        val recovery = rendererRecoveryUrl
        rendererRecoveryUrl = null
        val loadedUrl = recovery ?: withContext(Dispatchers.IO) { domainRepo.currentDomain() }
        webView.loadUrl(loadedUrl)
        updateDomainLabel()

        val result = domainRepo.refreshOnline()
        result.onSuccess { fresh ->
            showToast(getString(R.string.domain_refresh_success))
            if (fresh != loadedUrl && loadRetriesLeft <= 0) {
                loadRetriesLeft = 1
                webView.loadUrl(fresh)
                updateDomainLabel()
            }
        }.onFailure {
            showToast(getString(R.string.domain_refresh_failed))
        }
    }

    private suspend fun loadHome() {
        val url = withContext(Dispatchers.IO) { domainRepo.loadCandidate() }
        webView.loadUrl(url)
        updateDomainLabel()
    }

    private fun applyUserAgent() {
        val alt = autoUaFallback || SettingsStore.getAlternateUa(this)
        webView.settings.userAgentString =
            if (alt) SCWebViewClient.ALTERNATE_UA else SCWebViewClient.DESKTOP_UA
    }

    private fun looksLikeBlockPage(text: String?): Boolean {
        val t = (text ?: "").lowercase()
        return listOf(
            "just a moment", "attention required", "access denied",
            "you have been blocked", "checking your browser",
            "error 1106", "cloudflare"
        ).any { t.contains(it) }
    }

    private fun recoverFromBlock(reason: String) {
        if (recovering || blockRecoveriesLeft <= 0) return
        recovering = true
        blockRecoveriesLeft--
        showLoading(true)
        handler.postDelayed({
            mainScope.launch {
                try {
                    if (blockRecoveriesLeft <= MAX_BLOCK_RECOVERIES - 2 && !autoUaFallback) {
                        autoUaFallback = true
                        applyUserAgent()
                    }
                    val failedUrl = webView.url ?: domainRepo.currentDomain()
                    withContext(Dispatchers.IO) {
                        domainRepo.markDomainFailed(failedUrl)
                        domainRepo.refreshOnline()
                    }
                    loadHome()
                } finally {
                    recovering = false
                }
            }
        }, RETRY_DELAY_MS)
    }

    private fun updateDomainLabel() {
        val url = domainRepo.currentDomain()
        val host = Uri.parse(url).host ?: url
        domainLabel.text = host
        if (::sidebarCtl.isInitialized) {
            sidebarCtl.setDomainText(host)
        }
    }

    // -- PageListener ---------------------------------------------------------

    override fun onPageStarted(url: String?) {
        showLoading(true)
        handler.removeCallbacks(loadTimeoutRunnable)
        handler.postDelayed(loadTimeoutRunnable, LOAD_TIMEOUT_MS)
        skipIntroController.setAvailable(false)
        topStrip.visibility = View.VISIBLE
        topStrip.postDelayed({ topStrip.visibility = View.GONE }, 3500)
    }

    override fun onPageLoaded(url: String, title: String) {
        handler.removeCallbacks(loadTimeoutRunnable)
        showLoading(false)
        if (looksLikeBlockPage(title)) {
            recoverFromBlock("block page title: $title")
            return
        }
        blockRecoveriesLeft = MAX_BLOCK_RECOVERIES
        loadRetriesLeft = MAX_LOAD_RETRIES
        autoReloadUsed = false
        resumeChip.visibility = View.GONE
    }

    override fun onPageError(errorCode: Int, description: String) {
        handler.removeCallbacks(loadTimeoutRunnable)
        if (errorCode == ERROR_NAME_NOT_RESOLVED || errorCode == ERROR_CONNECT_FAILED) {
            if (loadRetriesLeft > 0) {
                loadRetriesLeft--
                showLoading(true)
                handler.postDelayed({
                    mainScope.launch {
                        val failedUrl = webView.url ?: domainRepo.currentDomain()
                        withContext(Dispatchers.IO) {
                            domainRepo.markDomainFailed(failedUrl)
                            domainRepo.refreshOnline()
                        }
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
        handler.removeCallbacks(loadTimeoutRunnable)
        val recoverable = statusCode == 403 || statusCode == 503 || statusCode == 429 ||
            statusCode in 520..527
        if (recoverable && loadRetriesLeft > 0) {
            loadRetriesLeft--
            recoverFromBlock("HTTP $statusCode")
            return
        }
        if (recoverable) {
            showToast(getString(R.string.domain_blocked_hint))
        }
        showLoading(false)
    }

    override fun onRenderGone() {
        rendererRecoveryUrl = webView.url
        cinemaMode = false
        recreate()
    }

    // -- Cinema Mode ----------------------------------------------------------

    private fun onVideoStateChanged(active: Boolean) {
        if (active) {
            val wasOff = !cinemaMode
            videoOffGeneration++
            if (wasOff) {
                cinemaMode = true
                handler.removeCallbacks(cursorAutoHideRunnable)
                handler.postDelayed(cursorAutoHideRunnable, CURSOR_HIDE_AFTER_MS)
                if (isTouchDevice) {
                    phoneMenuButton.visibility = View.GONE
                } else {
                    sidebarCtl.setCinemaHidden(true)
                    (webView.layoutParams as? FrameLayout.LayoutParams)?.marginStart = 0
                }
                if (pendingResume != null) pushPendingResumeSeek()
            }
        } else {
            val gen = ++videoOffGeneration
            handler.postDelayed({
                if (gen == videoOffGeneration && cinemaMode) {
                    cinemaMode = false
                    handler.removeCallbacks(cursorAutoHideRunnable)
                    cursorCtl.hide()
                    if (isTouchDevice) {
                        phoneMenuButton.visibility = View.VISIBLE
                    } else {
                        (webView.layoutParams as? FrameLayout.LayoutParams)?.marginStart =
                            resources.getDimensionPixelSize(R.dimen.sctv_rail_collapsed_width)
                        sidebarCtl.setCinemaHidden(false)
                    }
                }
            }, VIDEO_OFF_CONFIRM_MS)
        }
    }

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

    // -- Progress & Page Info -------------------------------------------------

    private fun onPageInfoChanged(json: String) {
        val info = runCatching { pageJson.decodeFromString<PageInfo>(json) }.getOrNull() ?: return
        if (info.titleId == 0) return
        currentPageInfo = info

        pendingResume?.let { (tid, _) -> if (tid != info.titleId) pendingResume = null }

        info.poster?.takeIf { it.isNotBlank() }
            ?.let { PosterCache.put(this, info.titleId, it) }

        val url = webView.url ?: return
        val poster = info.poster?.takeIf { it.isNotBlank() } ?: PosterCache.get(this, info.titleId)
        when {
            info.isWatchPage -> history.add(url, info.displayLabel(), poster)
            info.isTitlePage -> history.add(url, info.name, poster)
        }

        val p = progress.get(info.titleId)
        if (p != null && p.isResumeable() && pendingResume == null &&
            resumeChipShownFor != info.titleId &&
            (info.isTitlePage || info.isWatchPage)
        ) {
            resumeChipShownFor = info.titleId
            val tag = p.seasonEpisodeTag()
            resumeChip.text = getString(R.string.resume_chip_prefix) + " " +
                p.positionLabel() + (if (tag.isNotEmpty()) " ($tag)" else "")
            resumeChip.visibility = View.VISIBLE
            resumeChip.bringToFront()
        } else if (p == null || pendingResume != null) {
            resumeChip.visibility = View.GONE
        }
    }

    private fun onVideoProgress(posSec: Int, durSec: Int) {
        lastVideoPos = posSec
        lastVideoDur = durSec
        if (webView.url?.contains("/watch/") != true) {
            hideAutoNext()
            hideOutro()
            return
        }

        pendingResume?.let { (tid, target) ->
            if (tid == currentPageInfo?.titleId && kotlin.math.abs(posSec - target) < 20) {
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
                url = SiteUrl.relativeOf(webView.url) ?: "",
                poster = info.poster?.takeIf { it.isNotBlank() } ?: PosterCache.get(this, info.titleId),
                updatedAt = System.currentTimeMillis()
            )
        )

        maybeShowNextEpisodeOrOutro(posSec, durSec)
    }

    // -- Next Episode & Outro Chips -------------------------------------------

    private var nextEpisodeChip: TextView? = null
    private var nextEpisodeCancel: ImageView? = null
    private var outroChip: TextView? = null
    private var autoNextTick: Runnable? = null
    private var autoNextShownFor: Int = 0

    private fun maybeShowNextEpisodeOrOutro(posSec: Int, durSec: Int) {
        val info = currentPageInfo ?: return
        if (!info.isWatchPage || durSec < 120) {
            hideAutoNext()
            hideOutro()
            return
        }
        if (nextEpisodeChip != null || outroChip != null) return
        val remaining = durSec - posSec
        if (info.nextEpisodeId != null && remaining <= NEXT_EPISODE_WINDOW_SEC) {
            hideOutro()
            val key = info.titleId + (info.nextEpisodeId ?: 0)
            if (autoNextShownFor == key) return
            autoNextShownFor = key
            showAutoNextChip(info)
        } else if (info.nextEpisodeId == null && remaining <= OUTRO_WINDOW_SEC) {
            hideAutoNext()
            if (autoNextShownFor == info.titleId) return
            autoNextShownFor = info.titleId
            showOutroChip(durSec)
        } else {
            hideAutoNext()
            hideOutro()
        }
    }

    private fun makeChip(text: String, bottomDp: Int, onClick: View.OnClickListener): TextView {
        val content = findViewById<FrameLayout>(android.R.id.content)
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            this.text = text
            setTextColor(0xFFF5F7FA.toInt())
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setBackgroundResource(R.drawable.bg_sctv_chip)
            val padH = (18 * density).toInt()
            val padV = (10 * density).toInt()
            setPadding(padH, padV, padH, padV)
            elevation = 10f * density
            isClickable = true
            isFocusable = true
            setOnClickListener(onClick)
            content.addView(
                this,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.BOTTOM or android.view.Gravity.END
                ).apply {
                    bottomMargin = (bottomDp * density).toInt()
                    marginEnd = (24 * density).toInt()
                }
            )
        }
    }

    private fun showAutoNextChip(info: PageInfo) {
        hideAutoNext()
        val content = findViewById<FrameLayout>(android.R.id.content)
        val density = resources.displayMetrics.density
        val label = getString(R.string.next_episode_chip) +
            (info.nextEpisodeTag().takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: "")

        val chip = makeChip("", 96) { hideAutoNext(); goNextEpisode(info) }
        nextEpisodeChip = chip

        val cancelBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            scaleType = ImageView.ScaleType.CENTER
            setBackgroundResource(R.drawable.bg_sctv_chip)
            elevation = 10f * density
            isClickable = true
            isFocusable = true
            setOnClickListener { hideAutoNext() }
        }
        content.addView(
            cancelBtn,
            FrameLayout.LayoutParams(
                (38 * density).toInt(), (38 * density).toInt(),
                android.view.Gravity.BOTTOM or android.view.Gravity.END
            ).apply {
                bottomMargin = (148 * density).toInt()
                marginEnd = (24 * density).toInt()
            }
        )
        nextEpisodeCancel = cancelBtn

        var seconds = 10
        val tick = object : Runnable {
            override fun run() {
                if (nextEpisodeChip !== chip) return
                if (seconds <= 0) {
                    hideAutoNext()
                    goNextEpisode(info)
                    return
                }
                chip.text = "$label · ${seconds}s"
                seconds--
                autoNextTick = this
                handler.postDelayed(this, 1000)
            }
        }
        autoNextTick = tick
        handler.post(tick)
    }

    private fun goNextEpisode(info: PageInfo) {
        val eid = info.nextEpisodeId ?: return
        val link = SiteUrl.resolve("/it/watch/${info.titleId}?e=$eid", domainRepo.currentDomain()) ?: return
        hideAutoNext()
        progress.get(info.titleId)?.let {
            progress.save(it.copy(positionSec = it.durationSec, updatedAt = System.currentTimeMillis()))
        }
        webView.loadUrl(link)
    }

    private fun hideAutoNext() {
        autoNextTick?.let { handler.removeCallbacks(it) }
        autoNextTick = null
        nextEpisodeChip?.let { (it.parent as? FrameLayout)?.removeView(it) }
        nextEpisodeChip = null
        nextEpisodeCancel?.let { (it.parent as? FrameLayout)?.removeView(it) }
        nextEpisodeCancel = null
    }

    private fun showOutroChip(durSec: Int) {
        if (outroChip?.visibility == View.VISIBLE) return
        outroChip = makeChip(getString(R.string.skip_outro_chip), 96) {
            hideOutro()
            jsBridge.requestSeekTo(durSec - 15)
        }.also { it.bringToFront() }
    }

    private fun hideOutro() {
        outroChip?.let { (it.parent as? FrameLayout)?.removeView(it) }
        outroChip = null
    }

    // -- Dialogs: Search, Resume, Favorites, History --------------------------

    private fun showSearchDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_search, null)
        val dlg = android.app.AlertDialog.Builder(this, R.style.SCOverlay)
            .setView(view)
            .create()
        val input = view.findViewById<EditText>(R.id.searchInput)

        fun executeSearch() {
            val query = input.text?.toString()?.trim().orEmpty()
            dlg.dismiss()
            if (query.isNotBlank()) {
                val base = domainRepo.currentDomain().trimEnd('/')
                val encoded = Uri.encode(query)
                webView.loadUrl("$base/it/archive?search=$encoded")
            }
        }

        view.findViewById<Button>(R.id.searchCancel).setOnClickListener { dlg.dismiss() }
        view.findViewById<Button>(R.id.searchSubmit).setOnClickListener { executeSearch() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                executeSearch()
                true
            } else {
                false
            }
        }

        activeDialog = dlg
        dlg.setOnDismissListener { activeDialog = null }
        dlg.show()
        dlg.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        input.requestFocus()
    }

    private fun startResume(p: WatchProgress) {
        val link = p.resolveUrl(domainRepo.currentDomain())
        if (link != null) {
            pendingResume = p.titleId to p.positionSec
            resumeChip.visibility = View.GONE
            resumeChipShownFor = p.titleId
            webView.loadUrl(link)
        } else {
            showToast(getString(R.string.resume_unavailable))
        }
    }

    private fun onResumeChipClicked() {
        val info = currentPageInfo ?: return
        val p = progress.get(info.titleId) ?: return
        startResume(p)
    }

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
                webView.evaluateJavascript("(window.__scvContinuePlay&&window.__scvContinuePlay());", null)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        activeDialog = dlg
        dlg.setOnDismissListener { activeDialog = null }
        dlg.show()
        dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.requestFocus()
    }

    private fun pushPendingResumeSeek() {
        val (tid, target) = pendingResume ?: return
        if (tid != currentPageInfo?.titleId) return
        jsBridge.requestSeekTo(target)
        handler.postDelayed({ if (pendingResume != null) jsBridge.requestSeekTo(target) }, 3500)
        handler.postDelayed({ if (pendingResume != null) jsBridge.requestSeekTo(target) }, 8000)
    }

    private fun showResumeDialog() {
        val entries = progress.resumeable()
        val items = entries.map { p ->
            val tag = p.seasonEpisodeTag()
            val pct = if (p.durationSec > 0) ((p.positionSec * 100) / p.durationSec).coerceIn(1, 99) else 0
            OverlayItem(
                title = p.name + (if (tag.isNotEmpty()) " · $tag" else ""),
                subtitle = "Riprendi da ${p.positionLabel()}",
                tag = p.titleId.toString(),
                imageUrl = p.poster?.takeIf { it.isNotBlank() } ?: PosterCache.get(this, p.titleId),
                progressPercent = pct
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

    private fun showFavorites() {
        val items = favorites.all().map {
            val pid = SiteUrl.titleIdOf(it.url)
            val poster = it.poster?.takeIf { it.isNotBlank() } ?: pid?.let { tid -> PosterCache.get(this, tid) }
            OverlayItem(title = it.title, subtitle = "", tag = it.url, imageUrl = poster)
        }
        OverlayDialog(
            context = this,
            title = getString(R.string.favorites_title),
            items = items,
            emptyMessage = getString(R.string.empty_favorites),
            onItemClick = { item ->
                SiteUrl.resolve(item.tag, domainRepo.currentDomain())?.let { webView.loadUrl(it) }
            },
            onAddCurrent = {
                val url = webView.url ?: return@OverlayDialog
                val title = currentPageInfo?.takeIf { it.titleId != 0 }?.displayLabel()
                    ?: (webView.title ?: url)
                val poster = currentPageInfo?.poster ?: currentPageInfo?.titleId?.let { PosterCache.get(this, it) }
                favorites.add(url, title, poster)
                showToast("Aggiunto ai preferiti")
            },
            onActiveChange = { d -> activeDialog = d }
        ).show()
    }

    private fun showHistory() {
        val items = history.all().map {
            val pid = SiteUrl.titleIdOf(it.url)
            val poster = it.poster?.takeIf { it.isNotBlank() } ?: pid?.let { tid -> PosterCache.get(this, tid) }
            OverlayItem(title = it.title, subtitle = "", tag = it.url, imageUrl = poster)
        }
        OverlayDialog(
            context = this,
            title = getString(R.string.history_title),
            items = items,
            emptyMessage = getString(R.string.empty_history),
            onItemClick = { item ->
                SiteUrl.resolve(item.tag, domainRepo.currentDomain())?.let { webView.loadUrl(it) }
            },
            onAddCurrent = null,
            onActiveChange = { d -> activeDialog = d }
        ).show()
    }

    private fun showPhoneMenu() {
        val view = layoutInflater.inflate(R.layout.dialog_phone_menu, null)
        val dlg = android.app.AlertDialog.Builder(this, R.style.SCOverlay)
            .setView(view)
            .create()
        activeDialog = dlg
        dlg.setOnDismissListener { activeDialog = null }

        view.findViewById<View>(R.id.phoneMenuHome).setOnClickListener {
            dlg.dismiss(); mainScope.launch { loadHome() }
        }
        view.findViewById<View>(R.id.phoneMenuFavorites).setOnClickListener {
            dlg.dismiss(); showFavorites()
        }
        view.findViewById<View>(R.id.phoneMenuHistory).setOnClickListener {
            dlg.dismiss(); showHistory()
        }
        view.findViewById<View>(R.id.phoneMenuResume).setOnClickListener {
            dlg.dismiss(); showResumeDialog()
        }
        view.findViewById<View>(R.id.phoneMenuSettings).setOnClickListener {
            dlg.dismiss(); SettingsDialog(this, onSaved = { _ -> }).show()
        }
        view.findViewById<View>(R.id.phoneMenuExit).setOnClickListener {
            dlg.dismiss(); finishAffinity()
        }
        dlg.show()
        dlg.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.82).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    private fun onMainLoadTimeout() {
        showLoading(false)
        if (!autoReloadUsed) {
            autoReloadUsed = true
            showToast(getString(R.string.load_timeout_retry))
            handler.postDelayed({ webView.reload() }, RETRY_DELAY_MS)
        } else {
            showToast(getString(R.string.load_failed_hint))
        }
    }

    // -- D-Pad & Remote Key Handling ------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val action = event.action
        val keyCode = event.keyCode

        if (keyCode == KeyEvent.KEYCODE_MENU && action == KeyEvent.ACTION_DOWN) {
            if (!isTouchDevice) {
                if (sidebarCtl.isExpanded) {
                    sidebarCtl.collapse()
                    webView.requestFocus()
                } else {
                    sidebarCtl.focusSidebar()
                }
                return true
            }
        }

        if (keyCode == KeyEvent.KEYCODE_BACK && action == KeyEvent.ACTION_DOWN) {
            if (!isTouchDevice && sidebarCtl.isExpanded) {
                sidebarCtl.collapse()
                webView.requestFocus()
                return true
            }
            if (webView.canGoBack()) {
                webView.goBack()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        // Let Android's native D-pad focus engine handle modal dialogs and the expanded sidebar
        if (activeDialog != null || (!isTouchDevice && sidebarCtl.isExpanded)) {
            if (!isTouchDevice && sidebarCtl.isExpanded &&
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && action == KeyEvent.ACTION_DOWN
            ) {
                sidebarCtl.collapse()
                webView.requestFocus()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        if (action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && keyCode in DPAD_KEYS) {
            lastDpadAt = android.os.SystemClock.uptimeMillis()
        }

        if (cinemaMode && !cursorCtl.isActive()) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    cursorCtl.show()
                    if (action == KeyEvent.ACTION_DOWN) {
                        cursorCtl.onDirectionDown(keyCode, event.repeatCount)
                    } else if (action == KeyEvent.ACTION_UP) {
                        cursorCtl.onDirectionUp(keyCode)
                    }
                    return true
                }

                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
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
                    val dir = when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> "up"
                        KeyEvent.KEYCODE_DPAD_DOWN -> "down"
                        KeyEvent.KEYCODE_DPAD_LEFT -> "left"
                        else -> "right"
                    }
                    webView.evaluateJavascript(
                        "window.__sctvNav?__sctvNav.move('$dir'):'false'"
                    ) { result ->
                        // Bordo sinistro del sito → apre la sidebar.
                        if (dir == "left" && result?.contains("edge") == true) {
                            runOnUiThread {
                                if (!sidebarCtl.isExpanded) sidebarCtl.focusSidebar()
                            }
                        }
                    }
                }
                return true
            }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                if (action == KeyEvent.ACTION_DOWN) {
                    if (event.repeatCount == 0) {
                        okLongPressFired = false
                        handler.postDelayed(okLongPressRunnable, FAVORITES_HOLD_MS)
                    }
                } else if (action == KeyEvent.ACTION_UP) {
                    handler.removeCallbacks(okLongPressRunnable)
                    if (!okLongPressFired) {
                        webView.evaluateJavascript(
                            "window.__sctvNav?__sctvNav.activate():'false'", null
                        )
                    }
                }
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    jsBridge.requestPauseToggle()
                }
                return true
            }

            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    webView.flingScroll(0, -PAGE_SCROLL_FLING)
                }
                return true
            }

            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    if (cinemaMode) {
                        jsBridge.requestSkipIntro()
                    } else {
                        webView.flingScroll(0, PAGE_SCROLL_FLING)
                    }
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // -- Lifecycle ------------------------------------------------------------

    override fun onPause() {
        super.onPause()
        webView.onPause()
        cursorCtl.stopMovement()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
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
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            webView.clearCache(false)
        }
    }

    private fun showLoading(show: Boolean) {
        loadingOverlay.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showToast(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        private const val TAG = "MainActivity"
        @Volatile private var rendererRecoveryUrl: String? = null
        private const val FAVORITES_HOLD_MS = 750L
        private const val PAGE_SCROLL_FLING = 1800
        private const val NEXT_EPISODE_WINDOW_SEC = 90
        private const val OUTRO_WINDOW_SEC = 120
        private const val MAX_LOAD_RETRIES = 3
        private const val RETRY_DELAY_MS = 3000L
        private const val MAX_BLOCK_RECOVERIES = 4
        private const val LOAD_TIMEOUT_MS = 25_000L
        private const val ERROR_NAME_NOT_RESOLVED = -2
        private const val ERROR_CONNECT_FAILED = -6
        private const val CURSOR_HIDE_AFTER_MS = 4000L
        private const val VIDEO_OFF_CONFIRM_MS = 2500L

        private val DPAD_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER
        )
    }
}
