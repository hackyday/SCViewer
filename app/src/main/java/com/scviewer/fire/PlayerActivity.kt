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
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
import com.scviewer.fire.data.HistoryStore
import com.scviewer.fire.data.PageInfo
import com.scviewer.fire.data.PosterCache
import com.scviewer.fire.data.ProgressStore
import com.scviewer.fire.data.SiteUrl
import com.scviewer.fire.data.WatchProgress
import com.scviewer.fire.network.AdBlocker
import com.scviewer.fire.network.HlsRewriter
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
    /** Id del titolo dall'extra: fallback per i salvataggi quando il report
     *  Inertia della watch page non espone il titleId. */
    private var titleId = 0

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

    // -- Resume seek (M3) -------------------------------------------------------
    /** Posizione di ripresa (extra startPositionSec). Si attiva SOLO al primo
     *  onVideoState(active=true) — prima il video potrebbe non esistere — e
     *  converge in onProgress (±20s). Attivo il push immediato con retry a
     *  3.5s e 8s (schema pushPendingResumeSeek di MainActivity). */
    private var pendingSeek = 0
    private var resumeSeekArmed = false

    /** Info Inertia dell'ultima navigazione: serve ai salvataggi in onProgress. */
    private var pageInfo: PageInfo? = null

    private val resumeSeekRetry = Runnable {
        if (pendingSeek > 0 && resumeSeekArmed) jsBridge.requestSeekTo(pendingSeek)
    }

    private val adBlocker by lazy { AdBlocker.get(this) }

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
        titleId = intent.getIntExtra("titleId", 0)
        pendingSeek = intent.getIntExtra("startPositionSec", 0)
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
                    // Il video esiste: SOLO ora il resume seek può partire
                    // (prima requestSeekTo finirebbe consumata senza target).
                    if (active && !resumeSeekArmed) {
                        resumeSeekArmed = true
                        if (pendingSeek > 0) pushPendingResumeSeek()
                    }
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
                // Stesso blocco di SCWebViewClient (AdBlocker + HlsRewriter +
                // anti-popunder): il player vixcloud senza questo filtro perde
                // la variante FHD della master playlist e lascia passare ads.
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    if (request == null) return null
                    if (adBlocker.shouldBlock(request)) {
                        return adBlocker.blockedResponse()
                    }
                    HlsRewriter.intercept(request)?.let { return it }

                    val url = request.url
                    val path = (url.path ?: "").lowercase()
                    val query = (url.query ?: "").lowercase()
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
                // raw può già portare ?s= (riprese per stagione): usa il
                // separatore giusto, mai "?...?e=".
                val sep = if (raw.contains("?")) "&" else "?"
                return raw + sep + "e=" + it
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
        // Conservata per i salvataggi in onProgress (anche per report senza
        // titleId, che qui vanno comunque riconosciuti come watch page).
        pageInfo = info
        if (info.titleId == 0) return
        titleText = info.name
        titleView.text = info.name
        episodeText = if (info.isSeries && info.season != null && info.episode != null)
            "S${info.season}E${info.episode}" + (info.episodeName?.takeIf { it.isNotBlank() }
                ?.let { " · $it" } ?: "") else ""
        episodeView.text = episodeText
        nextEpisodeId = info.nextEpisodeId ?: 0
    }

    /** Schema pushPendingResumeSeek di MainActivity: push immediato del seek
     *  + retry a 3.5s e 8s (l'agent consuma la coda ogni 80ms, il TTL del
     *  comando è 12s). La conferma arriva da onProgress: convergenza ±20s →
     *  pendingSeek=0 e i retry pendenti diventano no-op. */
    private fun pushPendingResumeSeek() {
        val target = pendingSeek
        if (target <= 0 || !resumeSeekArmed) return
        jsBridge.requestSeekTo(target)
        handler.removeCallbacks(resumeSeekRetry)
        handler.postDelayed(resumeSeekRetry, 3500)
        handler.postDelayed(resumeSeekRetry, 8000)
        // Desistenza: se a 15s il seek non è MAI confluito (sito che ignora il
        // comando), si desiste e si torna a salvare la posizione reale del
        // video invece di restare appesi sul target irraggiungibile.
        handler.postDelayed({
            if (pendingSeek > 0) {
                Log.w("Player", "resume seek non confluito a $target — desisto")
                pendingSeek = 0
            }
        }, RESUME_SEEK_DESIST_MS)
    }

    private fun onProgress(pos: Int, dur: Int) {
        posSec = pos; durSec = dur
        scrubber.max = dur.coerceAtLeast(1)
        scrubber.progress = pos
        timeCur.text = fmt(pos)
        timeTot.text = fmt(dur)

        // -- Resume seek: convergenza (±20s) o ripush -------------------------
        if (pendingSeek > 0) {
            if (kotlin.math.abs(pos - pendingSeek) < 20) {
                pendingSeek = 0                          // già a punto
            } else if (pos > pendingSeek + 20) {
                pendingSeek = 0                          // oltre (es. seek utente)
            } else if (pos < pendingSeek - 20) {
                // Video vivo ma ancora prima del punto salvato: ripusha e
                // ritenta a 3.5s e 8s (schema di MainActivity).
                jsBridge.requestSeekTo(pendingSeek)
                handler.removeCallbacks(resumeSeekRetry)
                handler.postDelayed(resumeSeekRetry, 3500)
                handler.postDelayed(resumeSeekRetry, 8000)
            }
        }

        // Prossimo episodio: card negli ultimi 90 s (solo serie).
        val showNext = nextEpisodeId > 0 && dur > 0 && dur - pos < 90 && pos > dur / 2
        nextCard.visibility = if (showNext) View.VISIBLE else View.GONE
        if (showNext) showControls()

        // -- Salvataggi (M3): idempotenti con la modalità sito -----------------
        // Solo da video attivo su watch page; finché il resume seek non è
        // confluito NON si salva: la posizione pre-seek (≈0) non deve
        // regredire il punto di ripresa già in ProgressStore (save ignora
        // comunque gli snapshot più vecchi, ma qui updatedAt sarebbe fresco).
        val info = pageInfo
        if (info == null || !info.isWatchPage || dur <= 60 || pendingSeek > 0) return
        val tid = if (info.titleId != 0) info.titleId else titleId
        if (tid <= 0) return
        val url = web.url ?: return
        val poster = info.poster?.takeIf { it.isNotBlank() } ?: PosterCache.get(this, tid)
        ProgressStore.get(this).save(
            WatchProgress(
                titleId = tid,
                name = info.name.ifBlank { titleText },
                slug = info.slug,
                type = info.type,
                season = info.season,
                episode = info.episode,
                episodeName = info.episodeName,
                positionSec = pos,
                durationSec = dur,
                // URL RELATIVO (path+query): il dominio corrente non resta
                // congelato nel record — stessa convenzione della modalità sito.
                url = SiteUrl.relativeOf(url) ?: "",
                poster = poster,
                updatedAt = System.currentTimeMillis()
            )
        )
        HistoryStore.get(this).add(url, info.displayLabel(), poster)
        info.poster?.takeIf { it.isNotBlank() }?.let { PosterCache.put(this, tid, it) }
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
        // l'ordine di fuoco cambia con la visibilità: riallinea l'indice
        val order = focusOrder
        if (order.isEmpty()) focusIdx = 0
        else focusIdx = focusIdx.coerceIn(0, order.size - 1)
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

    private companion object {
        /** Oltre questo timeout il resume seek si considera non eseguibile. */
        private const val RESUME_SEEK_DESIST_MS = 15_000L
    }
}
