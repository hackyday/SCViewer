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
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.scviewer.fire.bridge.SctvBridge
import com.scviewer.fire.bridge.SctvRepository
import com.scviewer.fire.data.DomainRepository
import com.scviewer.fire.data.ProgressStore
import com.scviewer.fire.data.SettingsStore
import com.scviewer.fire.data.SiteUrl
import com.scviewer.fire.network.HlsRewriter
import com.scviewer.fire.ui.SettingsDialog
import com.scviewer.fire.work.DomainRefreshScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

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
 * Player NATIVO delegato: startPlayback (JS) → AndroidJs.play → qui si
 * risolve il watch URL (detail cache → episodeId, fallback ?s=) e si apre
 * PlayerActivity con titleId + startPositionSec (ripresa da ProgressStore).
 * NIENTE player HTML5 nella WebView.
 */
class SctvActivity : Activity() {

    companion object {
        private const val TAG = "SctvActivity"
        private const val BRIDGE_VERSION = 1
    }

    private lateinit var web: WebView
    private lateinit var loadingOverlay: View
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val domainRepo by lazy { DomainRepository.get(this) }
    private val repo by lazy { SctvRepository(this) }

    /** true dopo il push del primo evento boot: evita push progressChanged
     *  verso una pagina che non ha ancora il dispatcher attivo. */
    @Volatile private var booted = false

    /** Ultimo host pushato al JS: evita domainChanged ridondanti. */
    @Volatile private var pushedHost = ""

    // -- Creazione ---------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sctv)
        web = findViewById(R.id.sctvWeb)
        loadingOverlay = findViewById(R.id.sctvLoading)
        HlsRewriter.maxHeightOverride = SettingsStore.qualityCapHeight(this)
        configureWebView()
        // Cache-busting via versionCode: aggiornare l'asset invalida la URL.
        web.loadUrl("file:///android_asset/sctv/index.html?v=" + BuildConfig.VERSION_CODE)
        // Refresh periodico del dominio anche per l'interfaccia SCtv.
        DomainRefreshScheduler.schedule(this)
        observeDomainChanges()
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
            onBootReady = { scope.launch { pushBootPayload() } },
            onLoadTab = { tab ->
                scope.launch {
                    val payload = withRecovery("loadTab($tab)") { dom ->
                        repo.tabPayload(tab, dom)
                    }
                    if (payload != null) push("catalog", payload)
                    else pushError("Tab non raggiungibile: controlla la connessione")
                }
            },
            onLoadDetail = { titleId, req ->
                scope.launch {
                    val item = withRecovery("loadDetail($titleId)") { dom ->
                        repo.detailPayload(titleId, dom)
                    }
                    if (item != null) {
                        push("titleDetail", JSONObject().put("item", item), req)
                    } else {
                        pushError("Dettaglio non disponibile: controlla la connessione", req)
                    }
                }
            },
            onSearch = { query, req ->
                scope.launch {
                    val payload = withRecovery("search('$query')") { dom ->
                        repo.searchPayload(query, dom)
                    }
                    if (payload != null) push("searchResults", payload, req)
                    else pushError("Ricerca non riuscita: controlla la connessione", req)
                }
            },
            onLoadDetailSeason = { titleId, season, req ->
                scope.launch {
                    val result = withRecovery("loadDetailSeason($titleId,S$season)") { dom ->
                        repo.seasonEpisodes(titleId, season, dom)?.let { (s, eps) ->
                            JSONObject().put("season", s).put("episodes", eps)
                        }
                    }
                    if (result != null) push("titleSeason", result, req)
                    else pushError("Stagione non disponibile: controlla la connessione", req)
                }
            },
            onToggleMyList = { titleId ->
                scope.launch {
                    val result = withRecovery("toggleMyList($titleId)") { dom ->
                        repo.toggleMyList(titleId, dom)
                    }
                    if (result != null) {
                        push("myListChanged", result)
                    } else {
                        pushError("Impossibile aggiornare La mia lista")
                    }
                }
            },
            onGetContinueWatching = {
                scope.launch {
                    val cw = withContext(Dispatchers.IO) { repo.continueWatchingJson() }
                    push("progressChanged", JSONObject().put("continueWatching", cw))
                }
            },
            onReportProgress = { titleId, season, episode, pos, dur ->
                Log.i(TAG, "reportProgress($titleId, S$season:E$episode, $pos/$dur): M3")
            },
            onPlay = { titleId, season, episode ->
                scope.launch { onPlayRequested(titleId, season, episode) }
            },
            onOpenSettings = { scope.launch { openNativeSettings() } },
            onOpenSiteInterface = { scope.launch { switchToSite() } },
            onRetryConnection = { scope.launch { pushBootPayload() } }
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

    // -- Push nativo→JS ------------------------------------------------------------

    /** Unico entry nativo→JS: envelope `{"t","req","d"}` valutata sulla pagina.
     *  SEMPRE dal thread UI (evaluateJavascript non è thread-safe). */
    private fun push(type: String, d: JSONObject, req: Int? = null) {
        val ev = JSONObject()
            .put("t", type)
            .put("req", req ?: JSONObject.NULL)
            .put("d", d)
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
            val payload = withRecovery("boot") { dom -> repo.bootPayload(dom, uiMode) }
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
                repo.readSnapshot(domainRepo.currentDomain(), uiMode)
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

    /** M3 — player nativo: risolve il watch URL dalla puntata richiesta e
     *  apre PlayerActivity con titleId/startPositionSec per la ripresa.
     *
     *  Risoluzione episodeId, in ordine:
     *   1. episodi già nel dettaglio in cache (stagione di default del sito);
     *   2. stagione richiesta via [SctvRepository.seasonEpisodes] (cache dei
     *      partial reload del modal, altrimenti fetch ~10KB) → id ESATTO;
     *   3. fallback: episodio introvabile e season>1 → ?s=<season> sul watch
     *      URL (il sito apre la stagione richiesta); per season<=1 il watch
     *      nudo lascia a PlayerActivity.resolveWatchUrl (prima stagione).
     *  Un film va direttamente a /it/watch/<id>.
     */
    private fun onPlayRequested(titleId: Int, season: Int, episode: Int) {
        if (titleId <= 0) {
            pushError("Titolo non valido")
            return
        }
        scope.launch {
            val item = withRecovery("play($titleId)") { dom -> repo.detailPayload(titleId, dom) }
            if (item == null) {
                pushError("Titolo non raggiungibile: controlla la connessione")
                return@launch
            }
            val type = item.optString("type", "movie")
            val name = item.optString("title")
            val domain = domainRepo.currentDomain()

            var episodeId = 0
            if (type == "tv" && episode > 0) {
                episodeId = episodeIdIn(item.optJSONArray("episodes"), season, episode)
                if (episodeId == 0 && season > 0) {
                    val eps = withContext(Dispatchers.IO) {
                        runCatching { repo.seasonEpisodes(titleId, season, domain) }.getOrNull()
                    }?.second
                    episodeId = episodeIdIn(eps, season, episode)
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
            val p = withContext(Dispatchers.IO) {
                ProgressStore.get(this@SctvActivity).get(titleId)
            }
            val startPositionSec = if (p != null && p.isResumeable() &&
                (p.episode ?: 1) == episode && (p.season ?: 1) == season.coerceAtLeast(1)
            ) p.positionSec else 0

            Log.i(TAG, "play titleId=$titleId → $watchPath (start=${startPositionSec}s)")
            startActivity(
                Intent(this@SctvActivity, PlayerActivity::class.java).apply {
                    putExtra("url", url)
                    putExtra("title", name)
                    putExtra("titleId", titleId)
                    putExtra("startPositionSec", startPositionSec)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
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
        // Mantieni il dominio fresco anche qui (la modalità sito lo fa in
        // onResume): il collector pusha domainChanged se cambia.
        scope.launch { runCatching { domainRepo.refreshOnline() } }
        // Rail "Continua a guardare" aggiornata a ogni ritorno dal player.
        if (booted) {
            scope.launch {
                val cw = withContext(Dispatchers.IO) { repo.continueWatchingJson() }
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
