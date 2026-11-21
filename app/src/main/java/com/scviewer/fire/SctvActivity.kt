package com.scviewer.fire

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.scviewer.fire.bridge.SctvBridge
import com.scviewer.fire.bridge.SctvRepository
import com.scviewer.fire.data.DEFAULT_PROFILE_ID
import com.scviewer.fire.data.DomainRepository
import com.scviewer.fire.data.PosterCache
import com.scviewer.fire.data.ProgressStore
import com.scviewer.fire.data.SettingsStore
import com.scviewer.fire.data.SiteUrl
import com.scviewer.fire.data.sanitizeProfileId
import androidx.appcompat.app.AppCompatActivity
import com.scviewer.fire.network.AdBlocker
import com.scviewer.fire.network.HlsRewriter
import com.scviewer.fire.ui.SettingsDialog
import com.scviewer.fire.web.SCWebViewClient
import com.scviewer.fire.work.DomainRefreshScheduler
import com.scviewer.fire.work.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Nuova interfaccia SCtv (M2 — dati reali): WebView fullscreen che carica
 * l'asset locale `sctv/index.html` e lo alimenta con i payload di
 * [SctvRepository] (home/serie/film dal sito, dettaglio con episodi, ricerca
 * server, My List da FavoritesStore, Continua a guardare da ProgressStore).
 *
 * Ciclo di vita (piano §1.2):
 * - push nativo→JS SEMPRE da UI thread via `window.__sctvReceive(ev)`;
 * - D-pad → `handleRemoteAction(...)`; BACK → `window.__sctvBackState()`
 *   con callback 'exit' (chiudi app) / 'stay' (gestito dal JS);
 * - onResume: web.onResume + refresh dominio + push progressChanged;
 * - onPause: web.onPause (congela il rendering, il fuoco resta pulito);
 * - onDestroy: scope.cancel + destroy della WebView;
 * - onTrimMemory: clearCache sotto pressione di memoria.
 *
 * Recovery dominio (piano §3 rischi): ogni fetch fallito marca il dominio con
 * [DomainRepository.markDomainFailed], riprova UNA volta sul mirror successivo
 * ([loadCandidate]); se il mirror risponde viene promosso a primario
 * ([promoteRedirect]) e pushato `domainChanged` al JS.
 *
 * Player NATIVO delegato (PLAYER V2 — direct-embed): startPlayback (JS) →
 * AndroidJs.play → qui si risolve la puntata (detail cache → episodeId), si
 * risolve ORA l'embed vixcloud via OkHttp (+nogui=1, token monouso ~10s) e
 * si apre PlayerActivity con embedUrl + metadati (titleId, season/episode,
 * nextEpisodeId, poster, watch URL per i record). Su fallimento della
 * resolve: errore in UI e stop. La catena "prossimo episodio" riparte da
 * onActivityResult (RESULT_OK con nextEpisodeId). NIENTE player HTML5.
 */
class SctvActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SctvActivity"
        private const val BRIDGE_VERSION = 1
        /** requestCode del player: serve alla catena "prossimo episodio" (V2). */
        private const val REQ_PLAYER = 7001
    }

    private lateinit var web: WebView
    private lateinit var loadingOverlay: View
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val domainRepo by lazy { DomainRepository.get(this) }
    private val repo by lazy { SctvRepository(this) }

    /** TRUE mentre PlayerActivity (locale) è in primo piano: se una sessione
     *  cast partisse in quel frangente il telecomando NON deve coprirlo. */
    private var localPlayerOpen = false

    /** titleId del play in corso: onActivityResult (catena prossimo
     *  episodio) riparte da qui senza riscattare l'id dal result. */
    private var pendingPlayTitleId = 0

    /** true dopo il push del primo evento boot: evita push progressChanged
     *  verso una pagina che non ha ancora il dispatcher attivo. */
    @Volatile private var booted = false

    /** Profilo SCtv corrente (dati scoped: My List, CW, record player).
     *  Scritto dal bridge setActiveProfile (binder thread) e letto dalle
     *  coroutine: volatile, assegnazione SINCRONA sul binder così bootReady —
     *  chiamato subito dopo dal JS — vede già il profilo giusto. */
    @Volatile private var currentProfileId = DEFAULT_PROFILE_ID

    /** Ultimo host pushato al JS: evita domainChanged ridondanti. */
    @Volatile private var pushedHost = ""

    /**
     * LAVORO 1 — TIERING RAM ("low"/"mid"/"high"): il device si adegua alla
     * RAM disponibile. Box migliori = interfaccia più ricca (blur, kenBurns,
     * shadow ampi); stick deboli (AFTSSS 1GB, Mali-450) = interfaccia lean
     * ma sempre reattiva. La tier viene decisa UNA volta qui, prima di
     * qualunque boot push, e consumata in due posti:
     *  - payload boot, campo nuovo "quality" → il JS mette la classe `q-*`
     *    su <html> e abilita/disabilita le regole CSS del tier (index.html);
     *  - [SctvRepository.bootPayload]/[SctvRepository.tabPayload] per i CAP
     *    delle rail (low = 6 rail × 20 titoli, mid = 8 × 25, high = senza).
     * isLowRamDevice + soglia 1800MB coprono gli stick 1GB; sotto 3200MB è
     * mid, sopra high. lazy: getSystemService è economico ma non serve
     * ricomputarlo — la RAM fisica non cambia durante il processo.
     */
    private val sctvQuality: String by lazy {
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val totalMb = mi.totalMem shr 20
        val tier = when {
            am.isLowRamDevice || totalMb < 1800 -> "low"
            totalMb < 3200 -> "mid"
            else -> "high"
        }
        Log.i(TAG, "SCtv quality tier: $tier (RAM=${totalMb}MB, isLowRamDevice=${am.isLowRamDevice})")
        tier
    }

    // -- Creazione ---------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sctv)
        web = findViewById(R.id.sctvWeb)
        loadingOverlay = findViewById(R.id.sctvLoading)
        HlsRewriter.maxHeightOverride = SettingsStore.qualityCapHeight(this)
        // LAVORO 1 — tiering RAM: inizializza la tier SUBITO (primo accesso
        // alla lazy, che logga la tier scelta), prima di qualunque boot push.
        Log.d(TAG, "SCtv tier attiva: $sctvQuality")
        configureWebView()
        // Cache-busting via versionCode: aggiornare l'asset invalida la URL.
        web.loadUrl("file:///android_asset/sctv/index.html?v=" + BuildConfig.VERSION_CODE)
        // Refresh periodico del dominio anche per l'interfaccia SCtv.
        DomainRefreshScheduler.schedule(this)
        observeDomainChanges()
        // Cast Chromecast (C1): SOLO phone mode — su Fire TV questa funzione
        // esce subito e NESSUN codice cast viene istanziato.
        setupCastIfAvailable()

        // REGRESSIONE (parità con la modalità sito): sync della blocklist
        // adblock da GitHub a ogni boot — il Player (direct) filtra le
        // richieste col singleton AdBlocker e qui è l'unico avvio del
        // processo (il WorkManager periodico la rinfresca solo ogni 30 min).
        // Fuori dallo scope dell'activity: la coroutine deve concludersi
        // anche se SctvActivity viene distrutta subito.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { AdBlocker.get(this@SctvActivity).syncFromGitHub() }
        }
        // REGRESSIONE: auto-aggiornamento anche in SCtv mode (prima esisteva
        // solo in MainActivity.onCreate). Fetch GitHub con gate 12h condiviso
        // (prefs "scviewer_updates") + proposta persistita: il chip di un
        // aggiornamento già trovato torna a ogni avvio, anche dopo un Cancel
        // nell'installer di sistema. Download/installer via UpdateManager.
        checkUpdates()
    }

    /** Check aggiornamento (proposta persistita + gate 12h interno solo per
     *  la fetch): alla release più nuova mostra il chip nativo — banner in
     *  basso a sinistra, fuori dall'area di header/logo di SCtv. Da fetch
     *  fresca avvia subito download + installer; dal chip di cache (proposta
     *  già trovata in un check precedente) il download parte solo dal click,
     *  per non riaprire l'installer a ogni avvio dopo un Cancel. */
    private fun checkUpdates() {
        UpdateManager.checkForUpdates(this) { tag, fromCache ->
            UpdateManager.showChip(this, tag, bottomBanner = true)
            if (!fromCache) UpdateManager.startAutoUpdate(this, tag)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true            // localStorage profili (fix TV #11)
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true              // asset file:// + <img> https ok
            // Lo stick ha viewport ~960 CSS px: il design è pensato per 1280.
            useWideViewPort = true
            loadWithOverviewMode = true
            textZoom = 100
        }
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)

        val bridge = SctvBridge(
            onBootReady = { jsBootReady() },
            onLoadTab = { tab -> jsLoadTab(tab) },
            onLoadDetail = { titleId, req -> jsLoadDetail(titleId, req) },
            onSearch = { query, req -> jsSearch(query, req) },
            onLoadCategories = { req -> jsLoadCategories(req) },
            onLoadGenre = { genreId, req -> jsLoadGenre(genreId, req) },
            onLoadDetailSeason = { titleId, season, req -> jsLoadDetailSeason(titleId, season, req) },
            onToggleMyList = { titleId -> jsToggleMyList(titleId) },
            onGetContinueWatching = { jsGetContinueWatching() },
            onReportProgress = { titleId, season, episode, pos, dur ->
                Log.i(TAG, "reportProgress($titleId, S$season:E$episode, $pos/$dur): M3")
            },
            onPlay = { titleId, season, episode ->
                scope.launch { onPlayRequested(titleId, season, episode) }
            },
            onOpenSettings = { scope.launch { openNativeSettings() } },
            onOpenSiteInterface = { scope.launch { switchToSite() } },
            onRetryConnection = { jsBootReady() },
            onSetActiveProfile = { pid -> onProfileSelected(pid) }
        )
        web.addJavascriptInterface(bridge, "AndroidJs")
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // L'HTML mostra il suo splash+gate: via l'overlay nativo.
                loadingOverlay.visibility = View.GONE
            }
        }
    }

    // -- Cast Chromecast (C1, SOLO phone mode) -------------------------------------

    /** Host del controller cast: container della WebView headless (C2) e
     *  dispatcher dei comandi AndroidJs del receiver custom (C3). */
    private val castHost = object : com.scviewer.fire.cast.SctvCastController.Host {
        override fun castWebHost(): ViewGroup? =
            findViewById(R.id.sctvCastWebHost)
        override fun onCastAndroidJs(fn: String, args: org.json.JSONArray) {
            dispatchCastAndroidJs(fn, args)
        }
    }

    /**
     * Abilita il cast SOLO se: (1) device telefono (DeviceProfile.isPhone —
     * Fire TV esce qui e NESSUN codice cast viene istanziato: niente
     * controller, niente CastContext, niente listener) e (2) Google Play
     * services disponibili. Altrimenti il bottone resta GONE.
     */
    private fun setupCastIfAvailable() {
        if (!com.scviewer.fire.cast.DeviceProfile.isPhone(this)) return
        val cast = com.scviewer.fire.cast.SctvCastController.get(this)
        if (!cast.isAvailable()) {
            Log.i(TAG, "cast: Google Play services non disponibili — disabled")
            return
        }
        val bar = findViewById<View>(R.id.sctvCastBar)
        val btn = findViewById<androidx.mediarouter.app.MediaRouteButton>(R.id.sctvCastRoute)
        bar.visibility = View.VISIBLE
        com.google.android.gms.cast.framework.CastButtonFactory
            .setUpMediaRouteButton(this, btn)
        // DEBUG FAKE CAST: long-press sul bottone cast apre il telecomando
        // senza un Chromecast fisico (setting "cast fake", solo build debug).
        if (BuildConfig.DEBUG && SettingsStore.getCastFake(this)) {
            btn.setOnLongClickListener {
                cast.startFakeSession()
                true
            }
        }
        // Listener eventi sessione → apertura/chiusura del telecomando (C1.6).
        cast.sessionUiListener = { event ->
            runOnUiThread {
                when (event) {
                    "started", "resumed" -> {
                        // Non coprire il player locale se fosse in primo piano.
                        if (!localPlayerOpen) {
                            startActivity(
                                Intent(this, com.scviewer.fire.cast.RemoteActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                    "ended" -> {
                        // Il telecomando si chiude da sé (listener proprio).
                    }
                }
            }
        }
        cast.attach(castHost)
        Log.i(TAG, "cast: abilitato (phone mode + GMS)")
    }

    // -- Handler bridge + relay receiver custom -----------------------------------

    /** Handler AndroidJs come metodi nominati: usati dal bridge (WebView)
     *  E dal relay del receiver custom cast ({cmd:"androidjs"}) — le stesse
     *  funzioni, due canali. Ogni push() generato da questi handler viene
     *  moltiplicato verso il namespace cast (relayReceive). */

    private fun jsBootReady() {
        scope.launch { pushBootPayload() }
    }

    private fun jsLoadTab(tab: String) {
        scope.launch {
            // LAVORO 1: quality al repository per i CAP rail del tier.
            val payload = withRecovery("loadTab($tab)") { dom ->
                repo.tabPayload(tab, dom, sctvQuality)
            }
            if (payload != null) push("catalog", payload)
            else pushError("Tab non raggiungibile: controlla la connessione")
        }
    }

    private fun jsLoadDetail(titleId: Int, req: Int) {
        scope.launch {
            val item = withRecovery("loadDetail($titleId)") { dom ->
                repo.detailPayload(titleId, dom, currentProfileId)
            }
            if (item != null) {
                push("titleDetail", JSONObject().put("item", item), req)
            } else {
                pushError("Dettaglio non disponibile: controlla la connessione", req)
            }
        }
    }

    private fun jsSearch(query: String, req: Int) {
        scope.launch {
            val payload = withRecovery("search('$query')") { dom ->
                repo.searchPayload(query, dom)
            }
            if (payload != null) push("searchResults", payload, req)
            else pushError("Ricerca non riuscita: controlla la connessione", req)
        }
    }

    private fun jsLoadCategories(req: Int) {
        scope.launch {
            val payload = withRecovery("loadCategories") { dom ->
                repo.categoriesPayload(dom)
            }
            if (payload != null) push("categories", payload, req)
            else pushError("Categorie non disponibili: controlla la connessione", req)
        }
    }

    private fun jsLoadGenre(genreId: Int, req: Int) {
        scope.launch {
            val payload = withRecovery("loadGenre($genreId)") { dom ->
                repo.genrePayload(genreId, dom)
            }
            if (payload != null) push("genreData", payload, req)
            else pushError("Genere non disponibile: controlla la connessione", req)
        }
    }

    private fun jsLoadDetailSeason(titleId: Int, season: Int, req: Int) {
        scope.launch {
            val result = withRecovery("loadDetailSeason($titleId,S$season)") { dom ->
                repo.seasonEpisodes(titleId, season, dom, currentProfileId)?.let { (s, eps) ->
                    JSONObject().put("season", s).put("episodes", eps)
                }
            }
            if (result != null) push("titleSeason", result, req)
            else pushError("Stagione non disponibile: controlla la connessione", req)
        }
    }

    private fun jsToggleMyList(titleId: Int) {
        scope.launch {
            val result = withRecovery("toggleMyList($titleId)") { dom ->
                repo.toggleMyList(titleId, dom, currentProfileId)
            }
            if (result != null) {
                push("myListChanged", result)
            } else {
                pushError("Impossibile aggiornare La mia lista")
            }
        }
    }

    private fun jsGetContinueWatching() {
        scope.launch {
            val cw = withContext(Dispatchers.IO) {
                repo.continueWatchingJson(currentProfileId)
            }
            push("progressChanged", JSONObject().put("continueWatching", cw))
        }
    }

    /**
     * Relay del receiver CUSTOM cast (percorso 2): il receiver chiama via
     * namespace le stesse funzioni del bridge AndroidJs. Mappatura fn→handler
     * (argomenti posizionali nell'array `args`). openSettings/openSiteInterface
     * NON vengono inoltrate: quei dialog vivono sul telefono.
     */
    private fun dispatchCastAndroidJs(fn: String, args: JSONArray) {
        Log.d(TAG, "cast androidjs: $fn(${args})")
        when (fn) {
            "bootReady", "retryConnection" -> jsBootReady()
            "loadTab" -> jsLoadTab(args.optString(0))
            "loadDetail" -> jsLoadDetail(args.optInt(0), args.optInt(1))
            "search" -> jsSearch(args.optString(0), args.optInt(1))
            "loadCategories" -> jsLoadCategories(args.optInt(0))
            "loadGenre" -> jsLoadGenre(args.optInt(0), args.optInt(1))
            "loadDetailSeason" -> jsLoadDetailSeason(args.optInt(0), args.optInt(1), args.optInt(2))
            "toggleMyList" -> jsToggleMyList(args.optInt(0))
            "getContinueWatching" -> jsGetContinueWatching()
            "play" -> scope.launch { onPlayRequested(args.optInt(0), args.optInt(1), args.optInt(2)) }
            "setActiveProfile" -> onProfileSelected(args.optString(0))
            else -> Log.w(TAG, "cast androidjs: fn ignota '$fn'")
        }
    }

    /** Il dominio corrente è uno StateFlow: ogni cambio (refresh online,
     *  promozione mirror) viene pushato al JS come `domainChanged`. */
    private fun observeDomainChanges() {
        scope.launch {
            domainRepo.current.collect { cfg ->
                val host = cfg?.primary()?.let { hostOf(it) } ?: ""
                if (host.isBlank() || host == pushedHost) return@collect
                pushedHost = host
                if (booted) {
                    push("domainChanged", JSONObject().put("host", host))
                }
            }
        }
    }

    // -- Profili (dati scoped per profilo SCtv) -----------------------------------

    /** Bridge setActiveProfile: gate JS al select del profilo, oppure boot JS
     *  che ripristina il profilo salvato in localStorage PRIMA di bootReady.
     *
     *  Il salvataggio dell'id è SINCRONO sul binder thread (volatile): il
     *  bootReady che segue — sullo stesso thread — vede già il profilo
     *  giusto e il primo pushBootPayload è scoped senza corse.
     *
     *  Se il boot è già stato pushato e il profilo È CAMBIATO, ripusha i
     *  dati scoped: progressChanged prima (rail "Continua a guardare") e
     *  myListChanged dopo (re-render della vista corrente col My List nuovo,
     *  senza rubare il fuoco). */
    private fun onProfileSelected(profileId: String) {
        val safe = sanitizeProfileId(profileId)
        // "p-default" (gate JS) coincide col bucket "default" del nativo:
        // dati legacy pre-profili e modalità sito restano visibili nel
        // profilo Predefinito (zero perdita all'aggiornamento).
        val normalized = if (safe == "p-default") DEFAULT_PROFILE_ID else safe
        val changed = normalized != currentProfileId
        currentProfileId = normalized
        if (booted && changed) {
            scope.launch {
                val cw = withContext(Dispatchers.IO) {
                    repo.continueWatchingJson(normalized)
                }
                push("progressChanged", JSONObject().put("continueWatching", cw))
                // FIX My List: oltre agli ids, gli item MINIMI dai record
                // FavoritesStore — i preferiti del nuovo profilo potrebbero
                // non essere nel CATALOG (slider home) e senza item la
                // griglia My List resterebbe vuota.
                val ids = withContext(Dispatchers.IO) { repo.myListIds(normalized) }
                val items = withContext(Dispatchers.IO) { repo.myListItems(normalized) }
                push("myListChanged", JSONObject().put("ids", ids).put("items", items))
            }
        }
    }

    // -- Push nativo→JS ------------------------------------------------------------

    /** Unico entry nativo→JS: envelope `{"t","req","d"}` valutata sulla pagina.
     *  SEMPRE dal thread UI (evaluateJavascript non è thread-safe).
     *  C3 (receiver custom cast): ogni evento viene MOLTIPLICATO verso il
     *  namespace cast — il receiver SCtv ricostruisce la vista compatta da
     *  quei dati (no-op senza sessione / su Fire TV: peek() è null). */
    private fun push(type: String, d: JSONObject, req: Int? = null) {
        val ev = JSONObject()
            .put("t", type)
            .put("req", req ?: JSONObject.NULL)
            .put("d", d)
        com.scviewer.fire.cast.SctvCastController.peek()?.relayReceive(ev.toString())
        runOnUiThread {
            if (BuildConfig.DEBUG) Log.d(TAG, "push $type")
            web.evaluateJavascript("window.__sctvReceive(" + ev.toString() + ")", null)
        }
    }

    private fun pushError(message: String, req: Int? = null) {
        push("error", JSONObject().put("message", message), req)
    }

    /** Fetch con recovery dominio: sul fallimento marca il dominio corrente e
     *  riprova UNA volta sul mirror successivo; se il mirror risponde lo
     *  promuove a primario (persistito) — l'evento domainChanged parte dal
     *  collector su domainRepo.current. */
    private suspend fun withRecovery(tag: String, fetch: (domain: String) -> JSONObject?): JSONObject? {
        val primary = domainRepo.currentDomain()
        val first = withContext(Dispatchers.IO) { fetch(primary) }
        if (first != null) return first
        Log.w(TAG, "$tag: fetch fallita su $primary — provo il mirror successivo")
        domainRepo.markDomainFailed(primary)
        val candidate = domainRepo.loadCandidate()
        if (candidate == primary) return null
        val retry = withContext(Dispatchers.IO) { fetch(candidate) }
        if (retry != null) {
            Log.i(TAG, "$tag: mirror $candidate funzionante — promosso a primario")
            domainRepo.promoteRedirect(candidate)
        }
        return retry
    }

    private fun pushBootPayload() {
        scope.launch {
            val uiMode = SettingsStore.getUiMode(this@SctvActivity)
            // LAVORO 1: quality nel payload boot (campo "quality" → classe
            // q-* su <html> lato JS) e nei CAP rail del repository.
            val payload = withRecovery("boot") { dom ->
                repo.bootPayload(dom, uiMode, currentProfileId, sctvQuality)
            }
            if (payload != null) {
                booted = true
                push("boot", payload)
            } else {
                pushBootFallback(uiMode)
            }
        }
    }

    /** Boot impossibile: se esiste uno snapshot dell'ultimo boot riuscito la
     *  UI resta utilizzabile (evento boot + toast), altrimenti error-card col
     *  bottone Riprova (retryConnection → pushBootPayload). */
    private fun pushBootFallback(uiMode: String) {
        scope.launch {
            val snap = withContext(Dispatchers.IO) {
                repo.readSnapshot(domainRepo.currentDomain(), uiMode, currentProfileId)
            }
            if (snap != null) {
                booted = true
                push("boot", snap)
                pushError("Offline: mostro l'ultimo catalogo in cache")
            } else {
                booted = false
                pushError("Connessione al sito non riuscita. Controlla la rete e riprova.")
            }
        }
    }

    private fun hostOf(url: String): String =
        runCatching { Uri.parse(url).host ?: "" }.getOrDefault("")

    // -- Handler bridge ------------------------------------------------------------

    /** M3 — player nativo + PLAYER V2 (direct-embed): risolve la puntata
     *  richiesta, risolve ORA l'embed vixcloud via OkHttp (token monouso
     *  ~10s → lo start deve essere immediato, piano §2.1) e apre
     *  PlayerActivity in direct mode con tutti i metadati per i record.
     *
     *  Risoluzione episodeId, in ordine:
     *   1. episodi già nel dettaglio in cache (stagione di default del sito);
     *   2. stagione richiesta via [SctvRepository.seasonEpisodes] (cache dei
     *      partial reload del modal, altrimenti fetch ~10KB) → id ESATTO;
     *   3. fallback: episodio introvabile e season>1 → ?s=<season> sul watch
     *      URL (il sito apre la stagione richiesta); per season<=1 il watch
     *      nudo lascia a PlayerActivity.resolveWatchUrl (prima stagione).
     *  Un film va direttamente a /it/watch/<id>.
     *
     *  Il watch URL (assoluto) va SEMPRE nell'extra "url": nei record
     *  (ProgressStore/HistoryStore) DEVE finire /it/watch/..., mai l'embed.
     */
    private fun onPlayRequested(titleId: Int, season: Int, episode: Int) {
        if (titleId <= 0) {
            pushError("Titolo non valido")
            return
        }
        scope.launch {
            val item = withRecovery("play($titleId)") { dom ->
                repo.detailPayload(titleId, dom, currentProfileId)
            }
            if (item == null) {
                pushError("Titolo non raggiungibile: controlla la connessione")
                return@launch
            }
            val type = item.optString("type", "movie")
            val name = item.optString("title")
            val domain = domainRepo.currentDomain()

            var episodeId = 0
            var episodesArr: JSONArray? = null
            if (type == "tv" && episode > 0) {
                episodesArr = item.optJSONArray("episodes")
                episodeId = episodeIdIn(episodesArr, season, episode)
                if (episodeId == 0 && season > 0) {
                    val eps = withContext(Dispatchers.IO) {
                        runCatching {
                            repo.seasonEpisodes(titleId, season, domain, currentProfileId)
                        }.getOrNull()
                    }?.second
                    episodeId = episodeIdIn(eps, season, episode)
                    if (episodeId > 0) episodesArr = eps
                }
            }

            // V2: nome dell'episodio corrente e PROSSIMO episodio (stessa
            // stagione, k+1 nell'array usato) — la card "Up Next" del player
            // direct ne ha bisogno, la catena riparte via onActivityResult.
            var episodeName = ""
            var nextEpisodeId = 0
            var nextEpisodeNumber = 0
            var nextEpisodeSeason = 0
            if (episodeId > 0 && episodesArr != null) {
                var k = -1
                for (i in 0 until episodesArr.length()) {
                    val e = episodesArr.optJSONObject(i) ?: continue
                    if (e.optInt("id") == episodeId) {
                        k = i
                        episodeName = e.optString("title")
                        break
                    }
                }
                if (k >= 0) {
                    val nx = episodesArr.optJSONObject(k + 1)
                    if (nx != null && nx.optInt("id", 0) > 0) {
                        nextEpisodeId = nx.optInt("id")
                        nextEpisodeNumber = nx.optInt("ep")
                        nextEpisodeSeason = nx.optInt("season", season.coerceAtLeast(1))
                    }
                }
            }

            val watchPath = when {
                type != "tv" -> "/it/watch/$titleId"
                episodeId > 0 -> "/it/watch/$titleId?e=$episodeId"
                season > 1 -> "/it/watch/$titleId?s=$season"
                else -> "/it/watch/$titleId"
            }
            // URL ASSOLUTO sul dominio corrente (SiteUrl.resolve rimonta il
            // path, come fa la modalità sito per i link salvati).
            val url = SiteUrl.resolve(watchPath, domain)
                ?: domain.trimEnd('/') + watchPath

            // Ripresa SOLO se il progresso salvato riguarda QUESTA puntata:
            // su serie si confrontano stagione e episodio (un progresso di
            // S1E3 non deve ripartire dentro S2E3 col medesimo numero); su
            // film season/episode valgono 1 (i null del record diventano 1).
            // Progresso SCOPATO al profilo corrente.
            val p = withContext(Dispatchers.IO) {
                ProgressStore.get(this@SctvActivity).get(titleId, currentProfileId)
            }
            val startPositionSec = if (p != null && p.isResumeable() &&
                (p.episode ?: 1) == episode && (p.season ?: 1) == season.coerceAtLeast(1)
            ) p.positionSec else 0

            // Poster per i record del player: progresso salvato → PosterCache
            // → dettaglio (stesso ordine con cui i rail popolano le card).
            val poster = p?.poster?.takeIf { it.isNotBlank() }
                ?: PosterCache.get(this@SctvActivity, titleId)
                ?: item.optString("posterUrl").takeIf { it.isNotBlank() }
                ?: ""
            // Slug dall'URL titolo del dettaglio (per il record WatchProgress;
            // il sito risolve comunque per id se lo slug fosse parziale).
            val slug = item.optString("url").substringAfter("-$titleId-", "")

            // C2 — PERCORSO CAST (phone mode, sessione attiva — reale o fake
            // debug): lo stream va AL RECEIVER via master playlist risolta
            // ora (StreamResolver). PlayerActivity NON si apre: nessun
            // wrapper load, nessuna watch page. Errore di risoluzione →
            // messaggio in UI e stop (no fallback silenzioso al player
            // locale: la sessione è collegata, il flusso resta coerente).
            val cast = if (com.scviewer.fire.cast.DeviceProfile.isPhone(this@SctvActivity)) {
                com.scviewer.fire.cast.SctvCastController.peek()
            } else null
            if (cast != null && cast.isSessionActive()) {
                val castMeta = com.scviewer.fire.cast.SctvCastController.CastMeta(
                    titleId = titleId,
                    season = if (type == "tv") season.coerceAtLeast(1) else 1,
                    episode = if (type == "tv") episode.coerceAtLeast(1) else 1,
                    title = name,
                    episodeName = episodeName,
                    type = type,
                    slug = slug,
                    watchPath = watchPath,
                    poster = poster,
                    nextEpisodeId = nextEpisodeId,
                    nextEpisodeSeason = nextEpisodeSeason,
                    nextEpisodeNumber = nextEpisodeNumber,
                    profileId = currentProfileId,
                    startPositionSec = startPositionSec,
                    lang = SettingsStore.getSctvAudioLang(this@SctvActivity)
                )
                Log.i(TAG, "play titleId=$titleId → CAST (start=${startPositionSec}s, " +
                    "nextEp=$nextEpisodeId)")
                val ok = cast.castPlay(castMeta)
                if (!ok) {
                    pushError("Stream non risolvibile per il cast: riprova")
                } else {
                    // Il telecomando RIAPRE SEMPRE dopo un play riuscito: se
                    // l'utente l'aveva chiuso e avvia un titolo dal browsing,
                    // il play parte sul TV ma il telecomando non riapparirebbe
                    // (gli eventi di sessione "started"/"resumed" sono già
                    // passati). singleTop (launchMode in manifest): se è già
                    // in cima — es. play del prossimo episodio dal telecomando
                    // stesso — niente doppione, parte onNewIntent che aggiorna
                    // titolo/poster dall'extra refresh.
                    startActivity(
                        Intent(this@SctvActivity,
                            com.scviewer.fire.cast.RemoteActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            .putExtra(com.scviewer.fire.cast.RemoteActivity.EXTRA_REFRESH, true)
                    )
                }
                return@launch
            }

            // V2 — direct embed: GET /it/iframe/<id>?episode_id=<ep> con i
            // cookie della sessione sito (WebView) e il Referer, estrai l'src
            // vixcloud e aggiungi &nogui=1. FALLIMENTO → errore e stop: il
            // flusso watch-page resta per la modalità sito, niente fallback
            // in cascata qui (piano §4).
            val embedUrl = withContext(Dispatchers.IO) {
                resolveEmbedDirect(titleId, episodeId, domain)
            }
            if (embedUrl.isNullOrBlank()) {
                Log.w(TAG, "play $titleId: embed vixcloud non risolvibile")
                pushError("Player non disponibile: riprova tra qualche istante")
                return@launch
            }

            Log.i(TAG, "play titleId=$titleId → $watchPath (start=${startPositionSec}s, " +
                "direct, nextEp=$nextEpisodeId)")
            pendingPlayTitleId = titleId
            localPlayerOpen = true
            startActivityForResult(
                Intent(this@SctvActivity, PlayerActivity::class.java).apply {
                    putExtra("url", url)                 // watch URL assoluto (record/cronologia)
                    putExtra("embedUrl", embedUrl)       // embed vixcloud +nogui → direct mode
                    putExtra("title", name)
                    putExtra("titleId", titleId)
                    putExtra("startPositionSec", startPositionSec)
                    putExtra("season", if (type == "tv") season.coerceAtLeast(1) else 1)
                    putExtra("episode", if (type == "tv") episode.coerceAtLeast(1) else 1)
                    putExtra("type", type)
                    putExtra("slug", slug)
                    putExtra("episodeName", episodeName)
                    putExtra("nextEpisodeId", nextEpisodeId)
                    putExtra("nextEpisodeSeason", nextEpisodeSeason)
                    putExtra("nextEpisodeNumber", nextEpisodeNumber)
                    putExtra("poster", poster)
                    putExtra("profileId", currentProfileId)   // scope record player
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                REQ_PLAYER
            )
        }
    }

    /** PLAYER V2 — GET del wrapper {domain}/it/iframe/<titleId>[?episode_id=]
     *  (thread IO) e estrazione dell'src dell'iframe vixcloud con &nogui=1
     *  (il bundle nogui cambia: controls:false, autostart+mute, nessun
     *  force-720p necessario — max 720p comunque senza canPlayFHD).
     *  Il token dentro l'src è MONOUSO e scade in ~10s: chiamare SUBITO
     *  prima dello start, senza delay intermedi. Referer OBBLIGATORIO. */
    private fun resolveEmbedDirect(titleId: Int, episodeId: Int, domain: String): String? {
        val cookie = runCatching { CookieManager.getInstance().getCookie(domain) }.getOrNull()
        val wrapperUrl = buildString {
            append(domain.trimEnd('/'))
            append("/it/iframe/")
            append(titleId)
            if (episodeId > 0) append("?episode_id=").append(episodeId)
        }
        return runCatching {
            val b = Request.Builder().url(wrapperUrl)
                .header("User-Agent", SCWebViewClient.DESKTOP_UA)
                .header("Accept", "text/html")
                .header("Referer", domain.trimEnd('/') + "/")
            cookie?.takeIf { it.isNotBlank() }?.let { b.header("Cookie", it) }
            resolveClient.newCall(b.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "iframe fetch HTTP ${resp.code} ($wrapperUrl)")
                    return@use null
                }
                val html = resp.body?.string() ?: return@use null
                Regex("src=\"(https://vixcloud[^\"]+)\"").find(html)
                    ?.groupValues?.get(1)
                    ?.replace("&amp;", "&")
                    ?.let { src ->
                        val sep = if (src.contains("?")) "&" else "?"
                        src + sep + "nogui=1"
                    }
            }
        }.onFailure { Log.w(TAG, "resolveEmbedDirect failed", it) }.getOrNull()
    }

    /** Client HTTP per la resolve dell'embed (V2). Timeout stretti: il token
     *  vixcloud del wrapper scade in ~10s. */
    private val resolveClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** PLAYER V2 — catena "prossimo episodio": PlayerActivity termina con
     *  RESULT_OK + nextEpisodeId (più stagione/numero) → riparte
     *  onPlayRequested pulita (risoluzione episodio + embed freschi). */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PLAYER) localPlayerOpen = false
        if (requestCode != REQ_PLAYER || resultCode != RESULT_OK || data == null) return
        val nextId = data.getIntExtra("nextEpisodeId", 0)
        val ns = data.getIntExtra("nextEpisodeSeason", 0)
        val ne = data.getIntExtra("nextEpisodeNumber", 0)
        if (nextId > 0 && ns > 0 && ne > 0) {
            Log.i(TAG, "player → prossimo episodio S$ns:E$ne (id=$nextId) di $pendingPlayTitleId")
            onPlayRequested(pendingPlayTitleId, ns, ne)
        }
    }

    /** Id della puntata richiesta dentro un array episodes del repository
     *  (shape {ep, id, season, ...}): match sul numero episodio, con confronto
     *  sulla stagione quando entrambi i valori sono significativi. */
    private fun episodeIdIn(eps: JSONArray?, season: Int, episode: Int): Int {
        if (eps == null) return 0
        for (i in 0 until eps.length()) {
            val e = eps.optJSONObject(i) ?: continue
            if (e.optInt("ep") != episode) continue
            val es = e.optInt("season", 1)
            if (season > 0 && es > 0 && season != es) continue
            return e.optInt("id")
        }
        return 0
    }

    private fun openNativeSettings() {
        SettingsDialog(
            this,
            onSaved = { modeChanged ->
                // Il cap qualità vive in HlsRewriter (già attivo per il sito).
                HlsRewriter.maxHeightOverride = SettingsStore.qualityCapHeight(this)
                // Dal dialog aperto dentro SCtv l'unico cambio possibile è
                // verso il sito: routing immediato come da sidebar sito.
                if (modeChanged) switchToSite()
            }
        ).show()
    }

    private fun switchToSite() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
        )
        finish()
    }

    // -- D-pad → handleRemoteAction / BACK → __sctvBackState ------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val action = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> "up"
                KeyEvent.KEYCODE_DPAD_DOWN -> "down"
                KeyEvent.KEYCODE_DPAD_LEFT -> "left"
                KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> "ok"
                else -> null
            }
            if (action != null) {
                web.evaluateJavascript("handleRemoteAction('$action')", null)
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                // Il JS decide: 'exit' → chiudiamo l'app, 'stay' → ha già
                // gestito (modal aperta, tab diversa da home, gate profili).
                web.evaluateJavascript(
                    "window.__sctvBackState ? __sctvBackState() : 'exit'"
                ) { result ->
                    if (result?.contains("exit") == true) finish()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // -- Ciclo di vita ---------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        web.onResume()
        // Re-check aggiornamento anche al ritorno in primo piano: la proposta
        // persistita (available_tag) rispunta il chip subito; il gate 12h
        // (prefs "scviewer_updates") rende economica la fetch — al più una
        // chiamata GitHub ogni 12 ore, la stessa finestra condivisa col sito.
        checkUpdates()
        // Mantieni il dominio fresco anche qui (la modalità sito lo fa in
        // onResume): il collector pusha domainChanged se cambia.
        scope.launch { runCatching { domainRepo.refreshOnline() } }
        // Rail "Continua a guardare" aggiornata a ogni ritorno dal player
        // (dati del profilo corrente).
        if (booted) {
            scope.launch {
                val cw = withContext(Dispatchers.IO) {
                    repo.continueWatchingJson(currentProfileId)
                }
                push("progressChanged", JSONObject().put("continueWatching", cw))
            }
        }
    }

    override fun onPause() {
        super.onPause()
        web.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cast (C1): detach del controller (rimuove listener di sessione).
        // La SESSIONE cast non viene chiusa: il receiver continua a suonare.
        com.scviewer.fire.cast.SctvCastController.peek()?.detach(castHost)
        // Chip aggiornamento: via dal content (nessun riferimento a view di
        // activity morte; il download in corso prosegue nello scope del
        // manager e l'installer arriverà al ritorno in primo piano).
        UpdateManager.removeChip(this)
        scope.cancel()
        web.apply {
            stopLoading()
            removeAllViews()
            destroy()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            web.clearCache(false)
        }
    }
}
