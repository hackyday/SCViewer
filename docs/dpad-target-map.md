# D-pad Target Map — streamingcommunity (per SCViewer page_inject.js)

Rilievo fatto il 2026-11-20 su `https://streamingcommunityz.dad/` (unico dominio attivo tra `.dad .nexus .boo .day .build`; gli altri non rispondono a livello DNS/TCP dall'ambiente).

**Metodo**: HTML SSR scaricato con curl (home `/`, titolo `/it/titles/7166-...`, movies `/it/movies`, search `/it/search?q=jack`), bundle Vite scaricati dal `build/manifest.json` (`sliders-Dzd49Bol.js`, `titles-pE7pX--v.js`, `common-DDk2LSmR.js`, `home-DKoFex3Z.js`) e i 4+1 CSS (`app`, `common`, `home`, `titles`, `sliders`). Archivio `/it/archive` e `/it/watch/<id>` si sono scaricati come **shell client-only** (nessun SSR): la loro struttura è stata ricostruita dai componenti Vue nei bundle — segnati come VERIFICATO-JS dove il render function è stato letto dal bundle, IPOTIZZATO dove no.

Ogni selettore è marcato:
- **VERIFICATO** = trovato testualmente nell'HTML/CSS/JS scaricato.
- **IPOTIZZATO** = dedotto dalla logica Vue ma non direttamente osservato nel DOM servito.

Nota generale: il sito è **Inertia.js + Vue 3 (SSR parziale)**. Home/titoli/movies sono SSR ma vengono **ri-idratati**: dopo l'hydration i componenti client sostituiscono il markup `.ssr-*` con markup diverso (`.slider-item` ecc. — classi client diverse dalle classi SSR). Lo scan D-pad deve girare **dopo** l'hydration e **ri-scannerizzare** dopo ogni cambio di tab/apertura dropdown.

---

## 1. Struttura per page-kind

### 1.1 Header — comune a TUTTI i page-kind (SSR, VERIFICATO)

```
header
├─ a[href="/it/premium"] > div.banner          ← banner ads premium (già nascosto dal modulo)
├─ div.close-banner                            ← X chiusura banner (già nascosto)
└─ nav.navbar
   ├─ .nav-left
   │  ├─ button.navbar-toggler                 ← hamburger, apre sidebar (VERIFICATO, <button> semantico)
   │  ├─ .logo-item > a[href="/"]              ← logo
   │  ├─ div.nav-item > a[href]                ← Home, Serie TV (/it/tv-shows), Film (/it/movies),
   │  │                                           Archivio (/it/archive), Premium, Richiedi un titolo
   │  ├─ div.nav-item > a[href*="animeunity.cx"][target=_blank]   ← esterno (VERIFICATO)
   │  │     (+ in hydration: a[href*="vixsrc.to"][target=_blank]  ← VERIFICATO-JS in Navbar, non SSR)
   │  └─ .search-bar-item > .search-bar
   │     ├─ svg.search-icon  onClick           ← espande la search inline (VERIFICATO-JS SearchBar)
   │     ├─ input[type=text][tabindex=0]       ← esiste SOLO quando espansa (v-if, VERIFICATO-JS)
   │     └─ button.filter (Inertia Link as:"button", href=/it/archive?search=…)  (VERIFICATO-JS)
   └─ .nav-right
      ├─ .dropdown (selettore lingua): .dropdown-trigger > button  +  .dropdown-right (v-if quando aperto)
      │    └─ .dropdown-content > div.dropdown-item ×2 (English/Italian, click JS)  (VERIFICATO)
      ├─ a.nav-item.btn-login[href="/login"]   ← solo da disconnessi (VERIFICATO)
      └─ (da connessi: dropdown profilo, VERIFICATO-JS, non presente da guest)
```

Click path reale: nav = `<a>`; hamburger/lingua/search-icon = listener Vue sul nodo (button/svg), il click funziona con `el.click()`.

### 1.2 HOME — componente Inertia `Home/Home` (SSR + hydration)

**Hero/billboard** (VERIFICATO, SSR):

```
main#content > div.container.font-vw
├─ .background-wrap                                  ← DECORAZIONE (vedi §2)
│  ├─ .background-image / picture.background-image-loader
│  └─ .top-shadow / .left-shadow / .bottom-shadow
└─ .billboard
   ├─ .billboard-header
   │  ├─ .genre-selector > .dropdown.genre-dropdown
   │  │  ├─ button.genre-trigger "Generi"            ← click: apre dropdown (VERIFICATO, <button>)
   │  │  └─ .dropdown-left.dropdown-wrap (v-if) > .genre-columns > div.genre-item ×26  ← click JS (VERIFICATO-JS)
   │  │       (esiste anche <select> con gli stessi <option>, ma display:none su desktop — VERIFICATO CSS)
   │  └─ .socials > a[target=_blank] ×3              ← Telegram/Instagram/Twitter (VERIFICATO)
   └─ .info-wrap > .info
      ├─ .title (img logo-title), .genres, .plot      ← testo decorativo
      └─ .info-buttons
         ├─ .mylist > div.title-btn  (+ span "La mia lista")     ← click JS toggle watchlist (VERIFICATO-JS)
         ├─ a.info-button.play[href="/it/watch/<id>"]            ← RIPRODUCI (VERIFICATO, link reale)
         └─ a.info-button.more-info[href="/it/titles/<id>-<slug>"]  ← ALTRE INFO (VERIFICATO, link reale)
```

**Slider / rail** — markup SSR `.ssr-title-card` viene sostituito dall'hydration da `Slider`+`TitleSlider` (VERIFICATO-JS):

```
.sliders > .slider-row
├─ .row-header > a[href="/it/browse?...&type=tv"]     ← la label "I titoli del momento" È UN LINK
│    └─ .header-wrap > .label + .browse > .browse-container > span("Browse more") + svg.angle-icon
│        (span/icone con opacity animata via mouseenter JS — decorativi, il link è l'<a>)
└─ .slider ( [class~=slider-minimal] su browse )
   ├─ span.slide.slide-left   onClick  ← aparece SOLO dopo la prima navigazione/enter sul rail (v-if)
   ├─ ul (tab dots) > li(.active)       ← pallini paginazione rail, decorativi (VERIFICATO-JS)
   ├─ .slider-content (.animating durante lo slide, 750ms, pointer-events:none)
   │  └─ div.slider-item ×N  (is-infinite SEMPRE true sui rail titoli → CLONI nel DOM, vedi §5.1)
   │     └─ a[href="/it/titles/<id>-<slug>"]  ← NODO CLICCABILE = l'<a> (Inertia Link, VERIFICATO-JS)
   │        ├─ [rail normali] .box-16x9 > img.cover-image   ← mouseenter = apre preview
   │        │    + div.sub-ita "SUB" (opzionale, decorativo)
   │        ├─ [rail top10] .box-7x10 (mouseenter target)   ← contiene il numero gigante
   │        │    ├─ div > svg.svg-icon.svg-icon-rank-N.top-10-rank   ← NUMERO GIGANTE (decorativo)
   │        │    └─ img.poster-image
   │        │  + .box-art-top10 (div, cursor:pointer CSS) > .boxart + .top10-icon > svg rank
   │        └─ [rail watchlist] componente .watching (.watching-wrap overlay assoluto con .play-btn
   │                  e .label "continua a guardare" + barra .progress) — overlay, NON cliccabile a sé
   ├─ span.slide.slide-right  onClick
   └─ [rail watchlist] .watching-footer (solo rail watchlist, overlay)
```

SSR statico (prima dell'hydration, VERIFICATO HTML): `.show-peek > .ssr-title-card > a[href] > .boxart` e per il top10 `a > .box-7x10 (svg rank + .poster-image) + .box-art-top10 (> .boxart + .top10-icon > svg rank)`. **Gli skeleton** `.loading-title > .boxart.pulsate` (VERIFICATO) vengono sostituiti dai rail caricati via AJAX: i rail SSR sono i primi 4, gli altri arrivano da `/it/api/...` (ScrollSliders) — lo scan va rieseguito quando compaiono nuovi rail.

**Preview popup** (`TitlePreview`, VERIFICATO-JS, dettagli in §3):

```
body > div.preview-wrap.font-vw > div.preview-dialog
├─ .player
│  ├─ .preview-box-16x9 > a[href="/it/titles/…"] > img.preview-image   ← click → pagina titolo
│  │    + div "SUB"
│  └─ .trailer-container > [video player] + div.trailer-click           ← click su TUTTA l'area = play trailer
└─ .info > .info-container > .buttons
   ├─ a.play[href]            ← onClick chiama playVideo (parte il video, non naviga!)
   ├─ watchlist / like / remove components (Re, De, Ge — vedi §3)
   └─ .more-info              ← div con onClick → location.href pagina titolo
+ .metadata (score/anno/runtime/badge .age) | .genres | .watchlist-title ("S1:E2 …") — testo
```

**Banner pubblicitari in-page** (`SlideBanner`, VERIFICATO-JS): `div.slide-banner > a.slide-banner__image[target=_blank]` + `a.slide-banner__btn[target=_blank]` — link esterni (prop `link` dal backend, es. landing/sponsor). Rumore puro su TV.

**Footer**: non esiste in home/titolo (0 match `<footer` in tutto l'HTML). Nessuna decina di link footer da filtrare.

### 1.3 TITLES — `/it/titles/<id>-<slug>` (componente `Titles/Title`, SSR + hydration)

**Overview** (VERIFICATO, SSR):

```
.title-container
├─ .background-wrap (.background-image, .cover-image-sm, .left-shadow, .bottom-shadow)  ← DECORAZIONE
└─ .info-wrap ( + .dark-bg-left / .dark-bg-right ← decorazione )
   └─ .info > .overview-tab.overview
      ├─ .title > h1 > img.logo-image + span          ← titolo (deco)
      ├─ .features: span(anno) span(stagioni) span(views) span.badge(12+) span.badge(HD)  ← deco
      ├─ .buttons > .video-actions
      │  ├─ a.play[href="/it/watch/<id>"]             ← RIPRODUCI (VERIFICATO)
      │  └─ .download > div.download-btn              ← click JS: apre pannello download (VERIFICATO-JS DownloadButton)
      ├─ .user-action.rating > div.circular-progress.title-btn (numero 7.5) + .label  ← SOLO DISPLAY (deco)
      ├─ .user-action > div.title-btn (icona +)        ← MyListButton, click JS (VERIFICATO-JS)
      ├─ .user-action > div.title-btn (icona stella)   ← RateButton, click JS → .rate-tooltip con
      │                                                  .rating-stars (mouseover per stella) e
      │                                                  .clear-rating; richiede login (VERIFICATO-JS)
      ├─ .user-action.more > div.trigger-btn > div.title-btn (dots)  ← MoreActionsButton, click JS →
      │                                                  menu teleport con solo "Share" (inutile su TV)
      ├─ .plot > p + span.read-more "Leggi tutto"      ← click JS espande (VERIFICATO)
      └─ .extra: Cast (span) / Genere → span.genre > a[href="/it/browse/genre?g=<genere>"]  ← link reali
```

**Tab nav** (VERIFICATO HTML+JS): `nav.tab-nav > div.nav-item.<overview|episodes|related|details|trailers>` — **DIV senza href, senza tabindex, senza role**; listener `onClick` Vue (TabNav). `.active` = tab corrente. Cambia il contenuto: `.overview-tab`, `.episodes-tab`, `.details-tab`, `.trailers-tab` hanno `style="display:none"` finché non selezionati.

**Episodi** (tab "Episodi") — VERIFICATO HTML (skeleton) + VERIFICATO-JS (componente):

```
.episodes-tab > .season-selector.scrollbar
├─ .dropdown.season-dropdown
│  ├─ button.season-trigger "Stagione 1"           ← click: apre dropdown stagioni
│  └─ .dropdown-right.dropdown-wrap (v-if) > ul.season-list > li.season-item ×N   ← click JS cambia stagione
│      (esiste anche <select> hidden con <option value="<season_id>">, display:none desktop)
└─ .ssr-episode-slider (SSR = skeleton .ssr-episode-card con .number-sm/.loading-name-*; dopo hydration:)
   └─ EpisodeSlider (is-infinite = FALSE, minimal-css)
      └─ a[href="/it/titles/<id>-<slug>"][onClick=playVideo]     ← vedi TRAP §5.2
         ├─ .box-16x9 > img.cover-image + .play-wrap > div.play-btn (visibile su hover/enter)
         ├─ div.number (numero episodio, assoluto, decorativo)
         ├─ .watching (progress bar se già visto)
         └─ .details.scrollbar > .name + .plot (mouseenter → overflow-y:scroll) + .download > [DownloadButton]
```

**Rail "Consigliati"** (tab Related): stesso TitleSlider della home, `slider.name=="related"` (header senza link — la funzione `x()` ritorna "" per related, VERIFICATO-JS: la label NON è cliccabile lì).

**Dettagli**: `.details-tab > .col > .label + .record` (testo) + generi (link come sopra). **Trailer**: `.trailers-tab` (TrailerPreview).

### 1.4 EXPLORE / ARCHIVIO / SEARCH — componente `Titles/Browse` (client-only, nessun SSR)

Ricostruito dal render function (VERIFICATO-JS). Comprende `/it/archive`, `/it/tv-shows`, `/it/movies` (Home/Movies usa invece i rail, ma browse è la pagina "filtri+lista"), `/it/search?q=` (con `noFilters`).

```
div (Browse, scope data-v-c7081cda)
├─ h1 (label "TV Show > <label>" o placeholder vuoto)
├─ .search-input (.search-input--active quando attiva)
│  ├─ icona ricerca + input (modelValue, reale)
│  └─ button.filter (Link as button → /it/archive?search=…)
├─ Filtri: BrowseFilterSelect ×N (generi, paesi, tipo, …)  → div.filter > .dropdown-trigger +
│    .dropdown-wrap (v-if) > .dropdown-content > div.dropdown-item ×N (click JS)
│  + BrowseOtherFilter (qualità/età) + div.filter-more (onClick toggle)
├─ Barra risultati: "60 titoli | ordina per:" + BrowseSorter (.sort, dropdown JS)
└─ Contenuto: .browse-slider ×N (TitleSlider con is-infinite → STESSA struttura card della home,
   raggruppati per fascia, NON griglia piatta) + load-more infinito via browseMoreApiRoute
   (SkeletonNe = .loading-title quando isFull=false)
```

**Paginazione classica**: il componente `Pagination` esiste (VERIFICATO-JS+CSS: `div > div > a.page-link` / `.page-link--active`, Inertia links con `?page=`) ma viene renderizzato solo dove il backend passa `links` (es. richieste titoli); su Browse/Archive la paginazione è **infinite scroll** (load-more via API). Non fidarsi di trovare `.page-link` su archive.

### 1.5 WATCH — `/it/watch/<id>` (componente `Titles/Watch`, client-only)

VERIFICATO-JS + VERIFICATO (shell HTML): la pagina è **solo**:

```
div > iframe[src=embedUrl][allow="autoplay; fullscreen"]   (fullscreen, 100%×100%)
   (+ Modal FHD "CANNOT_PLAY_FHD" con bottone close quando il titolo richiede premium)
```

Tutto il player (play/pausa/seek/episodi/qualità) vive **dentro l'iframe cross-origin (vixcloud/scws)**: NON raggiungibile da querySelector sul documento parent. Comunicazione via `postMessage`: il parent ascolta `REFERER_BACK` (torna al titolo), `NEXT_EPISODE` (prossimo episodio), `WATCHTIME_UPDATE`. Per la nav D-pad la pagina watch è una **zona morta da non scansionare**: il wrapper deve (a) lasciare il focus nativo all'iframe o (b) governare i tasti D-pad verso il player e usare postMessage per back/next.

---

## 2. Lista NERA — dove il fuoco NON deve MAI andare

Tutti VERIFICATI salvo indicazione.

### 2.1 Numeri gigante TOP 10
- Selettore: `svg.svg-icon.svg-icon-rank-1..10.top-10-rank` dentro `.ssr-title-card.slider-top10` (SSR) e dentro `.box-7x10` / `.top10-icon` (client). **Sono DENTRO l'`<a>` della card** (l'anchor avvolge `.box-7x10` + `.box-art-top10`, entrambi con il rank dentro). Il link è sull'`<a>` che INCLUDE il numero: non esiste un link "solo poster". Il fuoco deve andare sull'`<a>` (o, per click-bubbling, su `.box-art-top10`), mai sull'SVG.
- Nota trap: `id="rank-N"` è **duplicato 2 volte per card** (in `.box-7x10` e in `.top10-icon`) e ripetuto su tutto il rail: qualsiasi logica `getElementById`/`#rank-1` nel modulo rompe. Il B9-IoU già in page_inject.js copre l'overlap visivo numero/card; la whitelist non deve aggiungere l'SVG.

### 2.2 Overlay / gradienti / watermark / badge
- `.background-wrap`, `.background-image`, `picture.background-image-loader` (con `img aria-hidden`), `.top-shadow`, `.left-shadow`, `.bottom-shadow`, `.cover-image-sm`, `.dark-bg-left`, `.dark-bg-right` — gradienti/ombre hero e title, zero interazione.
- `.genre-selector`… nulla; `.socials` → son link reali (`a[target=_blank]`) ma **rumore su TV** (esterni, aprono browser/esterna).
- `div.sub-ita` / "SUB" dentro card e preview — etichetta decorativa dentro l'anchor.
- `span.badge` (12+, HD) in `.features` e in preview `.badge.age` — testo.
- `.circular-progress.title-btn` con `.value-container` (il punteggio 7.5) — display only (nessun handler nel JS).
- `.watching`/`.watching-wrap` sulle card (icona play + label + barra progresso "continua…") — overlay assoluto dentro l'anchor, non ha handler proprio.
- `.loading-title`, `.boxart.pulsate`, `.ssr-episode-card` (skeleton), `.loading-plot-row`, `.loading-name-*` — skeleton di caricamento: spariscono dopo il load, non devono mai presi dallo scan (filtro: assenza di handler è difficile; il filtro `cursor:pointer` già li scarta perché non hanno pointer — ma dopo hydration possono restare momentaneamente: meglio saltare elementi dentro `[class*=loading]`).
- `.slide-banner` + `a.slide-banner__image`, `a.slide-banner__btn` (`target=_blank`) — advertising esterno.
- `header a>div.banner` + `div.close-banner` — già nascosti dal modulo (corretto).
- `svg.angle-icon`, `span "Browse more"` nel row-header — indicatore visivo; il target è l'`<a>` row-header.
- `ul > li` tab-indicator (pallini paginazione slider) — decorativi.
- `noscript` (su watch) — irrilevante.

### 2.3 Testi / titoli rail / logo
- `.row-title`/`.row-header .label` ("I titoli del momento", "Aggiunti di recente", "Top 10 titoli oggi") — **ma l'`<a>` che li contiene è un link reale** a `/it/browse?…`: utile come scorciatoia "vedi tutto"? Su TV il valore è marginale; il modulo può trattarla come target di bassa priorità o escluderla. Il testo in sé (`.label`, `span`) no.
- `.logo-item a[href="/"]` — link reale ma inutile (tasto Back già torna home); consigliato escludere.
- `.plot`, `.info`, `.genres`, `.features`, `.extra`, `.record`, `.metadata-item`, `.watchlist-title` — testo puro.

### 2.4 Selettore lingua / social header
- `.dropdown` lingua nel nav-right: `button` trigger + `div.dropdown-item` ("English"/"Italian") — **funzionanti** (click JS, cambio locale) ma inutili su TV (il locale non cambia i contenuti sostanzialmente, e il menu si apre/chiude con click). Consiglio: escludere trigger e item.
- Social nell'hero (`aria-label="Telegram|Instagram|Twitter"`, `target=_blank`): escludere.

### 2.5 Elementi nascosti / fuori viewport / cloni
- **Cloni slider infinite**: `TitleSlider` monta `Slider` con `is-infinite: !0` (VERIFICATO-JS); `Slider` duplica gli item (funzioni `V/X/N/R` con `r_id` random) per il loop. Il DOM contiene copie degli stessi titoli sia prima sia dopo la finestra visibile: lo scan vede titoli **duplicati** (stesso href) a distanza di N slot → il D-pad "cammina all'infinito" sullo stesso film. Mitigazione: dedup per `href` (o per id titolo) nel rail, o limitare il fuoco alla finestra visibile.
- `.slider-content.animating` → `pointer-events:none` per i 750ms dell'animazione (VERIFICATO CSS): click e focus durante l'animazione sono morti. Il modulo deve rimandare lo scan/movement a fine transizione (o ignorare candidati dentro `.animating`).
- `.sidebar-mobile` (`pointer-events:none; visibility:hidden` quando chiusa, VERIFICATO CSS) + `.sidebar-backdrop` — overlay menu hamburger: invisibile da chiuso; da aperto contiene i link nav (duplicati dell'header). Su TV il menu hamburger è ridondante (i nav-item sono già in header): escludere button.navbar-toggler e tutta la sidebar, oppure gestirla esplicitamente.
- `select.genre-selector select`, `select[data-v-40a0cdfa]` (stagioni) — `display:none` su desktop (VERIFICATO CSS): nel FOCUS_SEL c'è `select` → devono essere scartati dal check display/visibility (già fatto dal modulo).
- `.episodes-tab`, `.details-tab`, `.trailers-tab` con `display:none` (VERIFICATO): il contenuto esiste nel DOM ma non è attivo finché il tab non è selezionato — scan deve escludere subtrees con `display:none` e ri-scannerizzare dopo il click sul tab.
- `div.dropdown-right.dropdown-wrap[style*="display:none"]` nel SSR — i contenuti dropdown SSR pre-idratazione non sono cliccabili; dopo l'hydration i dropdown sono **v-if** (il contenuto non c'è finché non si apre).

### 2.6 Zone morte
- `iframe` (su watch è TUTTA la pagina; su altri casi gli iframe ads sono già nascosti).
- `div.preview-dialog` quando `h.title==null` — non renderizzato (v-if/teleport): nessun fantasma nel DOM.
- `input[disabled]`, `a[aria-disabled]` — già filtrati dal modulo.
- `canvas`/`video` non trovati nel parent (i `<video>` ci sono solo dentro preview/player; il modulo già li lista a parte per il player).

---

## 3. Popup preview — come si apre DAVVERO

**Non è CSS `:hover`.** Verificato su tutti i CSS scaricati: le uniche regole `:hover` "che rivelano contenuto" sono:

```
.sliders-Ce9teqOd.css:
  .slider:hover .slide svg[data-v-2bc8712e]{display:block}       ← mostra le FRECCE (decorativo per il fuoco)
  .slider:hover .tab-indicator[data-v-2bc8712e]{display:block}   ← pallini
  .slide[data-v-2bc8712e]:hover{background:#141414b3}            ← tinta freccia
common-BEB3sGmA.css / titles-DmBFg5pf.css / home-V_uI52ct.css:
  solo variazioni di background/underline su .nav-item, .dropdown-item, .info-button,
  .title-btn, .banner, .filter, .genre-item, .season-item, .record(genre), .read-more ecc.
```

Nessuna regola tipo `.item:hover .detail{display:block}` che rivela contenuto interattivo. **La preview è JS** (VERIFICATO-JS, `TitleSlider._()`):

1. `mouseenter` su `img.cover-image` (rail normali) o su `div.box-7x10` (rail top10) → `setTimeout(300ms)`;
2. se `document.querySelector('.preview-wrap')` esiste già → **abbandona** (non apre la nuova);
3. altrimenti `POST route('api.titles.preview', [id])` e, alla risposta, monta `TitlePreview` via **Teleport su `<body>`** (`.preview-wrap`, posizionato assoluto con offset calcolati dal `getBoundingClientRect` del target);
4. chiusura: `mouseleave` su `.preview-dialog`, oppure `window mousemove` fuori da `.preview-wrap`, oppure `dispatchEvent(new Event('mouseleave'))` dal modulo stesso.

**Conseguenze per page_inject.js**:
- `dispatchEvent(new MouseEvent('mouseenter'))` / `'mouseover'` su `img.cover-image`/`.box-7x10` FUNZIONA (Vue usa addEventListener; un synthetic event bubblerà e triggererà l'handler — l'handler legge solo `e.target` per il rect). Server-side POST avviene da JS: OK.
- MA serve **sequenza** tra card: prima `dispatchEvent(new Event('mouseleave'))` sulla card corrente (o click su un punto neutro) e attendere la chiusura (200ms + teardown), POI mouseenter sulla nuova; altrimenti il guard `if(document.querySelector('.preview-wrap')) return` blocca l'apertura della nuova preview. Consiglio pratico su TV: **non usare la preview** (è centrata mouse/touch, si chiude su mousemove fuori, e sposta il fuoco visivo), oppure aprirla esplicitamente solo sul tasto "OK" con la sequenza sopra e chiuderla con Back mappato a mouseleave.
- Candidati DENTRO la preview (quando è aperta, rerun dello scan su `.preview-wrap`):
  - SÌ: `a.play` (parte il video), il componente watchlist/like/remove (`.title-btn`/`.info-btn` nel dialog), `.more-info` (vai al titolo), `a[href]` di `.preview-box-16x9` (vai al titolo).
  - NO: `.trailer-click` (copre TUTTA l'area video: overlay "click ovunque" — lo si può usare ma è meglio esporlo come unico grande tasto "guarda il trailer", non come target di griglia), `.preview-image` come target separato (è dentro l'anchor già contato), `.metadata`, `.genres`, `.sub-ita`, `.logo`.
- Gli eventi sintetici da soli NON attivano le regole CSS `:hover` — qui non serve (nessuna regola :hover critica), ma le frecce `.slide` restano visibili solo se il fuoco "entra" nel rail con un vero hover: per il D-pad va bene comunque perché le frecce si montano comunque dopo il primo uso (`v.value`/`h.value` diventano true al primo slide) e restano in DOM. NOTA: al primo idratazione le frecce non sono in DOM (v-if) → scan iniziale non le vede: giusto, tanto su TV si naviga il rail col fuoco sulle card e le frecce sono inutili (consiglio: whitelist senza `.slide`).

---

## 4. Whitelist per lo scan (in ordine di preferenza su cursor:pointer)

Validi su **TUTTI** i page-kind (header + card):

```js
// header nav (link reali)
'header nav.navbar .nav-item > a[href]'        // Home/Serie/Film/Archivio/Premium/Richiedi
// card titoli: il nodo cliccabile È l'<a> che avvolge boxart/cover (home, related, browse, watchlist)
'a[href^="<app_url>/it/titles/"]'              // card + row-header link
'a[href^="<app_url>/it/watch/"]'               // Riproduci (hero, title, preview)
'a[href^="<app_url>/it/browse"]'               // row-header "vedi tutto" + generi (.genre a)
'a[href^="<app_url>/it/search"]'
// bottoni espliciti
'.info-buttons .mylist .title-btn'             // hero: aggiungi alla lista (div click)
'.info > .buttons .download-btn'               // title: download
'.info > .user-action .title-btn'              // title: lista/valuta (NON .rating: display only)
'button.genre-trigger, button.season-trigger'  // dropdown reali (<button> semantici)
'nav.tab-nav > div.nav-item'                   // tab Panoramica/Episodi/Consigliati/... (JS-only!)
'.dropdown-wrap li.season-item'                // quando il dropdown stagioni è aperto
'.dropdown-wrap .dropdown-item, .dropdown-wrap .genre-item'  // quando un dropdown è aperto
'.slider-content .read-more'                   // espandi plot (bassa priorità)
'.episodes-tab a[href*="/it/watch/"]'          // righe episodi (ONCLICK OVERRIDE: vedi §5.2)
```

Escludere espressamente (blacklist selettori da applicare PRIMA del fallback cursor:pointer):

```js
'a[target="_blank"]'                    // social, slide-banner, animeunity, vixsrc, premium banner
'.socials a, .slide-banner a, header a>div.banner, .close-banner'
'.logo-item a'
'a[href*="verifylink.click"], a[href*="instagram.com"], a[href*="twitter.com"], a[href*="animeunity"], a[href*="vixsrc"]'
'svg.top-10-rank, .top10-icon, .box-7x10 svg'   // numero gigante (non clickable, dentro l'anchor)
'.watching, .watching-wrap, .watching-footer'
'.badge, .sub-ita, .label, .number, .number-sm'
'.loading-title, .boxart.pulsate, [class*="loading"]'
'.tab-indicator, ul li.active'          // pallini slider
'.slide.slide-left, .slide.slide-right' // frecce: span onClick ma inutili/superflue con D-pad
'.genre-selector select, .season-selector select' // select nascosti desktop
'.circular-progress'                    // punteggio, display only
'.search-icon'                          // espande la search: su TV meglio un tasto dedicato;
                                        // se lo si tiene, va bene (ha onClick e cursor:pointer)
'.navbar-toggler, .sidebar-mobile *'    // sidebar hamburger: ridondante su TV
'.trailer-click'                        // overlay full-area preview
'.mylist .title-btn span'               // la label testuale accanto ai bottoni hero
```

Nota per page-kind:
- **home**: la prima riga di scan è il hero (`.info-buttons a.play`, `a.more-info`, `.mylist .title-btn`), poi i rail; se il banner premium è nascosto dal modulo, il primo target è `nav.nav-item a`.
- **titles**: sequenza = `.buttons a.play` → `.user-action .title-btn` (lista/valuta) → `nav.tab-nav .nav-item` → contenuto tab attivo (episodi/related). Non scansionare `.episodes-tab` se `display:none`.
- **browse/search**: filtri (`div.filter`, `.filter-more`, `.sort`) + rail. Con `noFilters` (search) solo rail. Il load-more non ha bottone: scorre — il D-pad può ignorarlo.
- **watch**: nessun candidato nel parent DOM. Se il modulo trova solo un `iframe`, deve passare la palla al player (o mappare Back → `postMessage('REFERER_BACK')` non serve: lo fa già il sito; il wrapper deve solo NON injettare l'anello).

Selettore card "quale nodo": l'`<a>` è `display:inline` di default ma il rect copre la boxart (il `div.box-16x9` interno). Per il fuoco conviene il rect dell'`<a>`; per il click `el.click()` funziona (Inertia intercetta e fa navigazione SPA).

---

## 5. Trap specifiche trovate nell'analisi

### 5.1 Slider infinite = cloni nel DOM
`TitleSlider` usa SEMPRE `is-infinite: !0` (VERIFICATO-JS: `z(we,{items:d.value,"is-infinite":!0,...})`). `Slider` crea/copie con chiave `r_id` random (`V()` fa `JSON.parse(JSON.stringify(...))` + `r_id=le()`). Effetto: per lo stesso `href` ci sono **più `<a>` identici** nel DOM (loop continuo destra/sinistra). Se il modulo dedup-only per contenimento/IoU NON basta: gli cloni non si sovrappongono (sono in slot diversi della striscia). Serve dedup per `href` (o `id` titolo estratto da `/it/titles/(\d+)-`) **per rail**, tenendo il clone visibile. (`EpisodeSlider` è `is-infinite: !1` — gli episodi non si duplicano.)

### 5.2 Le righe episodi hanno href bugiardo
`EpisodeSlider`: `<a href=route('titles.show'...) onClick=playVideo(title, episode)>` (VERIFICATO-JS). Il click NON naviga alla pagina del titolo: `playVideo` porta direttamente al player dell'episodio (e dentro l'handler `d()`: se il target è dentro `.download-btn` o `.admin-actions` fa `preventDefault`). Conseguenza per il wrapper: l'`a[href]` dell'episodio NON va interpretato come "vai al titolo"; il click va lasciato all'handler JS (el.click() lato DOM va bene: Inertia/Vue intercetta prima del default). Non precalcolare l'URL di destinazione per showLoading.

### 5.3 La preview sale a timeout e si auto-blocca
Vedi §3: mouseenter+300ms+POST+Teleport; guard su `.preview-wrap` esistente; chiusura su mouseleave/mousemove. Se il modulo prova a muovere il fuoco mentre la preview è aperta senza prima chiuderla, le nuove preview non si aprono e il layout visivo (posizioni) resta sfasato rispetto al DOM (la preview è assoluta su body).

### 5.4 Dropdown = contenuto esistente SOLO quando aperto
`Dropdown` (VERIFICATO-JS): `.dropdown-wrap` è `v-if` → gli item (.dropdown-item, .genre-item, .season-item) NON esistono finché non si clicca il trigger (che è `div.dropdown-trigger` con onClick, o `<button>` nei casi genre/season). Lo scan va rieseguito dopo il click; ESC chiude (handler document keydown). Il click fuori chiude (document click handler con `closest('.dropdown')`).

### 5.5 `pointer-events:none` durante l'animazione rail
`.slider-content.animating{...;pointer-events:none}` per 750ms (VERIFICATO CSS). Click/fuoco presi durante la transizione sono persi. Il modulo dovrebbe: (a) attendere la fine (750ms) tra uno slide e l'azione, oppure (b) ignorare candidati con antenato `.animating` e ri-scannerizzare a transizione finita.

### 5.6 ID duplicati `rank-N`
Vedi §2.1: `document.querySelector('#rank-1')` ecc. ritorna il primo di decine. Mai usare ID per la top10.

### 5.7 SSR `.slide-right` è uno span vuoto
Nell'HTML SSR la freccia è `<span class="slide-right">` SENZA svg e SENZA handler (VERIFICATO HTML); dopo hydration diventa `span.slide.slide-right` con onClick e svg. Differenziale SSR/client: un'euristica che si fida del markup SSR iniziale vede "span decorativo", quella post-hydration vede "bottone". Scan sempre post-hydration (es. dopo `Inertia` ready / MutationObserver settle).

### 5.8 Le frecce si accavallano alle card estreme
`.slide{position:absolute; width:4%; z-index:1; left/right:-4%}` (VERIFICATO CSS): la freccia copre il bordo della prima/ultima card visibile. Con il dedup containment del modulo il target resta la card (più grande) — ma attenzione: `.slide` ha `cursor:pointer` esplicito e arriva al fallback: per rect containment la card vince, ma in caso di clip parziale lo IoU può preferire la freccia. Whitelist-scan (§4) che esclude `.slide` risolve.

### 5.9 Link esterni `target=_blank`
AnimeUnity, vixsrc.to, Telegram (verifylink.click), Instagram, Twitter, banner premium, slide-banner: aprono tab/app esterni. Su un wrapper TV rompono la UX (o aprono Chrome). Escludere con la blacklist §4.

### 5.10 Rate tooltip e menu "Altro"
`RateButton` apre `.rate-tooltip` (mouseover sulle stelle per votare, `.clear-rating`); `MoreActionsButton` apre un menu teleport con **solo "Share"** (navigator.share — inesistente su TV). Entrambi richiedono login per fare qualcosa di utile. Bassissima priorità: si possono lasciare fuori dalla whitelist senza perdere funzioni reali su TV.

### 5.11 `auth_url` diverso dal sito
`props.auth_url = https://streamingunity.day` (login/register vivono su un ALTRO dominio; VERIFICATO in data-page). Il link "Accedi" (`/login`) in realtà riporta l'app a `auth_url`: per il wrapper è una navigazione cross-origin — l'anello D-pad e l'injection vanno riarmati al cambio dominio (o l'accesso va gestito nativamente).

### 5.12 Pagination vs infinite scroll
Non dare per scontata la paginazione su archive: è load-more via `browseMoreApiRoute` (VERIFICATO-JS props + Browse). Il bottone "carica altro" NON esiste: il componente mostra skeleton e carica allo scroll. Su TV: o premendo giù sull'ultimo item si manda lo scroll, oppure si espone nativamente. Il componente Pagination (`a.page-link`, VERIFICATO-JS) compare solo su pagine con `links` prop (es. richieste) — se appare, i `.page-link` sono link reali cliccabili.

### 5.13 Il link "Accedi" e `btn-profile`
Da guest: `a.nav-item.btn-login` (semantico). Da logged: dropdown profilo (`key:1 class:"nav-item btn-profile"` VERIFICATO-JS, contenuti solo da connessi). Non osservato da guest oltre il markup SSR: IPOTIZZATO che il trigger sia `button` nel dropdown — se appare, rientra nella regola dropdown §5.4.

---

## 6. Sintesi operativa per page_inject.js

1. Aggiungere `pageKind` (da `document.querySelector('#app').dataset`/JSON `data-page.component`: `Home/Home`, `Home/Movies`, `Home/Shows`, `Titles/Title`, `Titles/Browse`, `Titles/Watch`) e applicare le whitelist §4 per kind.
2. Dedup per href/id-titolo per rail (cloni infinite) — nuovo, necessary.
3. Ignorare candidati dentro `.slider-content.animating` e riprogrammare lo scan a fine animazione (750ms).
4. Non montare la preview su mouseenter sintetico durante la navigazione; se serve, sequenza mouseleave→wait→mouseenter e scan su `body > .preview-wrap`.
5. Scan re-run su: apertura/chiusura dropdown, switch tab (`nav.tab-nav .nav-item`), fine hydration rail AJAX, apertura preview.
6. Su `/it/watch/*` disattivare l'anello (solo iframe).
7. Il filtro `cursor:pointer` resta come fallback, MA con la blacklist §4 applicata prima: i falsi positivi principali sono `.box-art-top10` (wrapper dentro l'anchor), `.slide` (frecce), `.close-banner`, `.trailer-click`, `.filter-more`, `.read-more` — i primi due li si può tenere come click-target equivalenti, gli altri sono rumore o menu secondari.

---

## §7 Flusso pagina titolo — mappa navigazionale completa

Tutto VERIFICATO salvo indicazione. Fonti aggiuntive rispetto a §1.3: render functions di `Title`, `OverviewTab`, `TabNav`, `EpisodesTab`, `SeasonSelector`, `TrailerSlider`, `Slider`, `TitleSlider`, `TitlePreview`, `DownloadButton`, `MyListButton`, `RateButton` nei bundle `titles-pE7pX--v.js` / `sliders-Dzd49Bol.js` / `common-DDk2LSmR.js`.

### 7.1 ARRIVO sulla pagina — dove cade il fuoco

Ordine DOM reale del `<main>` (VERIFICATO HTML + render Title):

```
.title-container
├─ .background-wrap          ← ZONA MORTA (vedi sotto)
└─ .info-wrap
   ├─ .dark-bg-left / .dark-bg-right          ← gradiente (deco)
   ├─ .title > h1 > img.logo-image + span     ← H1 (deco)
   ├─ .info > .overview-tab.overview          ← TAB ATTIVA DI DEFAULT (client), vShow
   │  ├─ .features: span(anno) - span(stagioni/min) - span(views) - span.badge(età) - span.badge(HD) - span "SUB"
   │  ├─ .buttons > .video-actions
   │  │  ├─ a.play[href="/it/watch/<id>"]     ← ★ PRIMO TARGET RAGIONEVOLE (vedi sotto)
   │  │  └─ .download > div.download-btn
   │  ├─ .user-action.rating (score deco) / +mylist / star-rate / dots-more
   │  ├─ .plot (+ .read-more) / .extra Cast / .extra Genere (≤2 link)
   ├─ .episodes-tab   [display:none]  (montato, tv)
   ├─ .details-tab    [display:none]
   ├─ .trailers-tab   [display:none]
   └─ nav.tab-nav
└─ .sliders (rail "Correlati" + client-only trending/latest/top10/genres)
```

- **Fuoco iniziale consigliato**: `a.play` dentro `.overview-tab .video-actions`. È il primo candidato nel flusso di lettura, ha href reale, e la label è resume-aware: "Riproduci" da guest, "Continua" quando `title.user_watchlist` esiste (il minuto di ripresa è risolto lato server via prop `minute` sulla pagina watch — non serve logica client). Alternativa accettabile per la nav: cadere su `nav.tab-nav .nav-item` attivo, ma il play è l'azione principale.
- Se il titolo è `coming_soon`: il play diventa `span.play.play-disabled` — **non è un link, non ha handler**: lo scan deve scartarlo (check `a[href]` fallisce da solo) e il fuoco deve cadere su `.download-btn` o sul primo tab.
- NON candidati all'arrivo (con motivo): `.background-wrap` + `.background-image` + `picture.background-image-loader` + `.left-shadow`/`.bottom-shadow`/`.dark-bg-*` (decorazione/gradiente, nessun handler); il **video preview dell'hero** (VERIFICATO-JS: componente `ut`=VideoPlayer con `show-after:1000ms`, autoplay muto, visibile solo su tab overview — è sfondo animato, non cliccabile); `h1`, `img.logo-image`, tutti gli `span` di `.features`, `.badge` (età/HD/SUB), `.plot`/`.extra`/`.label`; `div.user-action.rating > .circular-progress.title-btn` (punteggio: è un `div.title-btn` con `cursor:pointer` ma NESSUN onClick — il `cursor:pointer` di `.title-btn` è ereditato dallo stile comune; il punteggio è display-only); `strong S1:E2` in `.watching` (TitleWatchingProgress: label + barra, display-only).

### 7.2 TAB NAV — `nav.tab-nav > div.nav-item`

- Struttura (VERIFICATO-JS TabNav): 6 voci generate da `[{overview, episodes, related, details, trailers, download}]`. Le prime 5 sono `div.nav-item.<nome>` con `onClick → emit('ontabswitch')`; la voce "Download" NON è un tab: è `div.nav-item.download > div.download-btn` (slot del DownloadButton) che apre il flusso download/dialog — TRAP: un'euristica "tutte le voci di tab-nav cambiano tab" rompe qui.
- Come diventano candidati: NESSUN href, NESSUN `role`, NESSUN `tabindex`. Solo `cursor:pointer` dal CSS (`.nav-item[data-v-72d227da]` presente nella lista cursor:pointer VERIFICATA) o selettore esplicito. **Consiglio: selettore esplicito `nav.tab-nav > div.nav-item:not(.download)`** — il cursor:pointer da solo funziona ma è fragile (la voce download contiene a sua volta un `.download-btn` candidato).
- `.active` = tab corrente (binding client); `.active-ssr` = classe SSR-seeded (su serie TV l'SSR marca "Episodi" per SEO) che viene **rimossa all'hydration**: subito dopo il load il DOM mostra `active-ssr` su Episodi mentre il tab client è Overview — non fidarsi della classe SSR per lo stato iniziale; usare `.active` (client) e comunque non usarla per decidere il focus iniziale.
- **Il cambio tab è vShow (display toggle), NON v-if**: il render di `Title` avvolge ogni tab-content in `withDirectives(...,[vShow])` (VERIFICATO-JS: `ee(...,[[te, tab=="overview"]])`, te=vShow). `.overview-tab`, `.episodes-tab`, `.details-tab`, `.trailers-tab` restano SEMPRE nel DOM, commutate con `style.display:none`. Conseguenza: dopo il click il contenuto è subito presente → **rescan immediato va bene, nessun delay di montaggio**. L'UNICA eccezione: TrailersTab riceve `show: tab=="trailers"` e internamente fa lazy `import("./sliders-...js")` + **v-if interno** → il TrailerSlider si monta SOLO alla prima apertura del tab (uno-time; poi resta montato). Delay stimabile: import lazy del chunk sliders (~26KB, già in cache dopo la home) + render — nell'ordine delle decine/centinaia di ms; il modulo deve ri-scannerizzare quando compare `.trailers-tab [class*=slider]` (o via MutationObserver).
- Altra delay-trap sul tab Episodi: il contenuto passa da **skeleton** (Suspense fallback = `ge` SSREpisodeSlider: `.ssr-episode-card`) a slider reale quando la stagione è risolta. Su cambio stagione (§7.3) c'è un **partial reload Inertia** (`B.reload({only:["loadedSeason","flash"]})`): skeleton → dati. Regola: non dare fuoco agli skeleton; riscan quando scompaiono `.ssr-episode-card`/`[class*=loading]`.
- Tab attive non selezionate = `display:none` → il filtro display del modulo le scatta già; nessun lavoro extra, ma ogni switch richiede un rescan esplicito (l'anello va riposizionato).

### 7.3 DROPDOWN STAGIONI

- Dove vive (VERIFICATO-JS SeasonSelector + CSS): **inline nel flusso, dentro `.episodes-tab > .season-selector`**. NIENTE Teleport, NIENTE backdrop/overlay: il `Dropdown` comune renderizza `.dropdown-wrap` come **v-if** dentro il `.dropdown` stesso, con `position:absolute; z-index:20 (pagina title) / 50 (default common); margin-top:10px` e `.dropdown-right{right:0}` (VERIFICATO CSS). Gli altri candidati restano cliccabili e scannabili mentre è aperto; nessuna cattura di click (solo listener documentali).
- Flusso completo:
  1. **Apertura**: click su `button.season-trigger` (dentro `div.dropdown-trigger` che ha l'onClick con `setTimeout(10ms)` toggle — VERIFICATO-JS). Il `<button>` è semantico → già in FOCUS_SEL; il click via `el.click()` bubbles e funziona.
  2. **Navigazione voci**: appare `.dropdown-right.dropdown-wrap > ul.season-list > li.season-item ×N` (testo: `Stagione N (M episodi)`). Item = `li` con onClick, `cursor:pointer` (VERIFICATO CSS `.season-item:hover{...cursor:pointer}`). Rescan necessario: prima del click gli item NON esistono (v-if).
  3. **Selezione**: click su `li.season-item` → `emit('update:modelValue', id)` → il dropdown **si chiude da solo** (v-if smonta il wrap; il click-item chiude perché `preventCloseOnClick` non è impostato — VERIFICATO-JS Dropdown `w()`).
  4. **Effetto selezione** (VERIFICATO-JS EpisodesTab `u()`): `history.replaceState` sull'URL `/it/titles/<id>-<slug>/season-<num>` (NON naviga!) → se gli episodi della stagione sono già in cache li usa subito; altrimenti partial reload `B.reload({only:["loadedSeason","flash"]})` con skeleton nel mezzo. Riscan dopo che gli skeleton spariscono.
  5. **Chiusura alternative**: `Escape` (handler document keydown nel Dropdown) o click fuori (handler document click con `closest('.dropdown')`). Il modulo può mappare Back → `Escape` sintetico o click su body.
- Esiste anche un `<select onChange>` parallelo con gli stessi anni/stagioni: `display:none` su desktop (VERIFICATO CSS) → FOCUS_SEL lo prende ma il filtro display lo scarta; NON aggiungerlo alla whitelist.
- Nota: `SeasonAdminActions` (`ml`) esiste nel selettore ma solo con permesso `panel.access` — da guest NON c'è; non serve blacklistare.

### 7.4 GRIGLIA EPISODI (client EpisodeSlider)

- Struttura post-hydration (VERIFICATO-JS; desktop): radice `div.slider-row.episode-slider` (Slider con `minimal-css`) → `.slider.slider-minimal` → [`span.slide.slide-left` se navigato] → `div.show-peek` → `div.slider-content` → **`div.slider-item > a[href="/it/titles/<id>-<slug>"][onClick=playVideo(title, episode)]`** per episodio → dentro l'anchor:
  - `.box-16x9 > img.cover-image` (miniatura; `mouseenter` mostra SOLO un'icona play overlay `.play-wrap > .play-btn` via vShow — **le card episodio NON aprono la preview grande**, nessun mouseenter→preview qui);
  - `div.number` (numero episodio, `position:absolute; bottom:.1em; left:.3em; font-size:2em` — VERIFICATO CSS: **decorativo, dentro l'anchor**, non aggiungerlo);
  - `.watching` (WatchingProgress comune: barra avanzamento episode.history — overlay deco);
  - `.details.scrollbar > .name` + `.plot` (mouseenter espande con `overflow-y:scroll` — testi, deco tranne il read-more implicito) + `.download > div.download-btn` (**download PER EPISODIO**: click → guest: PremiumFeatureDialog; loggato premium: GET api.video_info → dialog).
- **Rail NON infinita**: `EpisodeSlider` monta Slider con **`is-infinite: !1`** (VERIFICATO-JS) → NESSUN clone, nessun loop. Unica rail della pagina title senza duplicati (le altre, correlate/trending/…, sono infinite — §7.6).
- Nodo click vero: l'`<a>` (tutta la riga è dentro l'anchor). Il bottone "+" per episodio **non esiste** (solo download + admin per panel.access). Il fuoco per episodio va sull'`<a>`; il download-btn episodio è un secondo target interno — se lo si espone, attenzione: è DENTRO l'anchor e l'handler `d()` di EpisodeSlider fa `preventDefault` SOLO se il target è dentro `.download-btn`/`.admin-actions` → il click sul download-btn è gestito correttamente dal sito, esponibile come target separato (rect più piccolo, containment dedup lo tiene).
- Click sull'episodio = `preventDefault` + `playVideo(title, episode, animate=true)` → **non segue l'href** (vedi §5.2 e 7.9).

### 7.5 BOTTONI AZIONE (handler reali)

| Elemento | Selettore | Handler reale (VERIFICATO-JS) | Note |
|---|---|---|---|
| Riproduci/Continua | `.overview-tab a.play` | `onClick → playVideo(title, null, animate)`; href `/it/watch/<id>` | label "Riproduci"/"Continua" (user_watchlist); coming_soon → `span.play.play-disabled` (nessun handler) |
| Scarica (titolo) | `.video-actions .download-btn` | `DownloadButton.onClick`: tv → `document.querySelector(".season-selector").scrollIntoView()`; movie → GET `api.video_info` → dialog download (chunk premium lazy); guest → **PremiumFeatureDialog** (modal) | il bottone in `nav.tab-nav` (`div.nav-item.download > .download-btn`) è lo stesso componente |
| + La mia lista | `.user-action .title-btn` (icona +/check, `data-v-ade685f1`) | `MyListButton`: guest → Inertia GET `/login`; loggato → POST `api.favourites.toggle` → pushAlert; icona plus↔check | tooltip on mouseenter desktop-only |
| Valuta (stella) | `.user-action .title-btn` (star-icon, `data-v-a2a9cb15`) | `RateButton`: guest → `/login`; loggato → toggle `.rate-tooltip` con `.rating-stars` (5 stelle: `mouseover` calcola la stella da `layerX`, click POST `api.ratings.rate`) e `.clear-rating` (DELETE `api.ratings.delete`) | su TV: richiede eventi sintetici mouseover con coordinate — sconsigliato, basso valore |
| Altro (dots) | `.user-action.more .trigger-btn .title-btn` | `MoreActionsButton`: apre menu (componente common, trigger/toggle) con **solo "Share"** (`navigator.share`) | inutile su TV → blacklistabile senza perdite |
| Leggi tutto | `.plot .read-more` | `onClick`+`onKeydown` toggle plot espanso | target secondario ok |
| Generi | `.extra .genre a[href="/it/browse/genre?g=…"]` e `.details-tab .genre a` | Inertia Link | **solo i primi 2 generi** sono link (render `w<2` / `D<3`); i restanti span vuoti |
| Score | `.user-action.rating .circular-progress.title-btn` | NESSUN handler | deco, escludere |

Pulsanti pollici su/giù: **non esistono** sulla pagina titolo (né hero né preview hanno like/thumbs separati — la seconda `user-action` è la stella di RateButton). Nel TitlePreview i bottoni sono: `a.play` (playVideo), MyListButton (`Re`=MyList), RateButton (`De`), [RemoveFromWatchlistButton `Ge` se watchlist-rail], TitleButton.more-info (`Qe`) — vedi §3.

### 7.6 RAIL "SIMILI" (tab related) e rail extra

- La rail `sliders:[{name:"related",label:"Correlati"}]` è renderizzata con lo **stesso TitleSlider della home**: card identiche, `is-infinite: !0` → **CLONI presenti anche qui** (dedup per href necessario, §5.1).
- **Preview popup attive ANCHE sulla related rail**: l'handler `_()` di TitleSlider non ha eccezioni per `related` (le uniche branch specifiche sono top10/watchlist/upcoming) → mouseenter su `img.cover-image` apre la preview con timeout 300ms + POST, identica alla home. Su TV: stessa decisione di §3 (non usare, o usare solo su OK).
- **Trap header related**: `x()` ritorna `""` per `slider.name=="related"` → il row-header è un Inertia Link con `href=""` (VERIFICATO-JS). SSR: `.row-title > span "Correlati"` SENZA link; client: `.row-header > a[href=""]`. Click = visita same-URL (Inertia reload della pagina). Blacklistare il row-header della related (o tutti i row-header su title).
- **Rail client-only aggiuntive**: quando `!isMobile` (desktop) il Title monta `ScrollSliders` con `sliders:["trending","latest","top10","genres"]` + slide-banners (VERIFICATO-JS) — sotto la rail related compaiono ALTRI rail infinite con preview. Lo scan deve rieseguirsi quando questi rail appaiono (AJAX), altrimenti il fuoco "finisce" su elementi comparsi dopo. SSR: solo la rail related esiste (VERIFICATO HTML: 1 solo `.slider-row` nell'SSR della pagina titolo).
- Su tab non-overview (desktop) l'hero riceve `filter: brightness(33%)` (VERIFICATO-JS) — solo visuale.

### 7.7 TAB TRAILERS

- **Non sono link né iframe inline**: `TrailerSlider` (VERIFICATO-JS, `is-infinite: !1` niente cloni) rende per trailer `div > img.cover-image[src="https://img.youtube.com/vi/<youtube_id>/0.jpg"][onClick → seleziona trailer]` — **la miniatura è un IMG con onClick** (cursor:pointer da CSS `.cover-image[data-v-170c4d5a]`), non un `<a>`.
- Il click apre una **Modal** (DialogModal common: overlay centrato, titolo "Watch trailer") il cui contenuto è `iframe[src="https://youtube.com/embed/<youtube_id>"][allowfullscreen autoplay]` → **iframe YouTube = zona morta per il DOM parent**. Candidati dentro la modal: solo il bottone/area di chiusura (`.close`/`.close-dialog` — classi presenti nei CSS common VERIFICATE) e il backdrop (DialogModal chiude su click backdrop — IPOTIZZATO dal pattern Modal, non letto riga per riga).
- Flusso TV consigliato: fuoco sulle miniature (tasto OK → modal) → Back/OK su close → chiudi. Non scannerizzare dentro l'iframe.
- Il tab si monta lazy alla PRIMA selezione (§7.2): prima di quel momento `.trailers-tab` è vuoto (solo commenti) — VERIFICATO SSR (`<div class="trailers-tab" style="display:none;"><!----></div>`).

### 7.8 Coppie SSR → client esatte (usare SOLO le classi client)

| SSR (nel HTML servito) | Client (dopo hydration) | Dove |
|---|---|---|
| `.ssr-title-card` (+`.slider-top10`) | `.slider-item > a > (.box-16x9 > img.cover-image \| .box-7x10 + .box-art-top10)` | card rail (home/title/browse) |
| `.slider-row > .row-title > span` | `.slider-row > .row-header > a > .header-wrap > .label` (+`.browse > .browse-container > span`) | header rail |
| `.slider > .show-peek > .ssr-title-card*` | `.slider > .show-peek > .slider-content > .slider-item*` + `span.slide.slide-left/right` + `ul.tab-indicator > li` | interno rail (`.show-peek` SOPRAVVIVE, cambiano i figli) |
| `.ssr-episode-slider` | `div.slider-row.episode-slider > .slider.slider-minimal > .show-peek > .slider-content > .slider-item` | tab episodi |
| `.ssr-episode-card` (+`.number-sm`, `.image-wrap`, `.loading-name-*`) | `.slider-item > a > (.box-16x9 > img.cover-image + .play-wrap > .play-btn + .number + .watching) + .details.scrollbar > (.name + .plot + .download)` | card episodio |
| `.loading-title > .boxart.pulsate` | rail reali (`Slider`/`TitleSlider` completi) | skeleton home + rail ScrollSliders client-only |
| `.nav-item.active-ssr` | `.nav-item.active` (e l'`active-ssr` viene rimosso) | tab-nav |
| `div.row-title` testo semplice | link header (fuori da related) / `a[href=""]` (related) | §7.6 |

Regola pratica: qualsiasi selettore con prefisso `.ssr-` o `[class*="loading"]` è skeleton/stato transitorio → blacklist; le classi client definite sopra sono la whitelist.

### 7.9 WATCH embed — verifica definitiva (nessun player inline)

- **Non esiste player inline sulla pagina titolo in nessun caso.** `playVideo` (composable common, VERIFICATO-JS riga per riga): `preventDefault` → animazione su `document.body` (`transform: scale(1.04)`, `opacity: 0`, `transition: 750ms ease-in`, origin al centro verticale dello schermo) → dopo `setTimeout(750)` → `route("titles.watch", {locale, title_id, e: episode?.id, s: billboard_season_num?})` via **Inertia `N.get`** (= navigazione SPA a `/it/watch/<id>[?e=<ep>&s=<n>]`). Fuori da app_url/auth_url (domini mirror) → `window.location.href` (full reload).
- `coming_soon` o `release_date` futura → `pushAlert("In arrivo prossimamente")` e NESSUNA navigazione.
- Il guard `tv && !episode` dentro la pagina Watch esiste ma è un safety-net: il server risolve l'episodio dal `title_id` (o dal `?e=`). Il play del TitlePreview passa `episode=null` → funziona (il server sceglie); il play della card episodio passa l'episodio esplicito.
- Conseguenze modulo: (1) dopo il click su play/episodio la pagina **si sfuma in 750ms e cambia** — congelare il fuoco durante l'animazione (tutti i rect diventano invalidi) e riarmare al nuovo page-kind `Titles/Watch` (annulla anello, §1.5); (2) l'href `/it/watch/...` dell'`a.play` è utilizzabile come segnale di navigazione, quello degli episodi NO (l'href dice `/it/titles/...` ma va in watch).
- Unico "player sopra il titolo": NESSUNO. La pagina watch non è overlay: è un'altra rotta Inertia. Il back dal watch usa `postMessage REFERER_BACK` → `B.get(route("titles.show",...))` (già §1.5).

### 7.10 Whitelist/blacklist dedicate alla pagina titolo (compendio)

```js
// WHITELIST (ordine di tab:-flow)
'.overview-tab .video-actions a.play'            // Play/Continua (resumable)
'.video-actions .download-btn'                   // download (apre dialog/premium o scrolla alle stagioni)
'.user-action:not(.rating) .title-btn'           // +lista, stella, dots (escludere .rating!)
'nav.tab-nav > div.nav-item:not(.download)'      // switch tab (il tab download è un bottone download)
'.season-selector button.season-trigger'         // apertura dropdown stagioni
'.season-list .season-item'                      // solo a dropdown aperto (v-if)
'.episode-slider .slider-item > a'               // riga episodio (onClick=playVideo)
'.episode-slider .download-btn'                  // download per episodio (target secondario)
'.sliders .slider-item > a'                      // card correlate/trending/… (dedup href per rail!)
'.plot .read-more'
'.extra .genre a, .details-tab .genre a'         // primi 2 generi solo

// BLACKLIST (con motivo)
'.background-wrap *, .dark-bg-left, .dark-bg-right'      // backdrop/gradienti (nessun handler)
'.overview-tab video, .background-image-loader'          // preview hero autoplay (sfondo)
'h1, .logo-image, .features span, .badge'                // testo/meta
'.user-action.rating'                                    // punteggio display-only (title-btn senza onClick)
'.watching, .watching-label'                             // progress "S1:E2" (overlay)
'.user-action .label, .label'                            // label testuali sotto i bottoni
'.trigger-btn .tooltip'                                  // tooltip mouseenter desktop
'nav.tab-nav > div.nav-item.download .download-btn span' // label interna; il target è .download-btn
'.tab-indicator, .slide.slide-left, .slide.slide-right'  // pallini + frecce rail
'.slider-row .row-header a[href=""]'                     // header related: href vuoto (same-visit)
'[class*="ssr-"], [class*="loading"], .boxart.pulsate'   // skeleton
'.trailers-tab iframe, .modal iframe'                    // YouTube iframe (zona morta)
'.rate-tooltip .rating-stars'                            // rating a mouseover: non adatto al D-pad
```

Delay/rescan da schedulare lato modulo: (a) click tab → rescan immediato (vShow) ma trailer-tab lazy → rescan su comparsa slider; (b) cambio stagione → rescan a skeleton sparito (partial reload Inertia); (c) apertura dropdown → rescan per gli item; (d) apparizione rail ScrollSliders client-only → rescan; (e) click play/episodio → congelare 750ms (fade body) poi rilasciare il controllo alla pagina watch; (f) preview aperta → scan aggiuntivo su `body > .preview-wrap` (§3).
