# SCtv — PIANO DI IMPLEMENTAZIONE CONSOLIDATO

Fonti (analisi complete effettuate, consultabili in cronologia agenti):
1. Spec funzionale interfaccia: `docs/sctv/sctv-interface.html` (fonte normativa UI, 2142 righe)
2. Architettura integrazione: SctvActivity + bridge + toggle modalità + M1-M5 (questo documento §2-§5)
3. Mappa dati/API verificata con curl (2026-11-20): `docs/dpad-target-map.md` + §4 di questo documento

L'implementatore lavora SOLO da questo documento + i file citati.

---

## 1. DECISIONI ARCHITETTURALI (non negoziabili)

1. **Nuova `SctvActivity`** (non riadattare TvBrowseActivity): WebView dedicata fullscreen che carica `file:///android_asset/sctv/index.html?v=<VERSION_CODE>`. TvBrowseActivity verrà rimossa in M5 (resta per riferimento pattern: WebView programmatica, D-pad→handleRemoteAction, `__sctvBackState`, useWideViewPort+loadWithOverviewMode+textZoom=100 per lo stick).
2. **Launcher routing**: `LauncherRouterActivity` (Theme.NoDisplay, 15 righe) riceve l'intent-filter MAIN/LAUNCHER/LEANBACK_LAUNCHER (spostato da MainActivity, che diventa exported=false) e avvia `SctvActivity` o `MainActivity` secondo `SettingsStore.isSctvMode()`. Zero flash: NoDisplay non crea finestra.
3. **Player NATIVO delegato**: `startPlayback` → `AndroidJs.play(titleId, season, episode)` → SctvActivity risolve (detail cache → episodeId) → `PlayerActivity` con extras `titleId` + `startPositionSec` + `url` + `title`. NIENTE player HTML5 nella WebView SCtv (rimuovere #video-player-overlay e tutte le funzioni player dal file). PlayerActivity guadagna: shouldInterceptRequest (HlsRewriter+AdBlocker, copia da SCWebViewClient righe 41-76), resume seek (pendingSeek → requestSeekTo con retry 3.5s/8s dopo video attivo), ProgressStore.save + HistoryStore.add + PosterCache.put in onProgress.
4. **Push nativo→JS** via UNICO entry `window.__sctvReceive(ev)` con envelope `{"t":"<tipo>","req":<int|null>,"d":{...}}`; correlazione con `req` (contatore JS). JS→nativo via `SctvBridge` (addJavascriptInterface "AndroidJs"), metodi @JavascriptInterface, handler lanciano coroutine (niente UI sul binder thread).
5. **Tailwind COMPILATO a build-time** (`npx tailwindcss -o sctv.css --content index.html`, config inline spostato in tailwind.config.js) — MAI il CDN runtime (offline = interfaccia spogliata). **Font bundle WOFF2 locali** (Bebas Neue + Plus Jakarta Sans 400-800, @font-face locale, font-display:swap). Script `tools/build-sctv.sh` per rigenerare il CSS.
6. **Nessun fetch diretto dalla WebView** (origin file:// = CORS null bloccato): TUTTI i dati via bridge. `<img src="https://cdn...">` è ok (no CORS). domStorageEnabled=true per localStorage profili.

## 2. CONTRATTO BRIDGE

`app/src/main/java/com/scviewer/fire/bridge/SctvBridge.kt` (metodi JS→N, esatti):
```kotlin
@JavascriptInterface bootReady()
@JavascriptInterface loadTab(tab: String)                    // "home"|"tv"|"movie"|"new"
@JavascriptInterface loadDetail(titleId: Int, req: Int)
@JavascriptInterface search(query: String, req: Int)
@JavascriptInterface toggleMyList(titleId: Int)
@JavascriptInterface getContinueWatching()
@JavascriptInterface play(titleId: Int, season: Int, episode: Int)
@JavascriptInterface reportProgress(...)                      // riservato, non chiamato v1
@JavascriptInterface openSettings()                           // SettingsDialog nativo sopra SctvActivity
@JavascriptInterface openSiteInterface()                      // switch ui_mode → MainActivity (CLEAR_TASK)
@JavascriptInterface retryConnection()
```

Eventi N→JS (push, envelope §1.4):
- `boot` (dopo bootReady): `{bridgeVersion:1, uiMode, domainHost, myList:[ids], continueWatching:[cw], catalog:[items], rows:{home:[{label,ids,isTop10}],tv:[...],movie:[...]}, heroTitleId}` — cw-item: `{id:"site-<id>", titleId, name, tag, progress, label, poster, season, episode, positionSec, durationSec, url}`
- `catalog` (per tab): `{tab, catalog:[items], rows:[...]}`; tab "new": `{tab:"new", top10:[items]}` (comingSoon v1 vuoto)
- `titleDetail` (req): `{item}` con episodes[] popolati (incl. `id` episodio) + season field
- `searchResults` (req): `{query, catalog:[items]}`
- `myListChanged`: `{ids}`
- `progressChanged`: `{continueWatching:[cw]}`
- `domainChanged`: `{host}`; `error`: `{message}`

Shape item (CATALOG del file, + campi bridge): `{id:"site-<id>", titleId, title, type:'tv'|'movie', isOriginal:false, isKids, top10Rank?, match?, year?, maturity?, durationLabel?, badges:[], genres:[], synopsis, cast:[], creator?, posterUrl, backdropUrl, url:"/it/titles/<id>-<slug>", comingSoon:false, seasons, episodes:[{ep, id, season, title, duration, desc, streamUrl, thumb}]}`.

## 3. DATI REALI (mappa API verificata — estendere SiteCatalog, non reinventare)

- Base client: `SiteCatalog.fetchProps(path, domain)` (regex data-page + unescape) + `parseTitle` → TvTitle. Estendere parseTitle o aggiungere mappers: genres=title.genres[].name; cast=main_actors[].name (pivot.order); creator=main_directors[].name; maturity=title.age+"+"; badges=[quality, "DUB" se dub_ita, "SUB" se sub_ita]; durationLabel= runtime+"min" (film) / seasons_count+" stagioni".
- **Immagini**: `cdn_url + "/images/" + images[type].filename` — types: poster (card 2:3), cover (thumb 16:9), background (backdrop), logo. `pickImage`/`imagesUrl` già in SiteCatalog.
- **Home**: `fetchProps("/")` → props.title (hero, con logo + preview.embed_url), props.sliders[] (name: trending/latest/top10 con top10_index → top10Rank=index), props.genres. Serie/film: `/it/tv-shows`, `/it/movies` (top10 dedicate).
- **Dettaglio**: `fetchProps("/it/titles/<id>-<slug>")` → title completo; **episodi in props.loadedSeason** (NON in title.seasons[] che ha solo metadati); more-like-this = props.sliders[0].titles.
- **Cambio stagione (partial, 10KB)**: `GET /it/titles/<id>-<slug>/season-<N>` con headers X-Inertia:true, X-Inertia-Version:<version>, X-Inertia-Partial-Component:"Titles/Title", X-Inertia-Partial-Data:"loadedSeason", X-Requested-With:XMLHttpRequest → JSON {props:{loadedSeason:{episodes:[{id, number, name, plot, duration(min), images[cover]}]}}}. Su version scaduta torna HTML intero → riparsare data-page.
- **Search**: `/it/search?q=<q>` → Titles/Browse con titles[] (60, pertinenza). **Browse/genre**: `/it/archive` (+ filtri genre[]/type/sort/year ecc., paginazione Laravel `?page=N` con Accept: application/json — il server serve oltre pagina 17).
- **Stream**: delegato a PlayerActivity (watch page in WebView, warm-up cookie). Se in futuro serve StreamResolver nativo (ExoPlayer): watch props.embedUrl → iframe vixcloud (token MONOUSO 10s) → window.masterPlaylist → m3u8 master (token 60gg, cacheabile per scws_id) → h=1 prova+fallback 720p. v1 NON implementare.
- **Locali**: My List = FavoritesStore (url→titleId da `/it/titles/(\d+)-`); Continue Watching = ProgressStore.resumeable() (label = displayLabel(), progress = positionSec/durationSec); scritti da ENTRAMBE le modalità (PlayerActivity salva in M3).
- **Rischi**: rotazione domini → path relativi rimontati su currentDomain (DomainRepository.currentDomain/markDomainFailed); CDN immagini segue il dominio attivo (rileggere cdn_url dalle props); rate 1-2 req/s + cache; Cloudflare → fetchProps null → evento error.
- **Futuro (fuori v1)**: backend TV `/api/tv/*` (config pubblico, pairing QR, continue-watching/watchlist/history server-side).

## 4. FIX TV OBBLIGATORI sull'HTML (dal file docs, prima di tutto)

1. Rimuovere l'handler `mousemove` che cancella il fuoco (tv: mousemove sintetici = D-pad "morto").
2. `#profile-editor-modal`: aggiungere `tv-focusable` a preset avatar, Save/Cancel/Delete, toggle kids (click()); nome profilo via IME (AndroidJs.setTyping(true) — aggiungere al bridge) o mini-tastiera.
3. Player overlay: RIMOSSO interamente (con le sue funzioni e le sue voci in refreshFocusableElements).
4. `<select>` stagioni → bottoni tv-focusable ◀ Season n/total ▶ → changeDetailSeason(n±1); episodi con campo `season` + filtro `ep.season === detailSelectedSeason`.
5. Bottoni `#sound-toggle-profile` e header Search: aggiungere tv-focusable (o rimuovere, la sidebar li copre).
6. Focus iniziale automatico dopo renderCurrentView/open modal: `setTVFocus(focusableList[0])` (in TV build), tranne durante typing in search.
7. `startPlayback` → bridge play; azzerare il fuoco corrente al passaggio al player nativo (onPause della WebView lo congela).
8. Remote virtuale: nascondere su TV (solo se !window.AndroidJs).
9. New&Popular: ordinare top10 per top10Rank.
10. Meta viewport width=1280 + useWideViewPort/loadWithOverviewMode/textZoom=100 (pattern TvBrowseActivity).
11. Persistenza profili in localStorage (sctv.profiles / sctv.currentProfileId) + profilo default "Predefinito" al primo boot.
12. Loading state: tra boot e payload, spinner nel view-container (mai "No Titles Available" flash).
13. Continue Watching rail: aggiornamento MIRATO (#row-continue) su progressChanged, niente renderCurrentView integrale (fuoco).
14. Search: aggiornamento mirato input+griglia risultati (mai renderCurrentView integrale per tasto).
15. AudioEngine: persist toggle via bridge (boot payload `settings.uiAudio`).

## 5. MILESTONE (ordine e criteri di verifica)

**M1 — Fondazioni (SCtv lanciabile, dati finti)**
1. SettingsStore: +KEY_UI_MODE ("sctv"|"site", default "sctv"), getUiMode/setUiMode/isSctvMode.
2. strings.xml: settings_ui_label/settings_ui_sctv/settings_ui_site.
3. dialog_settings.xml + SettingsDialog.kt: segmented uiModeRow (SCtv/Sito) IN CIMA; sezioni esistenti (puntatore/UA) visibili SOLO in mode=site; qualità sempre visibile; onSaved(modeChanged).
4. LauncherRouterActivity + manifest (intent-filter MOSSO al router; MainActivity exported=false; +SctvActivity landscape configChanges).
5. activity_sctv.xml (WebView fullscreen + loading overlay stile esistente).
6. SctvActivity (ciclo vita completo §1.2: onResume web.onResume + push progressChanged; onPause web.onPause; onDestroy destroy; onTrimMemory clearCache; dispatchKeyEvent D-pad→handleRemoteAction + BACK→__sctvBackState callback stay/exit).
7. tools/build-sctv.sh + assets/sctv/{index.html,sctv.css,fonts/*}: file docs adattato (fix TV §4, Tailwind compilato, font locali, bridge layer + DEMO data: 12 item con serie/film/top10/comingSoon).
8. bridge/SctvBridge.kt completo.
VERIFICA: launch→splash→gate→tutti i tab navigabili D-pad; switch Sito↔SCtv da impostazioni entrambe le direzioni senza flash e senza perdita; modalità sito intoccata (page_inject funzionante); build release passa proguard.

**M2 — Dati reali**
1. bridge/SctvRepository.kt (o package sctv/: SiteClient, CatalogSource, TitleDetailSource, LocalSources —§3) con cache LRU + snapshot offline.
2. SctvActivity: handler cablati (coroutine, domainRepo recovery, push error), DomainRefreshScheduler.schedule anche qui.
3. index.html: dispatcher completo (boot/catalog/titleDetail/searchResults/myListChanged/progressChanged), righe native, detail con episodi reali (partial reload cambio stagione), search bridge, My List ↔ FavoritesStore, profili localStorage.
VERIFICA: home con poster reali entro ~3s (cache calda); tab serie/film; detail con cast/episodi/season-switch; search server; My List persiste; offline → banner + Riprova; cambio dominio → mirror.

**M3 — Player**
1. PlayerActivity: extras titleId/startPositionSec; shouldInterceptRequest (HlsRewriter+AdBlocker); resume seek; ProgressStore.save+HistoryStore.add+PosterCache.put in onProgress (idempotente con modalità sito).
2. SctvActivity: onPlay (resolve episodeId da detail cache → watch URL con ?e= o ?s=; startPositionSec da ProgressStore) + onResume push progressChanged + openSettings → SettingsDialog con onSaved che aggiorna HlsRewriter.maxHeightOverride.
3. index.html: progressChanged → renderContinueRail() mirata.
VERIFICA: play film/serie/episodio specifico; resume al punto (±5s); skip intro nativo; BACK → rail aggiornata senza riavvio; cap qualità funziona.

**M4 — Pulizia e release**
1. Rimozione TvBrowseActivity + assets/tv/ + layout morti (dopo build-verifica).
2. Manifest via TvBrowseActivity.
3. Offline test completo (grep zero cdn.tailwindcss.com/fonts.googleapis nell'asset; interfaccia stilizzata a rete spenta).
4. versionCode bump; verifica release; README due interfacce.
VERIFICA: cold start pulito; alternanza modalità ×3 senza crescita memoria; entrambe le interfacce funzionanti.

## 6. FILE (creati/modificati/toccati)

Creati: bridge/SctvBridge.kt, bridge/SctvRepository.kt, SctvActivity.kt, LauncherRouterActivity.kt, res/layout/activity_sctv.xml, assets/sctv/{index.html, sctv.css, fonts/*}, tools/build-sctv.sh.
Modificati: AndroidManifest.xml, data/SettingsStore.kt, ui/SettingsDialog.kt, res/layout/dialog_settings.xml, res/values/strings.xml, PlayerActivity.kt (M3), MainActivity.kt SOLO per il callback onSaved della SettingsDialog (switch modalità).
Intatti: tutta la modalità sito (page_inject.js, video_agent.js, SCWebViewClient, DomainRepository, stores, layout sito).
Rimosso in M4: TvBrowseActivity.kt, assets/tv/index.html, layout tv morti.
