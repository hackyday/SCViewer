package com.scviewer.fire.bridge

import android.webkit.JavascriptInterface

/**
 * Contratto JS→nativo della nuova interfaccia SCtv (piano §2).
 *
 * Registrato in [com.scviewer.fire.SctvActivity] con
 * `addJavascriptInterface(bridge, "AndroidJs")`: ogni metodo è chiamato dal
 * BINDER thread della WebView, quindi NESSUN accesso diretto a UI/WebView —
 * ogni handler delega alla lambda corrispondente, che l'Activity implementa
 * lanciando coroutine sul proprio scope Main.
 *
 * M1: i dati sono DEMO — solo bootReady (push boot), play (placeholder) e
 * openSettings (dialog nativo) hanno comportamento reale; i restanti
 * handler sono no-op in attesa di SctvRepository (M2).
 *
 * Il percorso nativo→JS è l'unico entry `window.__sctvReceive(ev)` (envelope
 * `{"t":"<tipo>","req":<int|null>,"d":{...}}`), pushato dall'Activity.
 */
class SctvBridge(
    /** JS ha finito di caricarsi: momento per il push dell'evento `boot`. */
    private val onBootReady: () -> Unit,
    /** Play di un titolo (film) o episodio (serie): player nativo (M3). */
    private val onPlay: (titleId: Int, season: Int, episode: Int) -> Unit,
    /** Cambio tab: home/tv/movie/new (dati reali, M2). */
    private val onLoadTab: (tab: String) -> Unit,
    /** Dettaglio titolo: episodi popolati + `titleDetail` push con `req`. */
    private val onLoadDetail: (titleId: Int, req: Int) -> Unit,
    /** Ricerca server: `searchResults` push con `req` (M2). */
    private val onSearch: (query: String, req: Int) -> Unit,
    /** Tab Categorie: generi REALI dell'archivio (/it/archive → props.genres)
     *  + prime 60 titoli; push evento `categories` {req, genres, catalog}.
     *  EXTRA rispetto al contratto piano §2, come loadDetailSeason. */
    private val onLoadCategories: (req: Int) -> Unit,
    /** Filtro genere dell'archivio (/it/archive?genre[]=<id>&page=1):
     *  push evento `genreData` {req, genreId, catalog}. EXTRA rispetto al
     *  contratto piano §2, come loadDetailSeason. */
    private val onLoadGenre: (genreId: Int, req: Int) -> Unit,
    /** Cambio stagione nel modal dettaglio: partial reload Inertia (~10KB) e
     *  push `titleSeason` {req, season, episodes} (M2 — metodo EXTRA rispetto
     *  al contratto piano §2, che non copriva il season switch; documentato
     *  come unica aggiunta al contratto). */
    private val onLoadDetailSeason: (titleId: Int, season: Int, req: Int) -> Unit,
    /** My List ↔ FavoritesStore (M2; in M1 il JS aggiorna in locale). */
    private val onToggleMyList: (titleId: Int) -> Unit,
    /** Rail "Continua a guardare": `progressChanged` push (M2). */
    private val onGetContinueWatching: () -> Unit,
    /** Riservato al player nativo (M3): non chiamato dal JS v1. */
    private val onReportProgress: (
        titleId: Int, season: Int, episode: Int, positionSec: Int, durationSec: Int
    ) -> Unit,
    /** Apri il SettingsDialog nativo sopra l'Activity. */
    private val onOpenSettings: () -> Unit,
    /** Switch alla modalità "sito" (MainActivity, CLEAR_TASK). */
    private val onOpenSiteInterface: () -> Unit,
    /** Riprova connessione dopo l'evento `error` (re-push boot in M1). */
    private val onRetryConnection: () -> Unit,
    /** Selezione profilo dal gate JS: l'Activity memorizza l'id corrente e,
     *  se il boot è già avvenuto, ripusha i dati scoped (myListChanged +
     *  progressChanged) per ridisegnare la UI col profilo scelto. Chiamato
     *  anche PRIMA di bootReady (profilo salvato in localStorage) così anche
     *  il primo payload è scoped. */
    private val onSetActiveProfile: (profileId: String) -> Unit
) {

    @JavascriptInterface
    fun bootReady() {
        onBootReady()
    }

    @JavascriptInterface
    fun loadTab(tab: String) {
        onLoadTab(tab)
    }

    @JavascriptInterface
    fun loadDetail(titleId: Int, req: Int) {
        onLoadDetail(titleId, req)
    }

    @JavascriptInterface
    fun search(query: String, req: Int) {
        onSearch(query, req)
    }

    /** EXTRA rispetto al piano §2: tab Categorie con generi reali
     *  (/it/archive → props.genres) + prime 60 titoli → push evento
     *  `categories` {req, genres:[{id,name}], catalog:[item]}, come
     *  loadDetailSeason. */
    @JavascriptInterface
    fun loadCategories(req: Int) {
        onLoadCategories(req)
    }

    /** EXTRA rispetto al piano §2: filtro genere dell'archivio
     *  (/it/archive?lang=it&genre[]=<id>&page=1) → push evento `genreData`
     *  {req, genreId, catalog:[item]}, come loadDetailSeason. */
    @JavascriptInterface
    fun loadGenre(genreId: Int, req: Int) {
        onLoadGenre(genreId, req)
    }

    /** EXTRA rispetto al piano §2: cambio stagione nel modal (partial reload
     *  Inertia) → push evento `titleSeason` {req, season, episodes}. */
    @JavascriptInterface
    fun loadDetailSeason(titleId: Int, season: Int, req: Int) {
        onLoadDetailSeason(titleId, season, req)
    }

    @JavascriptInterface
    fun toggleMyList(titleId: Int) {
        onToggleMyList(titleId)
    }

    @JavascriptInterface
    fun getContinueWatching() {
        onGetContinueWatching()
    }

    @JavascriptInterface
    fun play(titleId: Int, season: Int, episode: Int) {
        onPlay(titleId, season, episode)
    }

    @JavascriptInterface
    fun reportProgress(titleId: Int, season: Int, episode: Int, positionSec: Int, durationSec: Int) {
        onReportProgress(titleId, season, episode, positionSec, durationSec)
    }

    @JavascriptInterface
    fun openSettings() {
        onOpenSettings()
    }

    @JavascriptInterface
    fun openSiteInterface() {
        onOpenSiteInterface()
    }

    @JavascriptInterface
    fun retryConnection() {
        onRetryConnection()
    }

    /** Selezione profilo (gate JS / boot con profilo salvato): EXTRA rispetto
     *  al contratto piano §2, come loadDetailSeason. L'id arriva dal
     *  localStorage JS ("p-<timestamp>"); l'Activity lo sanifica e mappa
     *  "p-default" → "default" (bucket legacy/modalità sito). */
    @JavascriptInterface
    fun setActiveProfile(profileId: String) {
        onSetActiveProfile(profileId)
    }
}
