package com.scviewer.fire

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.ProgressBar
import android.widget.TextView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.scviewer.fire.data.PageInfo
import com.scviewer.fire.web.JsInterface

/**
 * Schermata di streaming dell'interfaccia SCtv: il player reale del sito
 * (watch URL) gira in un WebView a tutto schermo; sopra c'è un overlay di
 * controlli nativi in stile design (Back to Browse, titolo/episodio,
 * scrubber, play/pausa, ±10s, Skip Intro, card Prossimo episodio) navigabile
 * col D-pad e niente mouse. I report di riproduzione arrivano da
 * video_agent.js (iniettato a document start) e i comandi passano dalla
 * coda di JsInterface — lo stesso meccanismo provato di MainActivity.
 */
class PlayerActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var jsBridge: JsInterface
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var backBtn: TextView
    private lateinit var titleView: TextView
    private lateinit var episodeView: TextView
    private lateinit var skipBtn: TextView
    private lateinit var nextCard: View
    private lateinit var nextPlay: TextView
    private lateinit var nextCancel: TextView
    private lateinit var playBtn: TextView
    private lateinit var rewBtn: TextView
    private lateinit var ffwBtn: TextView
    private lateinit var scrubber: ProgressBar
    private lateinit var timeCur: TextView
    private lateinit var timeTot: TextView

    private var controlsVisible = true
    private var playing = false
    private var skipAvailable = false
    private var posSec = 0
    private var durSec = 0
    private var nextEpisodeId = 0
    private var watchUrl = ""
    private var titleText = ""
    private var episodeText = ""

    /** Ordine di fuoco tra i controlli visibili. */
    private val focusOrder: List<View>
        get() = listOfNotNull(
            backBtn,
            playBtn, rewBtn, ffwBtn,
            skipBtn.takeIf { it.visibility == View.VISIBLE },
            nextPlay.takeIf { nextCard.visibility == View.VISIBLE },
            nextCancel.takeIf { nextCard.visibility == View.VISIBLE }
        )
    private var focusIdx = 0

    private val hideRunnable = Runnable { if (playing) hideControls() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stage2Started = false
    private var resolvedUrl = ""

    private fun DomainHome(): String =
        com.scviewer.fire.data.DomainRepository.get(this).currentDomain()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)

        web = findViewById(R.id.playerWeb)
        topBar = findViewById(R.id.playerTopBar)
        bottomBar = findViewById(R.id.playerBottomBar)
        backBtn = findViewById(R.id.playerBack)
        titleView = findViewById(R.id.playerTitle)
        episodeView = findViewById(R.id.playerEpisode)
        skipBtn = findViewById(R.id.playerSkipIntro)
        nextCard = findViewById(R.id.playerNextCard)
        nextPlay = findViewById(R.id.playerNextPlay)
        nextCancel = findViewById(R.id.playerNextCancel)
        playBtn = findViewById(R.id.playerPlay)
        rewBtn = findViewById(R.id.playerRew)
        ffwBtn = findViewById(R.id.playerFfw)
        scrubber = findViewById(R.id.playerScrubber)
        timeCur = findViewById(R.id.playerTimeCur)
        timeTot = findViewById(R.id.playerTimeTot)

        titleText = intent.getStringExtra("title") ?: ""
        watchUrl = intent.getStringExtra("url") ?: ""
        titleView.text = titleText
        backBtn.setOnClickListener { finish() }
        playBtn.setOnClickListener {
            jsBridge.requestPauseToggle()
            playing = !playing
            updatePlayIcon()
            scheduleHide()
        }
        rewBtn.setOnClickListener { seekBy(-10) }
        ffwBtn.setOnClickListener { seekBy(10) }
        skipBtn.setOnClickListener {
            skipAvailable = false
            skipBtn.visibility = View.GONE
            jsBridge.requestSkipIntro()
        }
        nextPlay.setOnClickListener {
            if (nextEpisodeId > 0) {
                val domain = com.scviewer.fire.data.DomainRepository.get(this).currentDomain()
                web.loadUrl(domain.trimEnd('/') + "/it/watch/" +
                    (watchUrl.substringAfterLast("/watch/").substringBefore("?")) +
                    "?e=" + nextEpisodeId)
                nextCard.visibility = View.GONE
                showControls()
            }
        }
        nextCancel.setOnClickListener { nextCard.visibility = View.GONE }

        // WebView: player reale del sito + agent iniettato a document start.
        @SuppressLint("SetJavaScriptEnabled")
        fun setup() {
            web.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
            }
            jsBridge = JsInterface(
                onSkipIntro = { available -> runOnUiThread {
                    skipAvailable = available
                    val onWatch = web.url?.contains("/watch/") == true
                    skipBtn.visibility =
                        if (available && onWatch) View.VISIBLE else View.GONE
                    if (available) showControls()
                } },
                onAdBlockHit = { },
                onVideoState = { active -> runOnUiThread {
                    playing = active
                    updatePlayIcon()
                    scheduleHide()
                } },
                onPageInfo = { json -> runOnUiThread { onPageInfo(json) } },
                onProgress = { pos, dur -> runOnUiThread { onProgress(pos, dur) } },
                onPlayClicked = { "none" },
                onPosterFound = { _, _ -> },
                onCloudflareBlock = { }
            )
            web.addJavascriptInterface(jsBridge, "AndroidJs")
            web.webChromeClient = WebChromeClient()
            web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Stage 2: la home ha stabilito cookie/JS → vai alla watch.
                if (!stage2Started && url != null && !url.contains("/watch/") &&
                    url.startsWith("http")) {
                    stage2Started = true
                    scope.launch {
                        resolvedUrl = withContext(Dispatchers.IO) { resolveWatchUrl(watchUrl) }
                        Log.i("Player", "load stage2: $resolvedUrl")
                        runOnUiThread { web.loadUrl(resolvedUrl) }
                    }
                }
            }
        }
            try {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    val agent = assets.open("video_agent.js").bufferedReader().use { it.readText() }
                    val inject = assets.open("page_inject.js").bufferedReader().use { it.readText() }
                    WebViewCompat.addDocumentStartJavaScript(web, agent + "\n" + inject, setOf("*"))
                }
            } catch (_: Throwable) { }
        }
        setup()
        showControls()
        // Carico prima la HOME del sito: stabilisce cookie/sessione JS —
        // la watch page a cookie freddi viene rimandata alla pagina titolo.
        web.loadUrl(DomainHome())
        stage2Started = false
    }

    private val pageJson = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Serie: il watch senza ?e= viene rimandato (302) alla pagina titolo
     *  da alcune reti. Risolvi: se il fetch atterra su Titles/Title, prendi
     *  la prima stagione e carica /it/watch/<id>?e=<prima puntata>. */
    private fun resolveWatchUrl(raw: String): String {
        if (raw.contains("e=") || raw.startsWith("file:")) return raw
        return runCatching {
            val props = com.scviewer.fire.data.SiteCatalog.fetchProps(raw, "") ?: return raw
            props.optJSONObject("episode")?.optInt("id")?.takeIf { it > 0 }?.let {
                Log.i("Player", "resolveWatchUrl: episodio diretto $it")
                return raw + "?e=" + it
            }
            // Redirect a Titles/Title: è una serie senza stagione →
            // aggiungi ?s=<numero> (formato collaudato da Riprendi).
            val t = props.optJSONObject("title") ?: return raw
            val firstSeason = t.optJSONArray("seasons")?.optJSONObject(0)?.optInt("number")
                ?: return raw
            Log.i("Player", "resolveWatchUrl: serie → ?s=$firstSeason")
            raw + "?s=" + firstSeason
        }.getOrDefault(raw)
    }

    private fun onPageInfo(json: String) {
        val info = runCatching {
            pageJson.decodeFromString<PageInfo>(json)
        }.getOrNull() ?: return
        if (info.titleId == 0) return
        titleText = info.name
        titleView.text = info.name
        episodeText = if (info.isSeries && info.season != null && info.episode != null)
            "S${info.season}E${info.episode}" + (info.episodeName?.takeIf { it.isNotBlank() }
                ?.let { " · $it" } ?: "") else ""
        episodeView.text = episodeText
        nextEpisodeId = info.nextEpisodeId ?: 0
    }

    private fun onProgress(pos: Int, dur: Int) {
        posSec = pos; durSec = dur
        scrubber.max = dur.coerceAtLeast(1)
        scrubber.progress = pos
        timeCur.text = fmt(pos)
        timeTot.text = fmt(dur)
        // Prossimo episodio: card negli ultimi 90 s (solo serie).
        val showNext = nextEpisodeId > 0 && dur > 0 && dur - pos < 90 && pos > dur / 2
        nextCard.visibility = if (showNext) View.VISIBLE else View.GONE
        if (showNext) showControls()
    }

    private fun fmt(sec: Int): String =
        "%d:%02d".format(sec / 60, sec % 60)

    private fun seekBy(delta: Int) {
        jsBridge.requestSeekTo((posSec + delta).coerceIn(0, (durSec - 1).coerceAtLeast(0)))
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideRunnable)
        if (playing) handler.postDelayed(hideRunnable, 4000)
    }

    private fun updatePlayIcon() {
        playBtn.text = if (playing) "❚❚" else "▶"
    }

    // -- Controlli: visibilità + fuoco ---------------------------------------

    private fun showControls() {
        controlsVisible = true
        topBar.visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE
        if (skipAvailable) skipBtn.visibility = View.VISIBLE
        handler.removeCallbacks(hideRunnable)
        if (playing) handler.postDelayed(hideRunnable, 4000)
    }

    private fun hideControls() {
        controlsVisible = false
        topBar.visibility = View.GONE
        bottomBar.visibility = View.GONE
        if (!skipAvailable) skipBtn.visibility = View.GONE
        nextCard.visibility = View.GONE
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        // INDIETRO: controlli visibili → nascondi; nascosti → esci.
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (controlsVisible) hideControls() else finish()
            return true
        }

        // Qualsiasi altro D-pad: mostra i controlli e muovi il fuoco.
        showControls()
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> moveFocus(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveFocus(1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
                focusOrder.getOrNull(focusIdx)?.performClick()
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    private fun moveFocus(delta: Int) {
        val list = focusOrder
        if (list.isEmpty()) return
        focusIdx = ((focusIdx + delta) % list.size + list.size) % list.size
        list.forEach { it.background = resources.getDrawable(R.drawable.player_pill, theme) }
        // l'anello bianco via player_focus: applicalo al selezionato
        list[focusIdx].background = resources.getDrawable(R.drawable.player_focus, theme)
        // eccezione: i bottoni bianchi (play/skip/next-play) mantengono il fondo bianco
        // e mostrano il ring via outline — gestito dal layer-list del drawable.
        list[focusIdx].requestFocus()
    }
}
