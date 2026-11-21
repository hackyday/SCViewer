package com.scviewer.fire

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.scviewer.fire.data.DEFAULT_PROFILE_ID
import com.scviewer.fire.data.HistoryStore
import com.scviewer.fire.data.PageInfo
import com.scviewer.fire.data.PosterCache
import com.scviewer.fire.data.ProgressStore
import com.scviewer.fire.data.SettingsStore
import com.scviewer.fire.data.SiteUrl
import com.scviewer.fire.data.WatchProgress
import com.scviewer.fire.network.AdBlocker
import com.scviewer.fire.network.HlsRewriter
import com.scviewer.fire.web.JsInterface
import com.scviewer.fire.web.SCWebViewClient
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Schermata di streaming dell'interfaccia SCtv: il player reale del sito
 * (watch URL) gira in un WebView a tutto schermo; sopra c'è un overlay di
 * controlli nativi in stile design (Back to Browse, titolo/episodio,
 * scrubber, play/pausa, ±10s, Skip Intro, card Prossimo episodio) navigabile
 * col D-pad e niente mouse.
 *
 * DUE MODALITÀ (PLAYER V2):
 *  - DIRECT (extra "embedUrl" presente): l'embed vixcloud risolto da
 *    SctvActivity (OkHttp, token monouso ~10s, +nogui=1) È il main frame —
 *    nessun two-stage, nessuna watch page, nessun flash. Il ponte con JW è
 *    player_bridge.js (document start): eventi → [onPlayerEvent], comandi →
 *    window.__sctvPlayer (API diretta, bypass della coda JsInterface). La
 *    copertura nera si toglie solo all'evento 'ready' del bridge.
 *    Tasti media del telecomando (solo direct): PLAY/PAUSE = play/pausa;
 *    REWIND/FAST_FORWARD singoli = seek ±10s, TENUTI = SCRUB MODE (video in
 *    pausa, preview del target: OK = seek+play, BACK = annulla). I D-pad
 *    muovono SEMPRE il fuoco tra i controlli: la timeline si comanda SOLO
 *    coi tasti media e lo scrubber non è fuocabile (fuoco intrappolato).
 *  - LEGACY (modalità sito, extra "url" soltanto): two-stage home→watch con
 *    video_agent.js + page_inject.js, coda comandi di JsInterface e fallback
 *    embed M4b — flusso invariato.
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
    private lateinit var langBtn: TextView
    private lateinit var scrubber: ProgressBar
    private lateinit var timeCur: TextView
    private lateinit var timeTot: TextView
    /** Indicatore SCRUB MODE: box centrato con il tempo target grande e
     *  leggibile ("▶▶ 12:45") + hint dei tasti (OK conferma · BACK annulla). */
    private lateinit var scrubBox: View
    private lateinit var scrubTime: TextView
    /** Copertura nera sopra la WebView: copre lo stage1 (home del sito per i
     *  cookie) e qualunque pagina intermedia finché il contenuto vero non
     *  inizia a caricarsi — elimina il flash dell'interfaccia del sito. */
    private lateinit var blackout: View

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

    /** Profilo SCtv proprietario dei record (ProgressStore/HistoryStore):
     *  extra di SctvActivity — la modalità sito non passa l'extra e usa il
     *  bucket di default. */
    private var profileId = DEFAULT_PROFILE_ID

    /** Ordine di fuoco tra i controlli visibili. Lo scrubber NON fa parte
     *  dell'anello (né direct né legacy): fuocarlo intrappolava il D-pad —
     *  LEFT/RIGHT sullo scrubber cercavano il seek invece di muovere il fuoco
     *  e OK non aveva effetto. FIX: la timeline si comanda SOLO coi tasti
     *  media (REW/FFWD singoli ±10s, tenuti = scrub mode); l'anello resta
     *  backBtn → play/rew/ffw/lang (adiacenti: pausa, ±10 e lingua sono
     *  sempre a max 1-2 passi con il wrap dell'anello). */
    private val focusOrder: List<View>
        get() = listOfNotNull(
            backBtn,
            playBtn, rewBtn, ffwBtn, langBtn,
            skipBtn.takeIf { it.visibility == View.VISIBLE },
            nextPlay.takeIf { nextCard.visibility == View.VISIBLE },
            nextCancel.takeIf { nextCard.visibility == View.VISIBLE }
        )
    private var focusIdx = 0

    private val hideRunnable = Runnable { if (playing) hideControls() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stage2Started = false
    private var resolvedUrl = ""

    // -- Sessione + fallback embed (M4b) ---------------------------------------
    /** Cookie della WebView catturati a fine stage1: li riusano le fetch
     *  OkHttp (props.embedUrl / wrapper) per condividere la sessione del
     *  sito — senza cookie la watch viene rimandata alla pagina titolo. */
    private var cookieHeader: String? = null
    /** Fallback embed già tentato: single-shot, mai in loop. */
    private var embedFallbackTried = false
    /** Watch URL RELATIVO dell'episodio in riproduzione, per i salvataggi:
     *  quando il main frame è il wrapper /it/iframe o l'embed vixcloud,
     *  web.url NON è una pagina watch e non va salvata. */
    private var saveUrlRel: String? = null
    /** Lingua audio corrente (parametro lang dell'embed vixcloud). */
    private var currentLang = "it"

    // -- Direct-embed mode (PLAYER V2) ------------------------------------------
    /** TRUE quando SctvActivity ha già risolto l'embed vixcloud (extra
     *  "embedUrl"): decide document-start script, loadUrl, wiring comandi,
     *  evento ready, progresso e dialog lingua. */
    private var directEmbed = false
    /** Embed vixcloud con &nogui=1 (token monouso ~10s): caricato SUBITO in
     *  onCreate con il Referer del sito (403 senza). */
    private var embedUrl = ""
    /** Metadati del titolo per i record (ProgressStore/HistoryStore): li
     *  passa SctvActivity dal detail cache — in direct mode non esiste la
     *  watch page che in legacy li esponeva via onPageInfo. */
    private var posterUrl = ""
    private var recordSlug = ""
    private var recordType = ""
    private var episodeName = ""
    private var recordSeason = 1
    private var recordEpisode = 1
    private var nextEpisodeSeason = 0
    private var nextEpisodeNumber = 0
    /** Throttle UI per gli eventi 'time' di JW (arrivano più volte al
     *  secondo): barra/skip/next card a ≥1s, salvataggi a ≥10s. */
    private var lastTimeUiAt = 0L
    private var lastSaveAt = 0L
    /** Cancel sulla card prossimo episodio: senza flag, l'evento 'time'
     *  successivo (≤1s) la riaprirebbe subito. */
    private var nextCardDismissed = false
    /** Toast di errore JW: una sola volta per riproduzione. */
    private var directErrorShown = false
    /** HistoryStore: una sola voce per riproduzione (al primo ready). */
    private var historyLogged = false

    // -- SCRUB MODE (direct): scrub della timeline coi tasti media -------------
    /** TRUE mentre l'utente sta sistemando la posizione sulla timeline (REW/
     *  FFWD tenuti): video in pausa, preview del target senza seek HLS. In
     *  questo stato i D-pad NON muovono il fuoco (tutta l'attenzione è la
     *  timeline), OK committa il seek, BACK annulla. */
    private var scrubActive = false
    /** Posizione target della preview (secondi), sempre dentro [0, dur]. */
    private var scrubTargetSec = 0
    /** Posizione al PRIMO DOWN del gesto che ha aperto lo scrub (base per il
     *  BACK: cancel ripristina questa, così hold+BACK = net-zero movement). */
    private var scrubBaseSec = -1
    /** Incrementi applicati nel gesto di hold corrente: guida l'accelerazione
     *  (10s → 30s → 60s). Si azzera a ogni nuova pressione (repeatCount==0). */
    private var scrubSteps = 0
    /** Direzione dell'ultimo aggiustamento: simbolo dell'indicatore (▶▶/◀◀). */
    private var scrubDir = 1
    /** Stato play/pause CHE L'UTENTE VEDEVA all'attivazione dello scrub: BACK
     *  lo ripristina (play se era in play — enterScrub l'aveva messo in pausa;
     *  resta in pausa se era già in pausa). */
    private var scrubWasPlaying = false
    /** elapsedRealtime del primo ACTION_DOWN (repeatCount==0) di REW/FFWD:
     *  base per la soglia temporale dell'hold (≥ SCRUB_HOLD_MS). */
    private var mediaHoldStartAt = 0L
    /** Filtro temporale per i repeat del D-pad in scrub mode (120ms min). */
    private var lastDpadScrubAt = 0L

    // -- Resume seek (M3) -------------------------------------------------------
    /** Posizione di ripresa (extra startPositionSec, SECONDI — piano V2:
     *  il parametro minute dell'embed non si usa, i secondi sono più
     *  precisi). Si attiva SOLO quando il video esiste davvero — legacy:
     *  primo onVideoState(active=true); direct: primo evento 'ready' del
     *  bridge — e converge sugli eventi di posizione (±20s). Push immediato
     *  con retry a 3.5s e 8s (schema pushPendingResumeSeek di MainActivity). */
    private var pendingSeek = 0
    private var resumeSeekArmed = false

    /** Info Inertia dell'ultima navigazione: serve ai salvataggi in onProgress. */
    private var pageInfo: PageInfo? = null

    private val resumeSeekRetry = Runnable {
        // In scrub mode il video è in pausa con preview: un seek di resume
        // qui sposterebbe il video sotto la preview (review finding 6).
        if (pendingSeek > 0 && resumeSeekArmed && !scrubActive) playerSeekTo(pendingSeek)
    }

    private val adBlocker by lazy { AdBlocker.get(this) }

    private fun DomainHome(): String =
        com.scviewer.fire.data.DomainRepository.get(this).currentDomain()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)

        web = findViewById(R.id.playerWeb)
        blackout = findViewById(R.id.playerBlackout)
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
        scrubBox = findViewById(R.id.playerScrubBox)
        scrubTime = findViewById(R.id.playerScrubTime)

        titleText = intent.getStringExtra("title") ?: ""
        watchUrl = intent.getStringExtra("url") ?: ""
        titleId = intent.getIntExtra("titleId", 0)
        profileId = intent.getStringExtra("profileId") ?: DEFAULT_PROFILE_ID
        pendingSeek = intent.getIntExtra("startPositionSec", 0)
        saveUrlRel = SiteUrl.relativeOf(watchUrl)
        currentLang = SettingsStore.getSctvAudioLang(this)

        // -- PLAYER V2: extras della modalità direct ---------------------------
        embedUrl = intent.getStringExtra("embedUrl") ?: ""
        directEmbed = embedUrl.isNotBlank()
        posterUrl = intent.getStringExtra("poster") ?: ""
        recordSlug = intent.getStringExtra("slug") ?: ""
        recordType = intent.getStringExtra("type") ?: ""
        episodeName = intent.getStringExtra("episodeName") ?: ""
        recordSeason = intent.getIntExtra("season", 1).coerceAtLeast(1)
        recordEpisode = intent.getIntExtra("episode", 1).coerceAtLeast(1)
        nextEpisodeId = intent.getIntExtra("nextEpisodeId", 0)
        nextEpisodeSeason = intent.getIntExtra("nextEpisodeSeason", 0)
        nextEpisodeNumber = intent.getIntExtra("nextEpisodeNumber", 0)
        if (directEmbed && nextEpisodeNumber > 0) {
            nextCard.findViewById<TextView>(R.id.playerNextTitle).text =
                "S$nextEpisodeSeason E$nextEpisodeNumber"
        }
        // Direct: label S#E# subito (legacy la riceve da onPageInfo).
        if (directEmbed && recordType == "tv" && recordEpisode > 0) {
            episodeText = "S$recordSeason E$recordEpisode" +
                (episodeName.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
            episodeView.text = episodeText
        }

        backBtn.setOnClickListener { finish() }
        playBtn.setOnClickListener {
            if (directEmbed) {
                // Toggle via ultimo stato noto (gli eventi 'state' correggono
                // eventuali difformità).
                directCall(if (playing) "pause()" else "play()")
            } else {
                jsBridge.requestPauseToggle()
            }
            playing = !playing
            updatePlayIcon()
            scheduleHide()
        }
        rewBtn.setOnClickListener { seekBy(-10) }
        ffwBtn.setOnClickListener { seekBy(10) }
        langBtn = findViewById(R.id.playerLang)
        langBtn.setOnClickListener { showLanguageDialog() }
        skipBtn.setOnClickListener {
            skipAvailable = false
            skipBtn.visibility = View.GONE
            if (directEmbed) directCall("seek(30)") else jsBridge.requestSkipIntro()
        }
        nextPlay.setOnClickListener {
            if (nextEpisodeId > 0) {
                if (directEmbed) {
                    // V2: la catena riparte pulita — result → SctvActivity
                    // onActivityResult → onPlayRequested (embed fresco, token
                    // nuovo); NESSUN loadUrl di una watch page da qui.
                    nextCard.visibility = View.GONE
                    val res = Intent().apply {
                        putExtra("nextEpisodeId", nextEpisodeId)
                        putExtra("nextEpisodeSeason", nextEpisodeSeason)
                        putExtra("nextEpisodeNumber", nextEpisodeNumber)
                    }
                    setResult(RESULT_OK, res)
                    finish()
                } else {
                    val domain = com.scviewer.fire.data.DomainRepository.get(this).currentDomain()
                    web.loadUrl(domain.trimEnd('/') + "/it/watch/" +
                        (watchUrl.substringAfterLast("/watch/").substringBefore("?")) +
                        "?e=" + nextEpisodeId)
                    nextCard.visibility = View.GONE
                    showControls()
                }
            }
        }
        nextCancel.setOnClickListener {
            nextCard.visibility = View.GONE
            nextCardDismissed = true
        }

        // WebView: player reale del sito + agent iniettato a document start.
        @SuppressLint("SetJavaScriptEnabled")
        fun setup() {
            web.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                // M4b: lo UA DESKTOP è parte del fingerprint con cui il sito
                // serve catalogo e player (la MainActivity lo imposta da
                // sempre). Senza, dal player SCtv il WebView presenta lo UA
                // di default (mobile/TV del Fire TV Stick) e la watch page
                // viene trattata diversamente → rimanda alla pagina titolo.
                // Se l'utente ha scelto l'UA alternativo anti-blocco, il
                // player lo rispetta (stesso fingerprint della sessione sito).
                userAgentString =
                    if (SettingsStore.getAlternateUa(this@PlayerActivity)) SCWebViewClient.ALTERNATE_UA
                    else SCWebViewClient.DESKTOP_UA
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
                onCloudflareBlock = { },
                // PLAYER V2: eventi del bridge JW (ready/time/state/complete/
                // error). In legacy mode non arriva MAI (player_bridge non è
                // registrato): l'handler esce subito su !directEmbed.
                onPlayerEvent = { json -> runOnUiThread { onPlayerEvent(json) } }
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

                    // Pulizia player (piano §5.1): analytics vixcloud e ads
                    // spbgc, in ENTRAMBE le modalità. Hard-coded qui oltre
                    // alla hosts-list: l'asset viene ricopiato su disco solo
                    // al primo avvio, un'installazione esistente resterebbe
                    // con la lista vecchia.
                    val host = (request.url.host ?: "").lowercase()
                    if (host.endsWith("spbgc.com") ||
                        host.endsWith("analytics.vixcloud.cc")) {
                        return adBlocker.blockedResponse()
                    }

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

                override fun onPageStarted(
                    view: WebView?,
                    url: String?,
                    favicon: android.graphics.Bitmap?
                ) {
                    super.onPageStarted(view, url, favicon)
                    if (url == null) return
                    // Direct mode: la copertura nera si toglie SOLO
                    // all'evento 'ready' del bridge (o safety 25s) —
                    // l'embed vixcloud è contenuto vero ma non è ancora
                    // riproducibile quando la pagina inizia a caricarsi.
                    if (directEmbed) return
                    // Il contenuto VERO (watch page, wrapper /it/iframe o embed
                    // vixcloud) sta entrando → togli la copertura nera. La home
                    // dello stage1 e le pagine intermedie restano coperte.
                    if (isPlayerContentUrl(url)) {
                        hideBlackout()
                        if (url.contains("/watch/")) {
                            saveUrlRel = SiteUrl.relativeOf(url) ?: saveUrlRel
                        }
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // Direct mode: nessuna orchestrazione (stage1/stage2/
                    // fallback) — l'embed è già il main frame definitivo.
                    if (directEmbed) return
                    if (url != null && isPlayerContentUrl(url)) {
                        // Caso redirect server-side che salta onPageStarted.
                        if (url.contains("/watch/")) {
                            saveUrlRel = SiteUrl.relativeOf(url) ?: saveUrlRel
                        }
                        hideBlackout()
                    }
                    // Stage 2: la home ha stabilito cookie/JS → vai alla watch.
                    if (!stage2Started && url != null && !url.contains("/watch/") &&
                        url.startsWith("http")) {
                        stage2Started = true
                        scope.launch {
                            // CookieManager.getCookie BLOCCA il thread chiamante
                            // in attesa di chromium: chiamato sul UI thread
                            // (onPageFinished) congelava il player mentre
                            // l'home di stage1 teneva chromium occupata
                            // (freeze al play — segnalato dall'utente).
                            // Verifica dei cookie stabiliti dallo stage1: senza,
                            // la watch può venire rimandata alla pagina titolo —
                            // in quel caso scatta il fallback deterministico.
                            cookieHeader = withContext(Dispatchers.IO) {
                                runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
                            }
                            Log.i("Player", "stage1 cookies: " +
                                if (cookieHeader.isNullOrBlank()) "ASSENTI" else "ok")
                            resolvedUrl = withContext(Dispatchers.IO) {
                                resolveWatchUrl(watchUrl, cookieHeader)
                            }
                            Log.i("Player", "load stage2: $resolvedUrl")
                            runOnUiThread { web.loadUrl(resolvedUrl) }
                        }
                        return
                    }
                    // Bounce (M4b): la watch è atterrata sulla PAGINA TITOLO
                    // (sessione fredda / fingerprint non accettati). Fallback
                    // deterministico single-shot: risolvi l'embed via OkHttp
                    // e caricalo direttamente nel player.
                    if (stage2Started && !embedFallbackTried && url != null &&
                        url.contains("/it/titles/") && !url.contains("/watch/")) {
                        embedFallbackTried = true
                        tryEmbedFallback()
                    }
                }
            }
            // FIX fuoco: lo scrubber NON è fuocabile in nessuna modalità —
            // la timeline si comanda solo coi tasti media (REW/FFWD), così
            // LEFT/RIGHT muovono sempre il fuoco e il D-pad non resta mai
            // intrappolato sulla barra.
            try {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    if (directEmbed) {
                        // V2: SOLO player_bridge.js (piano §2.4) — gira nel
                        // frame vixcloud e fa da ponte JW↔nativo. video_agent/
                        // page_inject qui sono inutili e potenzialmente
                        // rumorosi (poll DOM + iniezioni UI nel player pulito).
                        val bridge = assets.open("player_bridge.js")
                            .bufferedReader().use { it.readText() }
                        WebViewCompat.addDocumentStartJavaScript(web, bridge, setOf("*"))
                    } else {
                        val agent = assets.open("video_agent.js").bufferedReader().use { it.readText() }
                        val inject = assets.open("page_inject.js").bufferedReader().use { it.readText() }
                        // Pulizia difensiva della watch page (piano §5.2), a
                        // document start come early_hide.js (pattern coerente
                        // con la registrazione legacy).
                        WebViewCompat.addDocumentStartJavaScript(
                            web, agent + "\n" + inject + "\n" + WATCH_HARDENING_JS, setOf("*")
                        )
                    }
                }
            } catch (_: Throwable) { }
        }
        setup()
        showControls()
        // Copertura nera ON finché il contenuto vero non entra (anti-flash
        // dello stage1) — in direct mode finché l'evento 'ready' del bridge.
        // Rete di sicurezza: mai nero oltre BLACKOUT_MAX_MS.
        blackout.visibility = View.VISIBLE
        handler.postDelayed(blackoutSafety, BLACKOUT_MAX_MS)
        if (directEmbed) {
            // V2: l'embed vixcloud È il main frame. NESSUN two-stage — la
            // risoluzione (token monouso ~10s) è già stata fatta da
            // SctvActivity subito prima del lancio. Il Referer è OBBLIGATORIO
            // (verificato: 403 senza).
            web.loadUrl(embedUrl, mapOf("Referer" to DomainHome().trimEnd('/') + "/"))
        } else {
            // Carico prima la HOME del sito: stabilisce cookie/sessione JS —
            // la watch page a cookie freddi viene rimandata alla pagina titolo.
            web.loadUrl(DomainHome())
            stage2Started = false
        }
    }

    private val pageJson = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Serie: il watch senza ?e= viene rimandato (302) alla pagina titolo
     *  da alcune reti. Risolvi: se il fetch atterra su Titles/Title, prendi
     *  la prima stagione e carica /it/watch/<id>?e=<prima puntata>.
     *  M4b: [cookies] dalla WebView (stage1) — con la sessione del sito il
     *  server risponde con la watch page vera e props.episode è corretto. */
    private fun resolveWatchUrl(raw: String, cookies: String?): String {
        if (raw.contains("e=") || raw.startsWith("file:")) return raw
        return runCatching {
            val props = com.scviewer.fire.data.SiteCatalog
                .fetchProps(raw, "", cookies) ?: return raw
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
            // M4b: separatore giusto anche qui — raw può già portare ?s=
            // (riprese per stagione); "?...?s=" produceva URL malformati.
            val sep = if (raw.contains("?")) "&" else "?"
            raw + sep + "s=" + firstSeason
        }.getOrDefault(raw)
    }

    // -- Copertura nera + fallback embed (M4b) ---------------------------------

    /** Il main frame sta caricando contenuto del player (non l'home dello
     *  stage1 né pagine del sito navigabili): watch page, wrapper del sito
     *  (/it/iframe) o embed vixcloud (fallback / cambio lingua). */
    private fun isPlayerContentUrl(url: String): Boolean {
        val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull() ?: ""
        return url.contains("/it/watch/") || url.contains("/it/iframe/") ||
                host.contains("vixcloud")
    }

    private fun hideBlackout() {
        if (::blackout.isInitialized && blackout.visibility != View.GONE) {
            blackout.visibility = View.GONE
            handler.removeCallbacks(blackoutSafety)
        }
    }

    /** Safety della copertura nera: qualunque cosa succeda, mai schermo nero
     *  oltre il timeout (es. rete morta prima dello stage2). */
    private val blackoutSafety = Runnable { hideBlackout() }

    /** La watch page è rimandata alla pagina titolo (sessione/fingerprint
     *  rifiutati dal server). Fallback deterministico: risolvi l'embed con
     *  OkHttp CON i cookie della WebView e caricalo DIRETTAMENTE nel player
     *  con il Referer del sito. Il token vixcloud dentro il wrapper è
     *  monouso e scade in ~10s: la resolve OkHttp e il loadUrl restano in
     *  sequenza immediata, senza delay intermedi (se il token fosse già
     *  scaduto, il wrapper viene ri-renderizzato rifacendo la resolve).
     *  Inerte nel percorso normale: scatta SOLO sul bounce a /it/titles/,
     *  che nella modalità sito (cookie già caldi) non si verifica mai. */
    private fun tryEmbedFallback() {
        Log.w("Player", "watch rimandata alla pagina titolo → fallback embed diretto")
        scope.launch {
            val embed = withContext(Dispatchers.IO) { resolveEmbedUrl(watchUrl, cookieHeader) }
            if (embed == null) {
                Log.w("Player", "fallback: props.embedUrl non disponibile — mostro la pagina")
                runOnUiThread { hideBlackout() }
                return@launch
            }
            Log.i("Player", "fallback → $embed")
            runOnUiThread {
                saveUrlRel = SiteUrl.relativeOf(watchUrl) ?: saveUrlRel
                web.loadUrl(embed, mapOf("Referer" to DomainHome().trimEnd('/') + "/"))
            }
        }
    }

    /** props.embedUrl della watch URL (per il fallback e il cambio lingua).
     *  Le serie la portano già con ?episode_id= e next_episode=1. Se il
     *  fetch atterra comunque sulla pagina titolo (nessun embedUrl) e il
     *  watch non porta né e= né s=, prova il formato ?s=1 collaudato. */
    private fun resolveEmbedUrl(watchSrc: String, cookies: String?): String? {
        var embed = com.scviewer.fire.data.SiteCatalog
            .fetchProps(watchSrc, "", cookies)
            ?.optString("embedUrl")?.takeIf { it.isNotBlank() }
        if (embed == null && !watchSrc.contains("e=") && !watchSrc.contains("s=")) {
            embed = com.scviewer.fire.data.SiteCatalog
                .fetchProps(watchSrc + "?s=1", "", cookies)
                ?.optString("embedUrl")?.takeIf { it.isNotBlank() }
        }
        return embed
    }

    // -- Lingua audio (M4b) ----------------------------------------------------

    /** Dialog LINGUA. Direct mode (V2): dialog LIVE con AUDIO e SOTTOTITOLI
     *  dal snapshot JW corrente — nessun reload (la master playlist è già
     *  caricata, le tracce si commutano via API JW). Legacy: dialog reload
     *  col parametro lang dell'embed (comportamento M4b invariato). */
    private fun showLanguageDialog() {
        if (directEmbed) {
            showLiveLanguageDialog()
            return
        }
        val labels = arrayOf("Italiano", "Inglese")
        val checked = if (currentLang == "en") 1 else 0
        AlertDialog.Builder(this, R.style.SCOverlay)
            .setTitle("Lingua audio")
            .setSingleChoiceItems(labels, checked) { dlg, which ->
                dlg.dismiss()
                val lang = if (which == 1) "en" else "it"
                if (lang == currentLang) return@setSingleChoiceItems
                SettingsStore.setSctvAudioLang(this, lang)
                reloadWithLang(lang)
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    /** Dialog LINGUA LIVE (piano §4): snapshot da window.__sctvPlayer
     *  (evaluateJavascript) → due sezioni — AUDIO (getAudioTracks →
     *  setAudio(ix)) e SOTTOTITOLI (getCaptionsList → setCaptions(ix),
     *  l'indice 0 è "Off") — con la voce corrente evidenziata. NESSUN
     *  reload: applicazione immediata sul player già in riproduzione. */
    private fun showLiveLanguageDialog() {
        web.evaluateJavascript(
            "try{window.__sctvPlayer?__sctvPlayer.snapshot():null}catch(e){null}"
        ) { raw ->
            val snapJson = decodeJsReturnValue(raw)
            val snap = snapJson?.let {
                runCatching { JSONObject(it) }.getOrNull()
            }
            if (snap == null) {
                // Snapshot fallito (player non pronto / bridge assente).
                AlertDialog.Builder(this, R.style.SCOverlay)
                    .setTitle("Lingua")
                    .setMessage("Player non pronto: riprova tra qualche istante.")
                    .setPositiveButton("Ok", null)
                    .show()
                return@evaluateJavascript
            }
            runOnUiThread { buildLiveLanguageDialog(snap).show() }
        }
    }

    /** evaluateJavascript restituisce il valore JS come literal JSON: per una
     *  stringa arriva racchiusa tra virgolette con escape. Qui la decodifica
     *  al valore vero (null se il valore era null o non è una stringa). */
    private fun decodeJsReturnValue(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return runCatching { pageJson.decodeFromString<String>(raw.trim()) }.getOrNull()
    }

    /** Costruisce il dialog LIVE: ScrollView con sezione AUDIO e sezione
     *  SOTTOTITOLI (RadioButton, corrente pre-selezionato). Sezioni vuote →
     *  nota fissa (film con unica traccia, nessun sottotitolo). */
    private fun buildLiveLanguageDialog(snap: JSONObject): AlertDialog {
        val audio = snap.optJSONArray("audio") ?: JSONArray()
        val audioIx = snap.optInt("audioIx", 0)
        val cc = snap.optJSONArray("cc") ?: JSONArray()
        // getCurrentCaptions può tornare -1: "Off" è comunque l'indice 0.
        val ccIx = snap.optInt("ccIx", 0).let { if (it < 0) 0 else it }
        lateinit var dlg: AlertDialog

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(4))
        }

        // -- Sezione AUDIO -----------------------------------------------------
        content.addView(sectionLabel("AUDIO"))
        if (audio.length() > 0) {
            val group = RadioGroup(this)
            for (i in 0 until audio.length()) {
                val tr = audio.optJSONObject(i)
                val label = tr?.optString("label")?.takeIf { it.isNotBlank() }
                    ?: tr?.optString("name")?.takeIf { it.isNotBlank() }
                    ?: tr?.optString("language")?.takeIf { it.isNotBlank() }
                    ?: "Traccia ${i + 1}"
                group.addView(RadioButton(this).apply {
                    text = label
                    textSize = 15f
                    isChecked = i == audioIx
                    setOnClickListener {
                        dlg.dismiss()
                        if (i != audioIx) directCall("setAudio($i)")
                    }
                })
            }
            content.addView(group)
        } else {
            content.addView(sectionNote("Unica traccia audio"))
        }

        // -- Sezione SOTTOTITOLI ------------------------------------------------
        content.addView(sectionLabel("SOTTOTITOLI"))
        if (cc.length() > 0) {
            val group = RadioGroup(this)
            for (i in 0 until cc.length()) {
                val label = cc.optJSONObject(i)?.optString("label")
                    ?.takeIf { it.isNotBlank() } ?: if (i == 0) "Off" else "Traccia ${i + 1}"
                group.addView(RadioButton(this).apply {
                    text = label
                    textSize = 15f
                    isChecked = i == ccIx
                    setOnClickListener {
                        dlg.dismiss()
                        if (i != ccIx) directCall("setCaptions($i)")
                    }
                })
            }
            content.addView(group)
        } else {
            content.addView(sectionNote("Non disponibili"))
        }

        val scroll = ScrollView(this).apply { addView(content) }
        dlg = AlertDialog.Builder(this, R.style.SCOverlay)
            .setTitle("Lingua e sottotitoli")
            .setView(scroll)
            .setNegativeButton("Annulla", null)
            .create()
        return dlg
    }

    /** Intestazione di sezione del dialog lingua live. */
    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(0xFF8A8A8A.toInt())
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(16), 0, dp(4))
    }

    /** Nota per sezione senza voci (unica traccia / nessun sottotitolo). */
    private fun sectionNote(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(0xFFBBBBBB.toInt())
        setPadding(dp(4), dp(2), 0, dp(2))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Ricarica il player con il parametro lang nuovo PRESERVANDO la
     *  posizione: la posizione corrente (onProgress → posSec) riparte con la
     *  meccanica pendingSeek del resume (si riarma su onVideoState e
     *  converge in onProgress, ±20s). Il reload serve l'embed VIXCLOUD
     *  (il bundle inoltra lang alla master playlist): se il main frame è
     *  già vixcloud basta patchare il parametro; altrimenti si risolve
     *  l'src dell'iframe vixcloud dal wrapper (props.embedUrl → GET → src).
     *  Token monouso ~10s: resolve e loadUrl in sequenza immediata. */
    private fun reloadWithLang(lang: String) {
        val resumePos = posSec
        currentLang = lang
        scope.launch {
            val target = withContext(Dispatchers.IO) { vixcloudEmbedUrl(lang) }
            if (target == null) {
                Log.w("Player", "cambio lingua: embed vixcloud non risolvibile")
                return@launch
            }
            Log.i("Player", "cambio lingua lang=$lang pos=$resumePos → $target")
            withContext(Dispatchers.Main) {
                if (resumePos > 0) {
                    pendingSeek = resumePos
                    resumeSeekArmed = false
                }
                web.loadUrl(target, mapOf("Referer" to DomainHome().trimEnd('/') + "/"))
            }
        }
    }

    /** URL dell'embed vixcloud con [lang] impostato, a partire dallo stato
     *  corrente del WebView (thread IO). */
    private fun vixcloudEmbedUrl(lang: String): String? {
        val url = web.url ?: return null
        // 1. Main frame già sull'embed vixcloud: patch diretta del parametro.
        if (Uri.parse(url).host?.contains("vixcloud") == true) {
            return patchLangParam(url, lang)
        }
        // 2. Main frame = wrapper /it/iframe (fallback attivo): scarica il
        //    wrapper e prendi l'src dell'iframe vixcloud (token fresco).
        if (url.contains("/it/iframe/")) {
            return fetchWrapperVixSrc(url)?.let { patchLangParam(it, lang) }
        }
        // 3. Watch page come main frame: props.embedUrl (con i cookie di
        //    sessione) → wrapper → src vixcloud.
        val watchSrc = saveUrlRel?.let { DomainHome().trimEnd('/') + it }
            ?: url.takeIf { it.contains("/watch/") }
            ?: return null
        val wrapper = resolveEmbedUrl(watchSrc, cookieHeader) ?: return null
        return fetchWrapperVixSrc(wrapper)?.let { patchLangParam(it, lang) }
    }

    /** GET del wrapper /it/iframe/<id> e estrazione dell'src dell'iframe
     *  vixcloud (token monouso generato al render della pagina). */
    private fun fetchWrapperVixSrc(wrapperUrl: String): String? {
        return runCatching {
            val b = Request.Builder().url(wrapperUrl)
                .header("User-Agent", SCWebViewClient.DESKTOP_UA)
                .header("Accept", "text/html")
                .header("Referer", DomainHome().trimEnd('/') + "/")
            cookieHeader?.takeIf { it.isNotBlank() }?.let { b.header("Cookie", it) }
            embedClient.newCall(b.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.i("Player", "wrapper fetch HTTP ${resp.code}")
                    return@use null
                }
                val html = resp.body?.string() ?: return@use null
                Regex("src=\"(https://vixcloud[^\"]+)\"").find(html)
                    ?.groupValues?.get(1)
                    ?.replace("&amp;", "&")
            }
        }.onFailure { Log.w("Player", "wrapper fetch failed", it) }.getOrNull()
    }

    /** Sostituisce/aggiunge il parametro lang preservando tutti gli altri
     *  (token monouso, expires, t, d, episode_id, next_episode...). */
    private fun patchLangParam(raw: String, lang: String): String? {
        return runCatching {
            val u = Uri.parse(raw)
            if (u.host.isNullOrBlank()) return@runCatching null
            val b = Uri.Builder()
                .scheme(u.scheme ?: "https")
                .authority(u.authority)
                .path(u.path ?: "")
            for (n in u.queryParameterNames) {
                if (n.equals("lang", ignoreCase = true)) continue
                b.appendQueryParameter(n, u.getQueryParameter(n) ?: "")
            }
            b.appendQueryParameter("lang", lang)
            b.build().toString()
        }.getOrNull()
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
     *  + retry a 3.5s e 8s (legacy: l'agent consuma la coda ogni 80ms, TTL
     *  12s; direct: API __sctvPlayer diretta). La conferma arriva da
     *  onProgress/onPlayerEvent('time'): convergenza ±20s → pendingSeek=0 e
     *  i retry pendenti diventano no-op. */
    private fun pushPendingResumeSeek() {
        val target = pendingSeek
        if (target <= 0 || !resumeSeekArmed) return
        playerSeekTo(target)
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
                playerSeekTo(pendingSeek)
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
        // M4b: nei salvataggi va SEMPRE il watch URL. Quando il main frame è
        // il wrapper /it/iframe o l'embed vixcloud (fallback / cambio lingua),
        // web.url non è una pagina watch e non deve finire nei record.
        val url = SiteUrl.relativeOf(web.url ?: "")?.takeIf { it.contains("/watch/") }
            ?: saveUrlRel
            ?: return
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
                url = url,
                poster = poster,
                updatedAt = System.currentTimeMillis()
            ),
            profileId
        )
        HistoryStore.get(this).add(url, info.displayLabel(), poster, profileId)
        info.poster?.takeIf { it.isNotBlank() }?.let { PosterCache.put(this, tid, it) }
    }

    private fun fmt(sec: Int): String =
        "%d:%02d".format(sec / 60, sec % 60)

    private fun seekBy(delta: Int) {
        // Durata ignota (tra 'ready' e il primo 'time' la dur può essere 0):
        // seek(0) riavvierebbe il video da capo — no-op finché non la sapiamo
        // (review finding 4). posSec aggiornato ottimisticamente così un
        // doppio tap veloce somma (finding 5).
        if (durSec <= 0) return
        posSec = (posSec + delta).coerceIn(0, (durSec - 1).coerceAtLeast(0))
        playerSeekTo(posSec)
    }

    // -- Comandi player: routing direct ↔ legacy --------------------------------

    /** Seek assoluto: direct → API __sctvPlayer sul frame vixcloud; legacy →
     *  coda comandi di JsInterface (video_agent). */
    private fun playerSeekTo(sec: Int) {
        if (directEmbed) directCall("seek($sec)") else jsBridge.requestSeekTo(sec)
    }

    /** Valuta una chiamata dell'API __sctvPlayer sul frame vixcloud (direct
     *  mode, sempre dal thread UI). [call] è l'espressione COMPLETA già con
     *  i suoi argomenti (es. "seek(30)", "setAudio(1)", "pause()").
     *  Silenziosamente no-op se il bridge non è ancora pronto (pagina in
     *  caricamento o player non ancora creato). */
    private fun directCall(call: String) {
        web.evaluateJavascript(
            "try{window.__sctvPlayer&&__sctvPlayer.$call}catch(e){}", null
        )
    }

    // -- Eventi del bridge JW (PLAYER V2, direct mode) ---------------------------

    /** Eventi di player_bridge.js: ready (snapshot completo), time, state,
     *  complete, error. È l'equivalente direct di onVideoState/onProgress —
     *  in questo mode video_agent non esiste. */
    private fun onPlayerEvent(json: String) {
        if (!directEmbed) return
        val ev = runCatching { JSONObject(json) }.getOrNull() ?: return
        when (ev.optString("t")) {
            "ready" -> {
                nextCardDismissed = false
                hideBlackout()
                val st = ev.optJSONObject("snapshot")?.optString("state")
                // nogui autostarta: buffering/playing contano come "playing"
                // per auto-hide e icona; idle/paused/complete no.
                playing = when (st) { "paused", "idle", "complete" -> false; else -> true }
                updatePlayIcon()
                showControls()
                scheduleHide()

                // Cronologia: UNA voce per riproduzione, al primo ready. Il
                // record DEVE portare il watch URL (extra di SctvActivity),
                // mai l'embedUrl — stessa convenzione della modalità sito.
                if (!historyLogged && watchUrl.isNotBlank()) {
                    historyLogged = true
                    HistoryStore.get(this).add(watchUrl, recordLabel(), posterUrl.ifBlank { null }, profileId)
                    if (posterUrl.isNotBlank() && titleId > 0) {
                        PosterCache.put(this, titleId, posterUrl)
                    }
                }

                // Resume seek (M3): si arma al PRIMO ready — qui non c'è
                // onVideoState (video_agent non gira in direct mode).
                if (!resumeSeekArmed) {
                    resumeSeekArmed = true
                    if (pendingSeek > 0) pushPendingResumeSeek()
                }
            }
            "time" -> onDirectTime(ev.optInt("pos"), ev.optInt("dur"))
            "state" -> {
                playing = ev.optString("state") == "playing"
                updatePlayIcon()
                // In scrub mode i controlli restano visibili (preview attiva).
                if (!scrubActive) scheduleHide()
            }
            "complete" -> {
                // Uscita FORZATA dallo scrub: il video è finito (es. commit
                // del target su dur) — niente indicatore appeso a schermo.
                if (scrubActive) exitScrub()
                playing = false
                updatePlayIcon()
                // A video finito gli eventi 'time' cessano: se c'è un prossimo
                // episodio, la card resta visibile (come in legacy).
                if (nextEpisodeId > 0 && !nextCardDismissed) {
                    nextCard.visibility = View.VISIBLE
                    showControls()
                }
            }
            "error" -> {
                // Player morto: esci da un'eventuale scrub mode pendente.
                if (scrubActive) exitScrub()
                Log.w("Player", "bridge JW: errore: ${ev.optString("msg")}")
                // L'embed mostra la sua schermata di errore: scopri e segnala
                // (una volta sola per riproduzione).
                if (!directErrorShown) {
                    directErrorShown = true
                    hideBlackout()
                    Toast.makeText(this, "Errore di riproduzione: riprova dal titolo",
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** "Nome — S1E2 · Titolo puntata" per Cronologia/ProgressStore (stesso
     *  formato di PageInfo.displayLabel in legacy). */
    private fun recordLabel(): String =
        if (recordType == "tv" && recordEpisode > 0)
            "$titleText — S$recordSeason E$recordEpisode" +
                (episodeName.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
        else titleText

    /** Evento 'time' del bridge (direct): aggiorna barra/tempi, resume seek,
     *  skip intro, card prossimo episodio e salvataggio progresso.
     *  Throttle: JW emette 'time' più volte al secondo — UI a ≥1s (legacy
     *  riceve report ogni ~2s), salvataggi a ≥10s (stesso throttle di
     *  video_agent). */
    private fun onDirectTime(pos: Int, dur: Int) {
        posSec = pos; durSec = dur
        // SCRUB MODE: la barra e i tempi mostrano il TARGET della preview —
        // gli eventi 'time' residui (il video è in pausa) non devono
        // sovrascriverli. posSec/durSec restano aggiornati come posizione
        // reale per commit/annullamento.
        if (scrubActive) return
        val now = System.currentTimeMillis()
        if (now - lastTimeUiAt < 1000) return
        lastTimeUiAt = now

        scrubber.max = dur.coerceAtLeast(1)
        scrubber.progress = pos
        timeCur.text = fmt(pos)
        timeTot.text = fmt(dur)

        // -- Resume seek: convergenza (±20s) o ripush — identico a M3 --------
        if (pendingSeek > 0) {
            if (kotlin.math.abs(pos - pendingSeek) < 20) {
                pendingSeek = 0                          // già a punto
            } else if (pos > pendingSeek + 20) {
                pendingSeek = 0                          // oltre (es. seek utente)
            } else if (pos < pendingSeek - 20) {
                playerSeekTo(pendingSeek)
                handler.removeCallbacks(resumeSeekRetry)
                handler.postDelayed(resumeSeekRetry, 3500)
                handler.postDelayed(resumeSeekRetry, 8000)
            }
        }

        // Skip intro (euristica piano §4): primi 30s di un titolo >60s.
        skipAvailable = dur > 60 && pos > 0 && pos < 30
        skipBtn.visibility = if (skipAvailable) View.VISIBLE else View.GONE

        // Prossimo episodio: card negli ultimi 90s (solo serie con next id).
        val showNext = nextEpisodeId > 0 && !nextCardDismissed && dur > 0 &&
                dur - pos < 90 && pos > dur / 2
        nextCard.visibility = if (showNext) View.VISIBLE else View.GONE
        if (showNext) showControls()

        // -- Salvataggio progresso (throttle 10s) ------------------------------
        // Solo a video maturo e solo dopo che il resume seek è confluito:
        // la posizione pre-seek (≈0) non deve regredire il punto di ripresa.
        if (dur <= 60 || pendingSeek > 0 || titleId <= 0) return
        if (now - lastSaveAt < 10_000) return
        lastSaveAt = now
        // URL RELATIVO del watch (MAI l'embedUrl nel record — piano §4).
        val urlRel = SiteUrl.relativeOf(watchUrl) ?: return
        ProgressStore.get(this).save(
            WatchProgress(
                titleId = titleId,
                name = titleText,
                slug = recordSlug,
                type = recordType.ifBlank { "movie" },
                season = recordSeason,
                episode = recordEpisode,
                episodeName = episodeName.takeIf { it.isNotBlank() },
                positionSec = pos,
                durationSec = dur,
                url = urlRel,
                poster = posterUrl.takeIf { it.isNotBlank() },
                updatedAt = now
            ),
            profileId
        )
    }

    // -- Tasti media del telecomando (solo direct; legacy invariato) -----------

    /** PLAY/PAUSE (85/126/127): toggle play/pausa via __sctvPlayer. Aggiorna
     *  l'ultimo stato noto per l'icona playBtn — la VERITÀ resta negli eventi
     *  'state' del bridge, che correggono eventuali difformità. Durante lo
     *  scrub: annulla lo scrub SENZA seek e applica il comando allo stato che
     *  l'utente vedeva prima (niente seek a sorpresa, niente doppio toggle). */
    private fun handleMediaPlayPause(event: KeyEvent, code: Int): Boolean {
        // Solo il primo ACTION_DOWN comanda; UP e repeat si consumano qui.
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return true
        if (scrubActive) {
            val wasPlaying = scrubWasPlaying
            exitScrub()
            when (code) {
                KeyEvent.KEYCODE_MEDIA_PLAY -> { directCall("play()"); playing = true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { directCall("pause()"); playing = false }
                else -> {
                    directCall(if (wasPlaying) "pause()" else "play()")
                    playing = !wasPlaying
                }
            }
        } else {
            when (code) {
                KeyEvent.KEYCODE_MEDIA_PLAY ->
                    if (!playing) { directCall("play()"); playing = true }
                KeyEvent.KEYCODE_MEDIA_PAUSE ->
                    if (playing) { directCall("pause()"); playing = false }
                else -> {
                    directCall(if (playing) "pause()" else "play()")
                    playing = !playing
                }
            }
        }
        updatePlayIcon()
        showControls()
        scheduleHide()
        return true
    }

    /** REWIND/FAST_FORWARD (89/90):
     *  - pressione SINGOLA (repeatCount==0) → seek immediato ±10s (come i
     *    bottoni ⟲10 / 10⟳); se siamo già in scrub mode il singolo aggiusta
     *    invece il TARGET (il commit resta su OK);
     *  - TENUTO (repeat > 0 oltre soglia) → entra/continua SCRUB MODE.
     *  Soglia hold: il primo repeat con repeatCount ≥ 3 OPPURE ≥200ms dal
     *  primo DOWN — con i periodi di repeat del Fire TV (~50ms, delay iniziale
     *  del framework) scatta al primo repeat utile in ogni caso, e la
     *  condizione temporale copre remoti con timing diverso. */
    private fun handleMediaSeek(event: KeyEvent, code: Int): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {
            // Rilascio: resta in SCRUB MODE con l'indicatore visibile — il
            // commit avviene SOLO con OK, l'annullamento con BACK.
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val dir = if (code == KeyEvent.KEYCODE_MEDIA_REWIND) -1 else 1
        if (event.repeatCount == 0) {
            // Nuova pressione: azzera l'accelerazione del gesto corrente.
            mediaHoldStartAt = SystemClock.elapsedRealtime()
            scrubSteps = 0
            if (scrubActive) {
                scrubAdjust(dir, accelerated = false)
            } else {
                // Base per il cancel dello scrub che potrebbe aprire il gesto
                // (hold): BACK deve riprendere dalla posizione PRE-gesto.
                scrubBaseSec = posSec.coerceIn(0, (durSec - 1).coerceAtLeast(0))
                seekBy(dir * SCRUB_STEP_NEAR)
            }
            showControls()
            return true
        }
        // Repeat (tasto tenuto): al primo oltre soglia attiva lo scrub mode.
        if (!scrubActive) {
            val holdMs = SystemClock.elapsedRealtime() - mediaHoldStartAt
            if (event.repeatCount >= SCRUB_HOLD_REPEATS || holdMs >= SCRUB_HOLD_MS) {
                enterScrub(dir)
            }
        }
        if (scrubActive) scrubAdjust(dir, accelerated = true)
        return true
    }

    // -- SCRUB MODE -------------------------------------------------------------

    /** Attiva la SCRUB MODE: pausa il video (SOLO preview — il seek HLS
     *  ripetuto è costoso e avviene una volta sola all'OK), salva lo stato
     *  play/pause percepito dall'utente e mostra l'indicatore del target.
     *  Senza durata nota lo scrub non si attiva (i repeat restano no-op). */
    private fun enterScrub(dir: Int) {
        if (durSec <= 0) return
        scrubActive = true
        scrubWasPlaying = playing
        scrubSteps = 0
        scrubDir = dir
        // Il target parte dalla base PRE-salto (captata al primo DOWN del
        // gesto): hold+OK senza adjust = net-zero movement (review finding 3).
        scrubTargetSec = (if (scrubBaseSec >= 0) scrubBaseSec else posSec)
            .coerceIn(0, durSec)
        if (playing) directCall("pause()")
        playing = false
        updatePlayIcon()
        updateScrubUi()
        showControls()
    }

    /** Aggiusta il target di [dir] passi. Accelerazione SOLO sul percorso
     *  "tenuto premuto" (repeat dei tasti media): gli incrementi del gesto
     *  corrente crescono 10s → 30s → 60s; singoli e D-pad restano a 10s.
     *  Ai bordi passo ridotto a 5s e target sempre dentro [0, dur] (dur
     *  compreso: OK lì equivale a saltare alla fine → 'complete'). Nessun
     *  seek reale: solo indicatore + barra/tempi sul target. Un eval JS per
     *  repeat va bene (i repeat arrivano ogni ~50ms, comandi leggeri). */
    private fun scrubAdjust(dir: Int, accelerated: Boolean) {
        if (!scrubActive || durSec <= 0) return
        val step = when {
            !accelerated -> SCRUB_STEP_NEAR
            scrubSteps >= SCRUB_REPEATS_FAST -> SCRUB_STEP_FAR
            scrubSteps >= SCRUB_REPEATS_MED -> SCRUB_STEP_MED
            else -> SCRUB_STEP_NEAR
        }
        if (accelerated) scrubSteps++
        scrubDir = dir
        var next = scrubTargetSec + dir * step
        if (next < 0 || next > durSec) {
            // Bordo: passo fine prima di agganciare il limite, mai oltre 0/dur.
            next = scrubTargetSec + dir * minOf(step, SCRUB_STEP_EDGE)
        }
        scrubTargetSec = next.coerceIn(0, durSec)
        updateScrubUi()
    }

    /** DPAD LEFT/RIGHT in scrub mode: aggiusta il target a passo fisso con un
     *  minimo di 120ms tra gli incrementi (i repeat del D-pad arrivano ogni
     *  ~50ms — senza filtro lo scorrimento sarebbe troppo veloce). */
    private fun scrubAdjustThrottled(dir: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDpadScrubAt < SCRUB_DPAD_MIN_MS) return
        lastDpadScrubAt = now
        scrubAdjust(dir, accelerated = false)
    }

    /** Preview dello scrub: indicatore grande centrato (▶▶/◀◀ + target) e
     *  posizione TARGET su barra e tempo corrente. Nessun seek reale. */
    private fun updateScrubUi() {
        if (!scrubActive) return
        scrubBox.visibility = View.VISIBLE
        scrubTime.text = (if (scrubDir < 0) "◀◀ " else "▶▶ ") + fmt(scrubTargetSec)
        scrubber.progress = scrubTargetSec.coerceAtMost(scrubber.max)
        timeCur.text = fmt(scrubTargetSec)
    }

    /** OK in scrub mode: seek al target + play — il film riparte da lì. */
    private fun commitScrub() {
        if (!scrubActive) return
        val target = scrubTargetSec
        exitScrub()
        posSec = target
        directCall("seek($target)")
        directCall("play()")
        playing = true
        updatePlayIcon()
        showControls()
        scheduleHide()
    }

    /** BACK in scrub mode: annulla — NESSUN seek; il video resta dove era
     *  quando lo scrub è partito: se era in play (enterScrub l'aveva messo in
     *  pausa) riprende da lì, se era già in pausa resta in pausa. */
    private fun cancelScrub() {
        if (!scrubActive) return
        val wasPlaying = scrubWasPlaying
        val base = scrubBaseSec
        exitScrub()
        // Net-zero: BACK ripristina la posizione PRE-gesto (review finding 3).
        if (base >= 0) directCall("seek(${base.coerceIn(0, (durSec - 1).coerceAtLeast(0))})")
        if (wasPlaying) {
            directCall("play()")
            playing = true
            updatePlayIcon()
        }
        showControls()
    }

    /** Esce dalla scrub mode e nasconde l'indicatore, senza toccare il video. */
    private fun exitScrub() {
        scrubActive = false
        scrubTargetSec = 0
        scrubSteps = 0
        scrubBaseSec = -1
        scrubBox.visibility = View.GONE
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
        val code = event.keyCode

        // INDIETRO: in scrub mode ANNULLA (nessun seek, play ripristinato);
        // controlli visibili → nascondi; nascosti → esci.
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (scrubActive) cancelScrub()
                else if (controlsVisible) hideControls() else finish()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        // Tasti MEDIA (85/126/127/89/90) SOLO in direct mode: play/pausa e
        // seek/scrub della timeline. In legacy restano come oggi — nessun
        // ramo toccato, cadono al super come prima.
        if (directEmbed) {
            when (code) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE ->
                    return handleMediaPlayPause(event, code)
                KeyEvent.KEYCODE_MEDIA_REWIND,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ->
                    return handleMediaSeek(event, code)
            }
        }

        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        // Qualsiasi altro D-pad: mostra i controlli e muovi il fuoco.
        showControls()
        when (code) {
            // FIX fuoco: LEFT/RIGHT muovono SEMPRE il fuoco tra i controlli —
            // il ramo seek-±10%-sullo-scrubber è rimosso (intrappolava il
            // D-pad). In scrub mode aggiustano il TARGET della timeline e il
            // fuoco resta fermo (tutta l'attenzione è lì).
            KeyEvent.KEYCODE_DPAD_LEFT ->
                if (scrubActive) scrubAdjustThrottled(-1) else moveFocus(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT ->
                if (scrubActive) scrubAdjustThrottled(1) else moveFocus(1)
            // In scrub mode anche UP/DOWN non muovono il fuoco (no-op).
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN ->
                if (!scrubActive) return super.dispatchKeyEvent(event)
            // OK: in scrub mode CONFERMA il target (seek + play); fuori dal
            // scrub attiva il controllo fuocato. Lo scrubber non è mai
            // fuocabile → OK non può più fare seek per sbaglio.
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
                if (scrubActive) commitScrub()
                else focusOrder.getOrNull(focusIdx)?.performClick()
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
        /** Massimo tempo coperto dalla schermata nera anti-flash. */
        private const val BLACKOUT_MAX_MS = 25_000L

        // -- SCRUB MODE (soglie e passi) ---------------------------------------
        /** Repeat oltre i quali l'hold dei tasti media attiva lo scrub. */
        private const val SCRUB_HOLD_REPEATS = 3
        /** Hold minimo (ms dal primo ACTION_DOWN) per attivare lo scrub:
         *  il primo repeat che soddisfa UNA delle due soglie fa partire la
         *  preview (copre remoti con periodi di repeat diversi). */
        private const val SCRUB_HOLD_MS = 200L
        /** Passo base: pressione singola e primi incrementi dello scrub. */
        private const val SCRUB_STEP_NEAR = 10
        /** Passo medio dell'accelerazione (dopo 6 incrementi del gesto). */
        private const val SCRUB_STEP_MED = 30
        /** Passo massimo dell'accelerazione (dopo 12 incrementi del gesto). */
        private const val SCRUB_STEP_FAR = 60
        /** Passo fine ai bordi della timeline (mai oltre 0/dur). */
        private const val SCRUB_STEP_EDGE = 5
        /** Incrementi del gesto prima di passare al passo medio (30s). */
        private const val SCRUB_REPEATS_MED = 6
        /** Incrementi del gesto prima di passare al passo massimo (60s). */
        private const val SCRUB_REPEATS_FAST = 12
        /** Minimo intervallo (ms) tra aggiustamenti col D-pad in scrub mode. */
        private const val SCRUB_DPAD_MIN_MS = 120L

        /** Pulizia difensiva della watch page (legacy, piano §5.2 — RE
         *  schermata watch): nasconde la modal premium (body>.modal) e i
         *  noscript, blocca lo scroll e fonda il fondo a nero. Registrata a
         *  document start (stesso pattern di early_hide.js) e limitata al
         *  main frame: l'iframe vixcloud gestisce da solo il proprio layout.
         *  Copre anche il main-frame-embed del fallback M4b (fondo nero
         *  mentre il player vixcloud si avvia). */
        private const val WATCH_HARDENING_JS = """
(function(){
  if (window.__scvWatchHardening || window.top !== window) return;
  window.__scvWatchHardening = true;
  try {
    var s = document.createElement('style');
    s.id = 'scv-watch-hardening';
    s.textContent = 'body>.modal{display:none !important;}' +
        'noscript{display:none !important;}' +
        'html,body{overflow:hidden !important; background:#000 !important;}';
    (document.head || document.documentElement).appendChild(s);
  } catch (e) { /* no-op */ }
})();
"""
    }

    /** Client HTTP per wrapper/props legati al player (risolve l'embed per
     *  fallback e cambio lingua). Timeout stretti: il token vixcloud del
     *  wrapper scade in ~10s. */
    private val embedClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
