/* SC Viewer — page-injection script.
 * Injected by SCWebViewClient.onPageFinished.
 *
 * Responsibilities:
 *   1. Cosmetic hide — strip residual ad containers (network block already
 *      dropped the requests; this just removes the empty placeholders).
 *   2. Anti-redirect — neutralise popunders / window.open hijacks and force
 *      all link clicks to navigate in the current tab instead of opening
 *      new windows (Fire TV remote can't switch tabs).
 *   3. Skip-intro detection — watch the video element; if playback has
 *      started and the current time is < 30s, surface a "Salta intro"
 *      button by calling AndroidJs.onSkipIntroAvailable(true).
 *   4. Virtual tap helper — supports the NATIVE CursorController: exposes a
 *      mousedown sentinel (__scvTouchSeen) and a __scvTap(viewX, viewY, ...)
 *      fallback that clicks the DOM element under the pointer if the WebView
 *      ever ignores the synthetic touch.
 *   5. Hero trailer tame — rimozione degli iframe vixcloud.cc/embed (trailer
 *      hero in HLS) su pagine NON watch: il decoder dell'iframe cross-origin
 *      brucia CPU/GPU su stick da 1 GB e l'intercettore 'play' non lo vede.
 *
 * NOTE: in "pointer mode" the D-pad drives the NATIVE CursorController
 * (virtual mouse cursor overlay). In the default "D-pad mode" the native side
 * dispatches window.__sctvNav.move(dir) / .activate() — see section 9 for the
 * full contract (move returns true | 'edge' | 'top' | false).
 *
 * All globals are namespaced under window.__scviewer to avoid collisions
 * with the site.
 */
(function () {
  if (window.__scviewerInjected) return;
  window.__scviewerInjected = true;

  // Document identity for the tap-sentinel protocol (see __scvTap below):
  // lets the native side detect that the page navigated between the touch
  // and the fallback check, in which case the fallback must NOT fire.
  window.__scvDocId = Date.now();

  // ---- 1. Cosmetic hide -----------------------------------------------------
  var HIDE_SELECTORS = [
    // Common ad / iframe wrappers
    'div[id^="google_ads_"]',
    'div[id^="div-gpt-ad"]',
    'ins.adsbygoogle',
    'iframe[src*="doubleclick.net"]',
    'iframe[src*="googlesyndication.com"]',
    'iframe[src*="ads"]',
    'div[class*="advert"]',
    'div[class*="adsbygoogle"]',
    'div[class*="banner-ad"]',
    'div[class*="pop-"]',
    'div[class*="popup"]',
    'div[id*="popunder"]',
    'div[id*="overlay-ad"]',
    'a[href*="popads"]',
    'a[href*="popcash"]',
    'a[href*="propellerads"]',
    'a[href*="adsterra"]',
    // Streamingcommunity-specific common offenders
    'div[class*="ads-container"]',
    'div[id*="ads-container"]',
    // Site's own Telegram banner bar (top) + its ✕ button
    'header a>div.banner',
    'header div.close-banner'
  ];

  function hideAds() {
    try {
      var css = HIDE_SELECTORS.join(',') + ' { display: none !important; visibility: hidden !important; }';
      var style = document.getElementById('scviewer-hide-style');
      if (!style) {
        style = document.createElement('style');
        style.id = 'scviewer-hide-style';
        style.textContent = css;
        document.head.appendChild(style);
      } else if (style.textContent !== css) {
        // Write ONLY when the value actually changes. Setting textContent
        // unconditionally is itself a childList mutation inside the observed
        // subtree — the MutationObserver below would re-fire hideAds() on its
        // own write, forever, wedging the renderer's JS thread (no click
        // handlers, no timers, ~100% CPU) and eventually OOM-crashing it.
        style.textContent = css;
      }
    } catch (e) { /* no-op */ }
  }

  // ---- 5b. Trailer hero = IFRAME vixcloud embed ------------------------------
  // Scoperta runtime: il trailer dell'hero (home) NON è un <video> del frame
  // principale ma un IFRAME cross-origin https://vixcloud.cc/embed/<id> con
  // player HLS dentro: l'intercettore 'play' (main frame) non lo vede, il
  // video decodifica continuamente (MediaCodec attivo, frame persi) e
  // brucia CPU/GPU su uno stick da 1 GB.
  //
  // STRADA SCELTA (documentata): rimozione via page_inject, NON blocco in
  // shouldInterceptRequest (SCWebViewClient). Motivi:
  //   1. L'intercettore deve decidere dall'URL corrente della WebView, ma
  //      legge su thread background: servirebbe un campo @Volatile aggiornato
  //      da onPageStarted/onPageInfoChanged. Quel riscaldamento ARRIVA TARDI
  //      nel flusso critico: l'ingresso in /it/watch/ è una navigazione SPA
  //      (mappa §7.9 — pushState → swap DOM → request dell'iframe embed) e
  //      il report Inertia che aggiorna l'URL cachato arriva DOPO la request
  //      dell'iframe: con l'URL stantio (ancora /it/titles/...) un blocco
  //      di vixcloud.cc/embed/* ucciderebbe IL PLAYER VERO. L'esito peggiore
  //      possibile; qui invece location.pathname del frame principale è
  //      sempre fresco e sincrono — guard esatto, nessuna corsa.
  //   2. RIMOZIONE dal DOM, non display:none: display:none NON ferma la
  //      decodifica di un iframe cross-origin (il player continuerebbe a
  //      bruciare CPU da nascosto); la rimozione scarica l'intero frame
  //      (rete + decoder + superficie di composizione — teardown standard
  //      del documento, non manipolazione di layer). L'incidente "compositor
  //      nero" passato nasceva nascondendo via CSS un VIDEO GIÀ IN PLAY;
  //      qui si rimuove un iframe, via diversa. Fallback visivo intatto:
  //      dietro l'iframe l'hero ha .background-wrap/.background-image
  //      (mappa §1.2) che ricompare subito.
  // Guard: su /it/watch/ l'iframe vixcloud È il player (mappa §1.5) — mai
  // toccato. Solo il path /embed/: playlist e segmenti (/playlist/,
  // /download/) non passano di qui (l'iframe non li referenzia come src).
  function tameHeroTrailerIframes() {
    try {
      if (location.pathname.indexOf('/it/watch/') === 0) return;
      var ifr = document.querySelectorAll('iframe[src*="vixcloud.cc/embed"]');
      for (var i = 0; i < ifr.length; i++) {
        try {
          if (ifr[i].parentNode) ifr[i].parentNode.removeChild(ifr[i]);
        } catch (eR) { /* no-op */ }
      }
    } catch (e) { /* no-op */ }
  }

  // ---- 2. Anti-redirect / anti-popunder ------------------------------------
  function killPopunders() {
    try {
      // Block window.open: restituisce uno STUB che ingoia qualsiasi
      // location/replace degli script popunder (tornare null fa crashare i
      // loro script o scatena fallback su about:blank).
      var stub = {
        document: { write: function () {}, writeln: function () {},
                    open: function () {}, close: function () {} },
        location: { href: '', replace: function () {}, assign: function () {} },
        focus: function () {}, blur: function () {}, close: function () {},
        opener: null, closed: true, name: ''
      };
      window.open = function () { return stub; };
      // Force _self on every link.
      document.addEventListener('click', function (e) {
        var a = e.target && e.target.closest ? e.target.closest('a') : null;
        if (a && a.target && a.target !== '_self' && a.target !== '_top') {
          a.target = '_self';
          a.removeAttribute('target');
        }
      }, true);
      // Disable beforeunload hijacks that try to keep users on a page.
      window.onbeforeunload = null;
      Object.defineProperty(window, 'onbeforeunload', {
        configurable: false,
        writable: false,
        value: null
      });
      // Also block the addEventListener overload some popunder libs use.
      var origAdd = window.addEventListener;
      window.addEventListener = function (type, listener, opts) {
        if (type === 'beforeunload' || type === 'unload') return;
        return origAdd.call(this, type, listener, opts);
      };
    } catch (e) { /* no-op */ }
  }

  // ---- 4. Virtual tap helper (native cursor support) -----------------------
  // CursorController dispatches a synthetic touch to the WebView. If the
  // WebView drops it, no mousedown appears; the native side then calls
  // __scvTap, which clicks the DOM element under the pointer directly.
  // Coordinates come in as VIEW pixels and are converted to CSS pixels
  // using the layout-viewport ratio (no deprecated scale APIs needed).
  window.__scvTouchSeen = 0;
  document.addEventListener('mousedown', function () {
    // Monotonic counter — the native CursorController reads this before and
    // after each synthetic tap to decide whether the touch reached the page
    // or a DOM-level fallback click must be fired instead.
    window.__scvTouchSeen = (window.__scvTouchSeen | 0) + 1;
  }, true);

  window.__scvTap = function (viewX, viewY, viewW, viewH) {
    try {
      var cw = document.documentElement.clientWidth;
      var ch = document.documentElement.clientHeight;
      if (!cw || !ch || !viewW || !viewH) return '0';
      var x = viewX * cw / viewW;
      var y = viewY * ch / viewH;
      var el = document.elementFromPoint(x, y);
      if (!el) return '0';
      el.click();
      return '1';
    } catch (e) {
      return '0';
    }
  };

  // ---- 7. "Guarda" interceptor (resume popup) --------------------------------
  // The play button (hero + title pages) is an anchor to /watch/<id>. When
  // the user has saved progress for that title, the native side asks "riprendi?"
  // BEFORE navigation: we query it synchronously here (JS-interface calls
  // return on the calling thread) and block the navigation while the popup is
  // up. Native restarts navigation via __scvContinuePlay on "Ricomincia".
  document.addEventListener('click', function (e) {
    try {
      var a = e.target && e.target.closest ? e.target.closest('a[href*="/watch/"]') : null;
      if (!a) return;
      var m = (a.getAttribute('href') || '').match(/\/watch\/(\d+)/);
      if (!m) return;
      var verdict = 'none';
      try { verdict = AndroidJs.onPlayClicked(m[1]) || 'none'; } catch (err) { return; }
      if (verdict === 'resume') {
        window.__scvPendingPlayHref = a.href;
        e.preventDefault();
        e.stopImmediatePropagation();
      }
    } catch (err2) { /* no-op */ }
  }, true);

  window.__scvContinuePlay = function () {
    var h = window.__scvPendingPlayHref;
    window.__scvPendingPlayHref = null;
    if (h) { window.location.href = h; return 'ok'; }
    return 'no-href';
  };

  // ---- Run ----------------------------------------------------------------
  hideAds();
  killPopunders();
  tameHeroTrailerIframes();
  installScrollGuard();

  // ---- 8. Rilevamento pagina di blocco anti-bot -----------------------------
  // I blocchi Cloudflare (403/503 + interstitial) renderizzano comunque una
  // pagina: se il titolo/le prime righe di testo combaciano con le firme dei
  // blocchi, avvisa il nativo che recupera cambiando mirror (e UA dopo N
  // blocchi). FIRME SOLO sul titolo per evitare falsi positivi dal body del
  // sito vero; sul body solo le frasi tipiche del blocco.
  try {
    var scvTitle = document.title || '';
    var scvBody = document.body ? document.body.innerText.slice(0, 3000) : '';
    var scvBlocked =
      /just a moment|attention required|access denied|cloudflare|error 1106/i.test(scvTitle) ||
      /sorry, you have been blocked|checking your browser|enable javascript and cookies/i.test(scvBody);
    if (scvBlocked) {
      try { AndroidJs.onCloudflareBlock(); } catch (e) { /* no-op */ }
    }
  } catch (eBlock) { /* no-op */ }

  // ---- 6. Page info collector -----------------------------------------------
  // The site is Laravel Inertia: the server embeds the page state as JSON in
  // the data-page attribute of the SSR HTML (Vue strips it at hydration, so
  // the LIVE DOM has no [data-page]). Same-origin fetch of the current URL
  // reliably returns it: show name, movie/series type and — on watch pages —
  // the exact season/episode being played. Native uses it for History and
  // Favorites naming and for the "Riprendi" (resume) feature.
  function unescapeAttr(s) {
    return s.replace(/&quot;/g, '"')
            .replace(/&#0?39;/g, "'")
            .replace(/&lt;/g, '<')
            .replace(/&gt;/g, '>')
            .replace(/&amp;/g, '&');
  }

  function extractInfo(html) {
    try {
      var m = html.match(/data-page="([^"]+)"/);
      if (!m) return null;
      var j = JSON.parse(unescapeAttr(m[1]));
      var t = j && j.props && j.props.title;
      if (!t || !t.id) return null;
      var ep = j.props.episode || null;
      return {
        component: j.component || '',
        titleId: t.id,
        name: t.name || '',
        slug: t.slug || '',
        type: t.type || '',
        season: (ep && ep.season) ? ep.season.number : null,
        episode: ep ? ep.number : null,
        episodeId: ep ? ep.id : null,
        episodeName: ep ? (ep.name || '') : null,
        nextEpisodeId: (j.props.nextEpisode) ? j.props.nextEpisode.id : null,
        nextEpisodeNumber: (j.props.nextEpisode) ? j.props.nextEpisode.number : null,
        poster: (function() {
          var cdn = (j.props && j.props.cdn_url) || '';
          var imgs = (t.images || []);
          for (var k = 0; k < imgs.length; k++) {
            if (imgs[k].type === 'poster' && imgs[k].filename)
              return cdn + '/images/' + imgs[k].filename;
          }
          // fallback: og:image (cover orizzontale, meglio di niente)
          var og = html.match(/property="og:image" content="([^"]*)"/);
          return og ? og[1] : '';
        })()
      };
    } catch (e) { return null; }
  }

  // Watch pages spesso non espongono l'array images del titolo: recupera il
  // poster dalla pagina del titolo (stessa origine, SSR con data-page) e lo
  // passa al nativo (PosterCache). Una sola fetch per titolo per documento.
  var posterFetchedFor = null;
  function maybeFetchTitlePoster(info) {
    try {
      if (!info || info.poster || location.pathname.indexOf('/watch/') < 0) return;
      if (posterFetchedFor === info.titleId) return;
      posterFetchedFor = info.titleId;
      fetch('/it/titles/' + info.titleId + '-' + info.slug, { credentials: 'same-origin' })
        .then(function (r) { return r.text(); })
        .then(function (html) {
          var t = extractInfo(html);
          if (t && t.poster) {
            try { AndroidJs.onPosterFound(String(info.titleId), t.poster); } catch (e) { /* no-op */ }
          }
        })
        .catch(function () { /* no-op */ });
    } catch (e) { /* no-op */ }
  }

  var infoBusy = false;
  var lastReportedPath = null;
  function reportPageInfo(force) {
    if (infoBusy) return;
    // Stesso URL di quello già riportato: niente ri-fetch (i partial reload
    // di Inertia fanno replaceState a URL invariato e la ri-fetch della
    // watch URL a volte viene rimandata alla home dal server, corrompendo
    // lo stato page-info corrente).
    var path = location.pathname + location.search;
    if (!force && path === lastReportedPath) return;
    infoBusy = true;
    try {
      fetch(path, { credentials: 'same-origin' })
        .then(function (r) { return r.text(); })
        .then(function (html) {
          infoBusy = false;
          var info = extractInfo(html);
          if (!info) {
            // Pagina senza titolo (home/archive/generi): riporta comunque il
            // component Inertia così il nativo aggiorna l'immersività.
            try {
              var dm = html.match(/data-page="([^"]+)"/);
              var dc = dm ? (JSON.parse(unescapeAttr(dm[1])).component || '') : '';
              if (dc) AndroidJs.onPageInfo(JSON.stringify({ component: dc, titleId: 0 }));
            } catch (e) {}
            return;
          }
          if (info) {
            lastReportedPath = location.pathname + location.search;
            try { AndroidJs.onPageInfo(JSON.stringify(info)); } catch (e) { /* no-op */ }
            maybeFetchTitlePoster(info);
          }
        })
        .catch(function () { infoBusy = false; });
    } catch (e) { infoBusy = false; }
  }
  window.__scvReportPageInfo = function(){ reportPageInfo(true); };

  // ---- 9. Navigazione D-pad a RIGHE (modello Netflix TV) --------------------
  //
  // Sostituisce il navigatore puramente geometrico, che sulle griglie di card
  // del sito saltava tra righe e perdeva l'ordine visivo. Modello:
  //
  //  • I candidati focalizzabili vengono raggruppati in RIGHE visive per
  //    sovrapposizione verticale dei rect (un elemento entra nella riga
  //    quando copre ≥ 40% della propria altezza con la banda della riga,
  //    min 8px di rumore). GIÙ/SU cambiano riga con snap alla colonna più
  //    vicina; SINISTRA/DESTRA si muovono DENTRO la riga.
  //  • Se la riga scorre orizzontalmente, scrolla SOLO il suo contenitore
  //    overflow-x (mai la pagina); la pagina scorre solo in verticale.
  //  • Il fuoco simula mouseover/mouseenter: i popup hover del sito entrano
  //    come candidati e vengono FUSI nella riga della card (un subtree
  //    appena aggiunto al DOM è un popup), mai in righe fantasma.
  //  • Fuoco perso (popup chiuso, nodo rimosso, SPA) → si riprende dal
  //    candidato visivamente più vicino all'ultima posizione dell'anello.
  //
  // API (MainActivity chiama ESATTAMENTE questi nomi via evaluateJavascript
  // — NON rinominarli):
  //   __sctvNav.move('up'|'down'|'left'|'right')
  //       → true   : anello mosso o pagina/riga scorrita
  //       → 'edge' : SINISTRA dal bordo sinistro assoluto → il nativo apre
  //                  la sidebar
  //       → 'top'  : SU dalla prima riga in cima pagina → il nativo può
  //                  aprire la sidebar
  //       → false  : nessun movimento possibile
  //   __sctvNav.activate()  : click sul candidato a fuoco; focus() sui campi.
  //   __sctvNav.reset()     : azzera fuoco + cache e ricentra sul candidato
  //                           più vicino al centro viewport. Sicuro da chiamare
  //                           da PageListener.onPageLoaded (no-op se il modulo
  //                           è appena stato iniettato su un documento nuovo).
  //   __sctvNav.isTyping()  : true se il fuoco è in un input/textarea/select
  //                           ATTIVO — in quello stato SINISTRA/DESTRA
  //                           muovono il caret del campo (B14, gestito nel
  //                           modulo) e SU/GIÙ escono dal campo con blur;
  //                           il nativo può usare il valore per decidere
  //                           se aprire la tastiera su OK.
  //   __sctvNav.spaReset()  : interno — hook pushState/popState di Inertia
  //                           (sezione 10): ricentra il fuoco dopo ogni
  //                           navigazione SPA.
  (function () {
    // Lo stub document-start (coda tasti premuti durante il caricamento)
    // occupa __sctvNav: installa il modulo reale solo se non c'è già quello
    // vero (contrassegnato __real).
    if (window.__sctvNav && window.__sctvNav.__real) return;

    // ---- Costanti di design -------------------------------------------------
    // Clustering: sovrapposizione verticale richiesta perché un elemento
    // entri in una riga — 40% della sua altezza (spec), con pavimento di 8px
    // così il rumore di layout non incolla righe di testo vicine.
    var ROW_OVERLAP_RATIO = 0.4;
    var ROW_OVERLAP_MIN_PX = 8;
    // B7: bordo sinistro del contenuto DINAMICO. Il costante 48px non reggeva
    // su layout diversi (griglie indentate, hero full-bleed, pagine titolo):
    // padding = mediana dei left minimi per riga (robusta a righe popup e a
    // caroselli scrollati fuori schema), fallback sul padding del body.
    function pagePadX(rows) {
      var lefts = [];
      for (var i = 0; i < rows.length; i++) {
        var ms = rows[i].members;
        if (ms.length < 2) continue; // riga a 1 membro: spesso popup → rumorosa
        var mn = Infinity;
        for (var j = 0; j < ms.length; j++) {
          if (ms[j].r.left < mn) mn = ms[j].r.left;
        }
        if (mn > 0 && mn < 200) lefts.push(mn);
      }
      if (lefts.length) {
        lefts.sort(function (a, b) { return a - b; });
        return lefts[Math.floor(lefts.length / 2)];
      }
      try {
        var pl = parseFloat(getComputedStyle(document.body).paddingLeft);
        if (pl > 0) return pl;
      } catch (e) { /* no-op */ }
      return 0;
    }
    // Culling dei candidati: finestra attorno al viewport molto più larga
    // del viewport (le righe sopra/sotto restano navigabili e la messa a
    // fuoco le centra), abbastanza stretta da tenere la scansione < 100ms.
    var CULL_Y_FACTOR = 1.2, CULL_X_FACTOR = 0.5, CULL_MIN = 160;
    var MIN_W = 24, MIN_H = 12;
    // B6a: selettore unico dei candidati semantici, riusato da scan() e dal
    // micro-merge dei nodi recenti in buildRows (stringa identica a prima).
    var FOCUS_SEL =
      'a[href],button,[role="button"],input,select,textarea,[tabindex]:not([tabindex="-1"])';
    // Mappa §4/§5.9: blacklist applicata PRIMA del fallback pointer — esterni
    // target=_blank (social/banner/verifylink/animeunity/vixsrc), logo, frecce
    // rail, pallini paginazione, overlay trailer, skeleton loading, hamburger
    // e sidebar-mobile (ridondanti: esiste la sidebar nativa). closest copre
    // anche i discendenti.
    var BLACKLIST_SEL =
      'a[target="_blank"],.logo-item a,span.slide,.tab-indicator,.trailer-click,' +
      '[class*="loading"],button.navbar-toggler,.sidebar-mobile,.sidebar-backdrop,' +
      '.socials a,.slide-banner a,.close-banner';
    // Mappa §5.5: durante lo slide del rail (.animating, 750ms) i click/fuoco
    // sono persi (pointer-events:none) — i candidati dentro vengono saltati.
    var ANIM_SEL = '.slider-content.animating';
    function blacklisted(el) {
      try { return !!(el.closest && el.closest(BLACKLIST_SEL)); } catch (eB) { return false; }
    }
    function inAnimating(el) {
      try { return !!(el.closest && el.closest(ANIM_SEL)); } catch (eA) { return false; }
    }
    // Margini oltre i quali la messa a fuoco centra l'elemento.
    var SNAP_Y = 70, SNAP_X = 8;

    var RING = 'sctv-dpad-focus';
    // stile+frame attaccati a documentElement (NON head/body): l'hydration
    // Inertia ricostruisce head e #app cancellando gli elementi iniettati.
    var host = document.documentElement || document.head || document.body;
    // riusa gli elementi creati a document start (se sopravvissuti)
    var st = document.getElementById('sctv-dpad-style');
    if (!st) {
      st = document.createElement('style');
      st.id = 'sctv-dpad-style';
      st.textContent = '.' + RING + '{outline:none!important}' +
        // Login e ricerca nell'header del sito: inutili nell'app (la ricerca
        // è nella sidebar nativa) — rimossi dalla home e da tutte le pagine.
        '.nav-item.btn-login,.nav-item.search-bar-item{display:none!important}';
      host.appendChild(st);
    }

    // ---- Anello -------------------------------------------------------------
    // Il ring è un frame position:fixed sopra TUTTO (z-index massimo): le
    // card vicine del sito non possono coprirlo. Stile minimale: niente
    // transizioni né ombre larghe — su Mali-450 (stick 1 GB) repaint storm
    // a ogni movimento = input lag percepito.
    var frame = document.getElementById('sctv-dpad-frame');
    if (!frame) {
      frame = document.createElement('div');
      frame.id = 'sctv-dpad-frame';
      frame.style.cssText = 'position:fixed;pointer-events:none;display:none;' +
        'z-index:2147483000;border:4px solid #FFFFFF;border-radius:14px;' +
        'box-shadow:0 0 0 5px rgba(229,9,20,.55)';
      host.appendChild(frame);
    }

    // Watchdog: se hydration/SPA rimuovono stile o frame, ri-appendi.
    setInterval(function () {
      if (!document.getElementById('sctv-dpad-style') && st.parentNode !== host) {
        try { host.appendChild(st); } catch (e) {}
      }
      if (!document.getElementById('sctv-dpad-frame') && frame.parentNode !== host) {
        try { host.appendChild(frame); } catch (e) {}
      }
    }, 1500);
    var framePad = 7;
    var lateTimer = null, scrollFlagTimer = null, spaTimer = null;

    function placeFrameRect(r) {
      frame.style.display = 'block';
      frame.style.left = (r.left - framePad) + 'px';
      frame.style.top = (r.top - framePad) + 'px';
      frame.style.width = (r.width + framePad * 2) + 'px';
      frame.style.height = (r.height + framePad * 2) + 'px';
    }
    function placeFrame(el) { if (el) placeFrameRect(el.getBoundingClientRect()); }
    function hideFrame() { frame.style.display = 'none'; }

    // B17: scroll/resize arrivano a raffica (fling, caroselli) — un handler
    // sincrono per evento forzava layout+style ripetuti (repaint storm su
    // Mali-450). Dirty-flag + UN rAF per frame; se non c'è nulla di sporco
    // il rAF muore subito senza costi.
    var frameDirty = false, frameRAF = 0;
    function scheduleFrame() {
      if (frameRAF) return;
      frameRAF = requestAnimationFrame(function () {
        frameRAF = 0;
        if (!cur || !frameDirty) return;
        frameDirty = false;
        placeFrame(cur);
      });
    }
    window.addEventListener('scroll', function () {
      if (!cur) return;
      frameDirty = true;
      scheduleFrame();
    }, true);
    window.addEventListener('resize', function () {
      if (!cur) return;
      frameDirty = true;
      scheduleFrame();
    });

    // rAF leggero SOLO durante gli scroll smooth: l'anello segue l'elemento
    // finché la sua posizione viewport non si stabilizza (2-3 frame dopo
    // l'arrivo), poi il loop si spegne da solo.
    var followId = 0, followUntil = 0;
    function startFollow(ms) {
      followUntil = Date.now() + (ms || 900);
      if (followId) return;
      var stable = 0, lastKey = '';
      var tick = function () {
        followId = 0;
        if (!cur) return;
        var r = cur.getBoundingClientRect();
        placeFrameRect(r);
        var key = Math.round(r.left) + ':' + Math.round(r.top);
        if (key === lastKey) stable++; else { stable = 0; lastKey = key; }
        if (stable < 3 && Date.now() < followUntil) {
          followId = requestAnimationFrame(tick);
        }
      };
      followId = requestAnimationFrame(tick);
    }

    // Gli scroll generati dalla navigazione (centratura dell'anello) non
    // devono finire nel guard anti-reset di Inertia (installScrollGuard:
    // vede gli scroll-to-top come reset del sito se c'è stato uno scroll
    // recente) — il flag li fa passare sempre.
    function navScrollStart() {
      window.__scvNavScrolling = true;
      clearTimeout(scrollFlagTimer);
      scrollFlagTimer = setTimeout(function () { window.__scvNavScrolling = false; }, 1200);
    }

    // ---- Stato --------------------------------------------------------------
    var cur = null;            // elemento a fuoco
    var lastCenter = null;     // centro viewport dell'ultimo fuoco (recupero)
    var cache = null, cacheTimer = null, cacheAt = 0;
    // La scansione completa (getComputedStyle su migliaia di nodi) costa
    // 100-300 ms su uno stick: le tempeste di mutazioni del sito NON devono
    // invalidarla per ogni tasto. TTL morbido: la lista resta valida 2,5 s
    // (i rect sono riletti live in buildRows, quindi scroll e nodi rimossi
    // si filtrano da soli); rescan forzato solo su swap SPA.
    var CACHE_TTL = 2500;
    var pendingCenter = false; // ricentro dopo SPA nav (consumato dall'observer)
    var spaAnchor = false;     // B15: la SPA nav in corso è un popstate (back/forward)
    var lastMutAt = 0;

    // Subtree aggiunti di recente al DOM = popup hover aperti (vedi merge
    // righe-p popup in buildRows). Mappa nodo → timestamp.
    var recent = new Map();
    var RECENT_TTL = 4000;
    function popupish(el) {
      var n = el, hops = 0;
      while (n && hops < 6) {
        if (recent.has(n)) return true;
        // Mappa §3: la preview è un componente Vue TELEPORTATO su <body>
        // con classe .preview-wrap — fuori dalla card. Anche fuori dalla
        // finestra `recent` resta un popup da fondere nella riga della card.
        if (n.classList && n.classList.contains('preview-wrap')) return true;
        if (n === document.body) break;
        n = n.parentElement;
        hops++;
      }
      return false;
    }

    // Il sito è una SPA: la lista candidati si ricalcola SEMPRE fuori dal
    // percorso dei tasti (background rescan), mai bloccando un movimento.
    // centerFocus/reset fanno lo scan sincrono (nuova pagina).
    var scanPending = false;
    function rescanSoon(delay) {
      if (scanPending) return;
      scanPending = true;
      // 0 esplicito = rescan al tick successivo (chiamato dal percorso tasti
      // quando la cache è scaduta); senza argomento usa il default 180.
      var d = (delay === undefined || delay === null) ? 180 : delay;
      setTimeout(function () {
        scanPending = false;
        cache = scan();
        cacheAt = Date.now();
      }, d);
    }
    // I trailer in autoplay nell'hero del sito: la loro superficie video
    // (fork Fire TV del WebView) copre la sidebar nativa e mangia CPU su
    // uno stick da 1 GB — pausa e nasconde i video del frame principale.
    // Il player watch è in un iframe separato: non viene toccato.
    function tameVideos() {
      var vs = document.querySelectorAll('video');
      for (var i = 0; i < vs.length; i++) {
        try { vs[i].pause(); vs[i].muted = true; } catch (e) {}
      }
    }
    // Il sito rimette in play i trailer a loop dai suoi timer: intercetta
    // l'evento 'play' (capture, frame principale) e rimetti in pausa subito.
    // Senza questo la CPU resta alta e la nav risulta laggosa.
    document.addEventListener('play', function (e) {
      var v = e.target;
      if (v && v.tagName === 'VIDEO') {
        try { v.pause(); v.muted = true; } catch (e2) {}
      }
    }, true);

    var obs = new MutationObserver(function (muts) {
      lastMutAt = Date.now();
      var batchAddedEls = false;
      for (var i = 0; i < muts.length; i++) {
        var added = muts[i].addedNodes;
        for (var j = 0; j < added.length; j++) {
          var n = added[j];
          if (n.nodeType === 1) { recent.set(n, lastMutAt); batchAddedEls = true; }
        }
      }
      if (recent.size > 240) {
        recent.forEach(function (t, el) {
          if (lastMutAt - t > RECENT_TTL) recent.delete(el);
        });
      }
      if (cacheTimer) return;
      cacheTimer = setTimeout(function () {
        cacheTimer = null;
        tameVideos();
        // Fuoco da ricentrire dopo una navigazione SPA: attendi la quiete
        // delle mutazioni (il swap di Inertia arriva a batch), poi ricentra.
        if (pendingCenter && Date.now() - lastMutAt > 250) {
          pendingCenter = false;
          clearTimeout(spaTimer);
          doSpaFocus();
          return;
        }
        // B3: batch con elementi NUOVI nel DOM (card caricate, popup) →
        // rescan debounced fuori dal percorso tasti. Mutazioni solo-testo
        // non toccano la lista candidati.
        if (batchAddedEls) rescanSoon(500);
      }, 120);
    });
    function startObserver() {
      if (document.body && !obs._started) {
        // B17: niente attributes — le mutazioni di attributo (classi hover,
        // stili) non aggiungono candidati e generavano batch infiniti con i
        // timer del sito. Il ring non dipende da attributi del sito.
        obs.observe(document.body, { childList: true, subtree: true });
        obs._started = true;
      }
    }
    startObserver();
    setTimeout(startObserver, 800);
    setTimeout(tameVideos, 400);
    setTimeout(tameVideos, 1500);

    // ---- Candidati ----------------------------------------------------------
    function scan() {
      var out = [], seen = new Set();
      var vw = innerWidth, vh = innerHeight;
      var cullY = Math.max(CULL_MIN, vh * CULL_Y_FACTOR);
      var cullX = Math.max(CULL_MIN, vw * CULL_X_FACTOR);
      var i, el, r, cs;

      function ok(rr) {
        if (rr.width < MIN_W || rr.height < MIN_H) return false;
        if (rr.bottom < -cullY || rr.top > vh + cullY ||
            rr.right < -cullX || rr.left > vw + cullX) return false;
        return true;
      }
      function add(e, rr, sem) {
        e.__sctvRect = rr;
        e.__sctvSem = !!sem;
        seen.add(e);
        out.push(e);
      }

      // 1. semantica: link, bottoni, campi, tabindex
      var nodes = document.querySelectorAll(FOCUS_SEL);
      var hrefs = new Set();
      for (i = 0; i < nodes.length; i++) {
        el = nodes[i];
        if (el.disabled || (el.getAttribute && el.getAttribute('aria-disabled') === 'true')) continue;
        r = el.getBoundingClientRect();
        if (!ok(r)) continue;
        cs = getComputedStyle(el);
        if (cs.visibility === 'hidden' || cs.display === 'none') continue;
        if (blacklisted(el) || inAnimating(el)) continue;
        // Mappa §5.1: rail infinite = cloni con lo STESSO href a slot diversi
        // (non si sovrappongono: dedup per contenimento/IoU non li vede) —
        // senza questo il fuoco "cammina all'infinito" sullo stesso titolo.
        // Tieni la prima occorrenza nella finestra cull (dedup DOPO ok():
        // un clone fuori finestra non deve consumare l'href di quello visibile).
        if (el.tagName === 'A') {
          var hh = el.getAttribute('href') || '';
          if (hh) {
            if (hrefs.has(hh)) continue;
            hrefs.add(hh);
          }
        }
        add(el, r, true);
      }

      // 2. euristica Vue: QUALSIASI elemento cliccabile senza semantica è
      //    riconoscibile dal cursore pointer (card, voci, tab, chip).
      //    Pre-filtro per rect PRIMA di getComputedStyle: il check costoso
      //    si paga solo sui sopravvissuti (centinaia, non migliaia).
      var all = document.getElementsByTagName('*');
      for (i = 0; i < all.length; i++) {
        el = all[i];
        if (seen.has(el)) continue;
        r = el.getBoundingClientRect();
        if (!ok(r)) continue;
        cs = getComputedStyle(el);
        if (cs.cursor !== 'pointer' || cs.visibility === 'hidden' ||
            cs.display === 'none' || cs.pointerEvents === 'none') continue;
        if (blacklisted(el) || inAnimating(el)) continue;
        add(el, r, false);
      }

      // Mappa §2.1: TOP 10 — svg.svg-icon.svg-icon-rank-N.top-10-rank è
      // DENTRO l'<a> della card (numero gigante, copre numero+poster). La
      // regola "più interno vince" si INVERTE quando l'interno è un svg
      // decorativo con un antenato semantico candidato: tieni l'ANTENATO.
      // QA E: il <path> INTERNO all'svg passava l'esclusione limitata a
      // <svg> e batteva la card sul centro-x → tutti i discendenti SVG.
      var SVGTAGS = 'svg,path,use,g,polygon,circle,rect,line,polyline,ellipse,text';
      var out2 = [];
      for (i = 0; i < out.length; i++) {
        var dv = out[i];
        var dropDv = false;
        try {
          dropDv = dv.matches(SVGTAGS);
        } catch (eSv) { dropDv = false; }
        if (!dropDv) {
          try {
            dropDv = dv.classList.contains('svg-icon') ||
                     dv.classList.contains('top-10-rank') ||
                     dv.classList.contains('box-art-top10');
          } catch (eS) { dropDv = false; }
        }
        if (dropDv) {
          var anc2 = dv.parentElement, kept2 = false;
          for (var h2 = 0; h2 < 4 && anc2 && !kept2; h2++) {
            try {
              if (anc2.matches && anc2.matches('a[href],button,[role="button"]') &&
                  seen.has(anc2)) kept2 = true;
            } catch (eM) { break; }
            anc2 = anc2.parentElement;
          }
          if (kept2) {
            delete dv.__sctvRect;
            delete dv.__sctvSem;
            continue;
          }
        }
        out2.push(dv);
      }
      out = out2;

      // 3. dedup per contenimento: fra elementi sovrapposti tiene il più
      //    interno (il target reale del click), scarta il contenitore.
      //    CORREZIONE P0 (QA B/G): la condizione era INVERTITA — controllava
      //    rr (corrente, più grande) dentro kr (tenuto, più piccolo), quasi
      //    sempre falso → sopravvivevano SPAN+ANCHOR dello stesso bottone
      //    (doppio-step: 2 pressioni per controllo) e svg dentro link.
      //    Ora: kr (già tenuto) DENTRO rr → scarta il contenitore rr, con
      //    inversione semantica: se il contenitore è semantico e l'interno
      //    no (span di testo dentro <a>), vince il CONTENITORE — l'anello
      //    abbraccia il bottone intero e OK ha un solo stop per controllo.
      function isSemanticCand(el) {
        try {
          return !!el.matches('a[href],button,input,select,textarea,[role="button"],[tabindex]');
        } catch (eS2) { return false; }
      }
      out.sort(function (a, b) {
        return a.__sctvRect.width * a.__sctvRect.height -
               b.__sctvRect.width * b.__sctvRect.height;
      });
      var kept = [];
      var demoted = new Set();
      for (i = 0; i < out.length; i++) {
        var rr = out[i].__sctvRect;
        var inside = false;
        for (var m = 0; m < kept.length; m++) {
          var kr = kept[m].__sctvRect;
          // kr (tenuto, più piccolo) è dentro rr (corrente, contenitore)?
          if (kr.left >= rr.left - 2 && kr.right <= rr.right + 2 &&
              kr.top >= rr.top - 2 && kr.bottom <= rr.bottom + 2) {
            if (isSemanticCand(out[i]) && !isSemanticCand(kept[m])) {
              // contenitore semantico vs interno decorativo: vince il
              // contenitore, l'interno esce dalla lista tenuta.
              demoted.add(kept[m]);
              continue;
            }
            inside = true; // contenitore non semantico: vince l'interno
            break;
          }
        }
        if (!inside) kept.push(out[i]);
      }
      if (demoted.size) {
        var keptF = [];
        for (i = 0; i < kept.length; i++) {
          if (demoted.has(kept[i])) continue;
          keptF.push(kept[i]);
        }
        kept = keptF;
      }
      // B9: dedup per IoU su coppie SENZA contenimento (il numero gigante
      // "TOP 10" sopra la card): prima restavano entrambi e si navigava anche
      // il numero decorativo. Vince il candidato più semantico (tag
      // interattivo > solo cursor:pointer); a rango pari si mantiene tutto.
      var dropped = new Set();
      for (i = 0; i < kept.length; i++) {
        var ri = kept[i].__sctvRect;
        if (!ri) continue;
        for (var m2 = i + 1; m2 < kept.length; m2++) {
          if (dropped.has(i) || dropped.has(m2)) continue;
          var rj = kept[m2].__sctvRect;
          if (!rj) continue;
          var ix = Math.min(ri.right, rj.right) - Math.max(ri.left, rj.left);
          var iy = Math.min(ri.bottom, rj.bottom) - Math.max(ri.top, rj.top);
          if (ix <= 0 || iy <= 0) continue;
          var inter = ix * iy;
          var union = ri.width * ri.height + rj.width * rj.height - inter;
          if (union <= 0 || inter / union <= 0.4) continue;
          var si2 = kept[i].__sctvSem ? 1 : 0;
          var sj2 = kept[m2].__sctvSem ? 1 : 0;
          if (sj2 > si2) dropped.add(i);
          else if (si2 > sj2) dropped.add(m2);
        }
      }
      var final = [];
      for (i = 0; i < kept.length; i++) {
        if (!dropped.has(i)) final.push(kept[i]);
      }
      for (i = 0; i < out.length; i++) {
        delete out[i].__sctvRect;
        delete out[i].__sctvSem;
      }
      return final;
    }

    // B2: MAI uno scan sincrono nel percorso dei tasti. Cache assente =
    // prima installazione / centerFocus (nuova pagina, nessun tasto in attesa)
    // → scan sincrono ammesso. Cache scaduta → si naviga con la stale (i rect
    // sono riletti live in buildRows: nodi rimossi e scroll si filtrano da
    // soli) e si riscansiona in background al tick successivo.
    function candidates() {
      var now = Date.now();
      if (!cache) {
        cache = scan();
        cacheAt = now;
        return cache;
      }
      if (now - cacheAt > CACHE_TTL) rescanSoon(0);
      return cache;
    }

    // ---- Righe --------------------------------------------------------------
    // Righe costruite sui rect LIVE a ogni passo di navigazione (i rect di
    // scan diventano stidenti dopo lo scroll): n ≈ centinaia, costo ~1-2ms.
    function buildRows(cands) {
      var vh = innerHeight, vw = innerWidth;
      var cullY = Math.max(CULL_MIN, vh * CULL_Y_FACTOR);
      var cullX = Math.max(CULL_MIN, vw * CULL_X_FACTOR);
      var items = [];
      var candSet = new Set(cands);
      for (var i = 0; i < cands.length; i++) {
        var el = cands[i];
        var r = el.getBoundingClientRect();
        if (r.width < 4 || r.height < 4) continue;               // sparito
        if (r.bottom < -cullY || r.top > vh + cullY ||
            r.right < -cullX || r.left > vw + cullX) continue;   // fuori finestra live
        items.push({ el: el, r: r, pop: popupish(el) });
      }
      // B6a micro-merge: i nodi appena aggiunti al DOM (popup hover aperti,
      // bottoni dentro subtree nuovi) entrano nelle righe ANCHE se la cache
      // non li contiene ancora — copre il gap fra comparsa del popup e rescan
      // in background. Limitato ai nodi freschi (≤ 1,2 s), max 24 esaminati e
      // 12 inseriti per chiamata: costo ~1-2ms, niente scan completo.
      if (recent.size) {
        var freshSeen = 0, mergedHot = 0;
        recent.forEach(function (t, n) {
          if (freshSeen >= 24 || mergedHot >= 12) return;
          if (!n || n.nodeType !== 1 || !n.isConnected) return;
          if (lastMutAt - t > 1200) return;
          freshSeen++;
          var subs = null;
          try {
            // il nodo nuovo può essere già interattivo (o solo un wrapper)
            if (n.matches(FOCUS_SEL)) subs = [n];
            else subs = n.querySelectorAll(FOCUS_SEL);
          } catch (eQ) { return; }
          for (var si = 0; si < subs.length && mergedHot < 12; si++) {
            var s = subs[si];
            if (candSet.has(s)) continue;
            var rs = s.getBoundingClientRect();
            if (rs.width < 4 || rs.height < 4) continue;
            if (rs.bottom < -cullY || rs.top > vh + cullY ||
                rs.right < -cullX || rs.left > vw + cullX) continue;
            var csS;
            try { csS = getComputedStyle(s); } catch (eC) { continue; }
            if (csS.visibility === 'hidden' || csS.display === 'none') continue;
            items.push({ el: s, r: rs, pop: true });
            mergedHot++;
          }
        });
      }
      if (!items.length) return [];

      items.sort(function (a, b) {
        return (a.r.top - b.r.top) || (a.r.left - b.r.left);
      });

      // Clustering: ogni elemento entra nella riga con cui ha la MASSIMA
      // sovrapposizione verticale (≥ 40% della sua altezza, min 8px).
      var rows = [];
      for (var j = 0; j < items.length; j++) {
        var it = items[j];
        var need = Math.max(it.r.height * ROW_OVERLAP_RATIO, ROW_OVERLAP_MIN_PX);
        var best = null, bestOv = -1;
        for (var k = 0; k < rows.length; k++) {
          var rw = rows[k];
          var ov = Math.min(it.r.bottom, rw.bottom) - Math.max(it.r.top, rw.top);
          if (ov >= need && ov > bestOv) { bestOv = ov; best = rw; }
        }
        if (!best) {
          best = { top: it.r.top, bottom: it.r.bottom, members: [], allPop: it.pop };
          rows.push(best);
        } else {
          if (it.r.top < best.top) best.top = it.r.top;
          if (it.r.bottom > best.bottom) best.bottom = it.r.bottom;
          if (!it.pop) best.allPop = false;
        }
        best.members.push(it);
      }

      // Merge righe-popup: una riga fatta SOLO di nodi appena aggiunti al
      // DOM è il popup hover di una card. I suoi bottoni entrano nella
      // STESSA riga della card (quella a fuoco se vicina, altrimenti la
      // riga con più sovrapposizione) — mai in una riga fantasma.
      for (var p = rows.length - 1; p >= 0; p--) {
        var pr = rows[p];
        if (!pr.allPop || pr.members.length > 10) continue;
        var target = null;
        if (cur) {
          var cloc = rowOf(rows, cur);
          if (cloc && cloc.row !== pr && !cloc.row.allPop) {
            var gap = bandGap(pr, cloc.row);
            if (gap <= Math.max(40, (cloc.row.bottom - cloc.row.top) * 0.5)) target = cloc.row;
          }
        }
        if (!target) {
          var bestOv2 = -1;
          for (var q = 0; q < rows.length; q++) {
            var qr = rows[q];
            if (qr === pr || qr.allPop) continue;
            var ov2 = Math.min(pr.bottom, qr.bottom) - Math.max(pr.top, qr.top);
            if (ov2 >= (pr.bottom - pr.top) * 0.15 && ov2 > bestOv2) {
              bestOv2 = ov2; target = qr;
            }
          }
        }
        if (!target) continue;
        for (var x = 0; x < pr.members.length; x++) target.members.push(pr.members[x]);
        if (pr.top < target.top) target.top = pr.top;
        if (pr.bottom > target.bottom) target.bottom = pr.bottom;
        rows.splice(p, 1);
      }

      rows.sort(function (a, b) {
        return (a.top - b.top) || (a.members[0].r.left - b.members[0].r.left);
      });
      for (var f = 0; f < rows.length; f++) {
        rows[f].members.sort(function (a, b) { return a.r.left - b.r.left; });
      }
      return rows;
    }

    function bandGap(a, b) {
      return Math.max(a.top - b.bottom, b.top - a.bottom, 0);
    }

    function rowOf(rows, el) {
      for (var i = 0; i < rows.length; i++) {
        var ms = rows[i].members;
        for (var j = 0; j < ms.length; j++) {
          if (ms[j].el === el) return { row: rows[i], ri: i, idx: j };
        }
      }
      return null;
    }

    function pickByX(row, x) {
      var ms = row.members, best = null, bd = Infinity;
      for (var i = 0; i < ms.length; i++) {
        // P0 (QA G/D): salta i membri morti (preview smontata tra scan e
        // focus): nodo staccato o rect zero-size = anello invisibile.
        var elV = ms[i].el, rV = ms[i].r;
        if (!elV || !document.contains(elV) || rV.width < 4 || rV.height < 4) continue;
        var c = rV.left + rV.width / 2;
        var d = Math.abs(c - x);
        if (d < bd) { bd = d; best = ms[i]; }
      }
      return best || ms[0];
    }

    // ---- Scroll di messa a fuoco -------------------------------------------
    function clampN(v, lo, hi) { return v < lo ? lo : (v > hi ? hi : v); }
    function pageTop() {
      return window.scrollY || document.documentElement.scrollTop || 0;
    }
    function maxPage() {
      var doc = document.documentElement;
      var h = Math.max(doc ? doc.scrollHeight : 0,
                       document.body ? document.body.scrollHeight : 0);
      return Math.max(0, h - innerHeight);
    }
    // Primo antenato con overflow scorrevole sull'asse dato (mai html/body:
    // la pagina la gestisce il chiamante).
    function scrollerFor(el, axis) {
      var n = el.parentElement, hops = 0;
      while (n && hops < 24) {
        if (n === document.body || n === document.documentElement) break;
        var cs;
        try { cs = getComputedStyle(n); } catch (e) { break; }
        if (axis === 'x') {
          var ox = cs.overflowX;
          if ((ox === 'auto' || ox === 'scroll') && n.scrollWidth > n.clientWidth + 4) return n;
        } else {
          var oy = cs.overflowY;
          if ((oy === 'auto' || oy === 'scroll') && n.scrollHeight > n.clientHeight + 4) return n;
        }
        n = n.parentElement;
        hops++;
      }
      return null;
    }

    // Centratura su tre livelli: contenitore overflow-x della riga (SOLO
    // quello — la pagina non scrolla in orizzontale, mai), eventuale scroller
    // verticale interno (liste episodi), quindi la pagina in verticale.
    function scrollFix(el) {
      var r = el.getBoundingClientRect();

      var sx = scrollerFor(el, 'x');
      if (sx) {
        var cr = sx.getBoundingClientRect();
        var tx = sx.scrollLeft + (r.left + r.width / 2 - (cr.left + cr.width / 2));
        tx = clampN(tx, 0, sx.scrollWidth - sx.clientWidth);
        if (Math.abs(tx - sx.scrollLeft) > SNAP_X) {
          sx.scrollLeft = tx; // istantaneo: lo smooth su Mali-450 è lag
        }
      }

      try { el.scrollIntoView({ block: 'nearest', inline: 'nearest' }); } catch (e4) {}

      var sy = scrollerFor(el, 'y');
      if (sy) {
        var cr2 = sy.getBoundingClientRect();
        var ty = sy.scrollTop + (r.top + r.height / 2 - (cr2.top + cr2.height / 2));
        ty = clampN(ty, 0, sy.scrollHeight - sy.clientHeight);
        // B10: oldTop catturato PRIMA dell'assegnazione — leggerlo dopo era
        // inutile (sy.scrollTop valeva già ty ⇒ shift sempre 0) e il
        // centraggio pagina ragionava su rect stidenti.
        var oldTop = sy.scrollTop;
        if (Math.abs(ty - oldTop) > 4) {
          sy.scrollTop = ty;
        }
      }

      // B10: rect RILETTO dopo gli scroll interni — è l'unica posizione
      // veritiera per decidere il centraggio pagina (niente shift manuale).
      var r2 = el.getBoundingClientRect();
      if (r2.top < SNAP_Y || r2.bottom > innerHeight - SNAP_Y) {
        var pt = pageTop();
        var want = clampN(pt + (r2.top + r2.height / 2 - innerHeight / 2), 0, maxPage());
        if (Math.abs(want - pt) > 4) {
          window.scrollTo(0, want);
        }
      }
    }

    function pageScroll(sign) {
      // Scroll istantaneo: lo smooth su Mali-450 (stick 1 GB) è scatti e lag.
      window.scrollBy(0, sign * innerHeight * 0.66);
      navScrollStart();
      startFollow(700);
      // B3: lo scroll pagina cambia la finestra cull dei candidati — riscansiona
      // presto MA in background (il prossimo tasto non deve aspettare).
      rescanSoon(150);
    }

    // ---- Fuoco --------------------------------------------------------------
    // Mappa §1.5/§6.6: su /it/watch/ il frame principale non ha candidati —
    // il player è SOLO un iframe cross-origin. Niente scan né anello: move/
    // activate rispondono false pulito e il nativo governa i tasti in
    // cinema mode (latch B1). Zero costo: nemmeno un querySelector.
    function isWatchDoc() {
      return location.pathname.indexOf('/it/watch/') === 0;
    }
    function isFieldEl(el) {
      var t = el && el.tagName;
      return t === 'INPUT' || t === 'TEXTAREA' || t === 'SELECT';
    }
    function isTyping() {
      return !!cur && isFieldEl(cur) && document.activeElement === cur;
    }

    // B14: SINISTRA/DESTRA in un campo di testo muovono il caret
    // (selectionStart ± 1 con setSelectionRange). Guard: i type senza caret
    // manipolabile (number/email/date/time: setSelectionRange lancia
    // InvalidStateError) e i campi senza API selection (select) restano
    // come prima (false). Ai bordi il tasto è comunque consumato (true):
    // in isTyping le frecce appartengono al campo, mai all'anello.
    function caretMove(dir) {
      try {
        var t = (cur.type || '').toLowerCase();
        if (t === 'number' || t === 'email' || t === 'date' || t === 'time' ||
            t === 'month' || t === 'week' || t === 'datetime-local') return false;
        var s = cur.selectionStart, e = cur.selectionEnd;
        if (typeof s !== 'number' || typeof e !== 'number') return false;
        var len = (cur.value || '').length;
        if (dir === 'left') {
          if (s !== e) { cur.setSelectionRange(s, s); return true; } // collassa a inizio selezione
          if (s <= 0) return true;
          cur.setSelectionRange(s - 1, s - 1);
          return true;
        }
        if (s !== e) { cur.setSelectionRange(e, e); return true; } // collassa a fine selezione
        if (e >= len) return true;
        cur.setSelectionRange(e + 1, e + 1);
        return true;
      } catch (eCaret) {
        return false;
      }
    }

    // Mappa §3: la preview NON è CSS-hover. L'apertura è un listener Vue su
    // mouseenter DIRETTO di img.cover-image (rail normali) o .box-7x10 (top10)
    // → setTimeout(300ms) → POST → Teleport su <body> (.preview-wrap).
    // mouseenter non bubble: l'evento va dispatchato SUL target, non sulla
    // card. Restituisce i target hover della card (max 3: i cloni del rail
    // condividono l'href ma solo i primi contano per l'apertura).
    function hoverTargets(card) {
      var t = [];
      if (!card || !card.querySelectorAll) return t;
      try {
        if (card.matches('img.cover-image,.box-7x10')) t.push(card);
        var imgs = card.querySelectorAll('img.cover-image,.box-7x10');
        for (var ii = 0; ii < imgs.length && ii < 3; ii++) t.push(imgs[ii]);
      } catch (eH) { /* no-op */ }
      return t;
    }

    function setFocus(el) {
      // P0 (QA G/D): il nodo può MORIRE tra scan e focus (il sito smonta/
      // ri-teleporta la preview al cambio hover): fuoco su nodo staccato o
      // rect zero-size = anello invisibile e loc=null. Ri-ancora sul
      // candidato valido più vicino all'ultima posizione vista.
      if (el) {
        var elOk = false;
        try {
          var vr = el.getBoundingClientRect();
          elOk = document.contains(el) && vr.width > 3 && vr.height > 3;
        } catch (eV0) { elOk = false; }
        if (!elOk) {
          el = nearestTo(lastCenter ? lastCenter.x : innerWidth / 2,
                         lastCenter ? lastCenter.y : innerHeight / 2) || null;
        }
      }
      if (cur && cur !== el) {
        cur.classList.remove(RING);
        // mouseout solo se il nuovo fuoco NON è dentro il vecchio: muoversi
        // dalla card a un bottone del suo popup non deve chiudere il popup
        // (e quindi non deve far perdere il fuoco).
        if (el && !el.contains(cur)) {
          try { cur.dispatchEvent(new MouseEvent('mouseout', { bubbles: true })); } catch (e) {}
          // Mappa §3: mouseleave sintetico sulla card PRECEDENTE (e sui suoi
          // target hover) — chiude/annulla la preview prima del mouseenter
          // della nuova. Il sito ha un guard: se .preview-wrap è già in DOM
          // la nuova preview NON si apre; il mouseleave lascia il tempo alla
          // chiusura (~200ms) e cancella il timer del POST pendente.
          try { cur.dispatchEvent(new Event('mouseleave')); } catch (eL0) {}
          var olds = hoverTargets(cur);
          for (var oi = 0; oi < olds.length; oi++) {
            try { olds[oi].dispatchEvent(new Event('mouseleave')); } catch (eL1) {}
          }
        }
        if (isFieldEl(cur)) { try { cur.blur(); } catch (e2) {} }
      }
      cur = el || null;
      if (!cur) { hideFrame(); return; }
      // B5: un fuoco impostato da move()/utente è controllo esplicito —
      // annulla ogni ricentro SPA pendente (observer + timer di sicurezza).
      pendingCenter = false;
      clearTimeout(spaTimer);
      // B8: il fuoco è cambiato — la catena di verifica/click-fallback
      // dell'attivazione precedente non deve più scattare.
      actToken++;
      cur.classList.add(RING);
      navScrollStart(); // prima dello scroll: il guard deve lasciarlo passare
      scrollFix(cur);
      // B4: lastCenter DOPO lo scrollFix — l'elemento si è mosso rispetto al
      // viewport (centratura pagina/riga), il recupero da fuoco perso deve
      // partire dalla posizione VISTA, non da quella pre-scroll.
      var r0 = cur.getBoundingClientRect();
      lastCenter = { x: r0.left + r0.width / 2, y: r0.top + r0.height / 2 };
      try {
        cur.dispatchEvent(new MouseEvent('mouseover', { bubbles: true }));
        cur.dispatchEvent(new MouseEvent('mouseenter', { bubbles: true }));
      } catch (e3) {}
      // Mappa §3: mouseenter diretto sui target cover della card — è QUELLO
      // che fa partire il timer 300ms del POST preview. Se il fuoco si muove
      // di nuovo entro 300ms il mouseleave qui sopra cancella tutto (nessuno
      // spam di preview durante la navigazione rapida).
      var hts = hoverTargets(cur);
      for (var hi = 0; hi < hts.length; hi++) {
        try {
          hts[hi].dispatchEvent(new MouseEvent('mouseover', { bubbles: true }));
          hts[hi].dispatchEvent(new MouseEvent('mouseenter', { bubbles: true }));
        } catch (eH2) { /* no-op */ }
      }
      // B6a/mappa §3: popup GIÀ in DOM (mutazione ≤ 50ms) → rescan al tick;
      // altrimenti la preview arriva a ~300ms + POST: rescan a 450ms. Sempre
      // in background — il prossimo tasto non deve mai aspettare uno scan.
      rescanSoon(Date.now() - lastMutAt <= 50 ? 0 : 450);
      startFollow(900);
      clearTimeout(lateTimer);
      lateTimer = setTimeout(function () {
        if (cur) placeFrame(cur);
      }, 260);
    }

    function centerFocus() {
      if (cur) {
        cur.classList.remove(RING);
        try { cur.dispatchEvent(new MouseEvent('mouseout', { bubbles: true })); } catch (e) {}
        cur = null;
      }
      hideFrame();
      cache = null;
      var f = firstVisible();
      if (f) setFocus(f);
    }

    function firstVisible() {
      var cands = candidates();
      var cy = innerHeight / 2, cx = innerWidth / 2;
      var best = null, bd = Infinity;
      for (var i = 0; i < cands.length; i++) {
        var r = cands[i].getBoundingClientRect();
        if (r.width < 4 || r.height < 4) continue;
        var d = Math.abs(r.top + r.height / 2 - cy) +
                Math.abs(r.left + r.width / 2 - cx) * 0.3;
        if (d < bd) { bd = d; best = cands[i]; }
      }
      return best;
    }

    function nearestTo(x, y) {
      var cands = candidates();
      var best = null, bd = Infinity;
      for (var i = 0; i < cands.length; i++) {
        var r = cands[i].getBoundingClientRect();
        if (r.width < 4 || r.height < 4) continue;
        var d = Math.abs(r.top + r.height / 2 - y) +
                Math.abs(r.left + r.width / 2 - x) * 0.6;
        if (d < bd) { bd = d; best = cands[i]; }
      }
      return best;
    }

    // ---- Navigazione --------------------------------------------------------
    function move(dir) {
      // Zona morta watch (mappa §6.6): prima di ogni altra cosa, zero lavoro.
      if (isWatchDoc()) return false;
      // B14: campi di testo attivi. SINISTRA/DESTRA muovono il caret
      // (caretMove); SU/GIÙ escono dal campo (blur + true: il fuoco resta
      // sul campo col suo anello, il prossimo movimento naviga l'anello).
      if (isTyping()) {
        if (dir === 'left' || dir === 'right') return caretMove(dir);
        if (dir === 'up' || dir === 'down') {
          try { cur.blur(); } catch (eBlur) {}
          return true;
        }
      }

      var rows = buildRows(candidates());

      if (!rows.length) {
        if (dir === 'left') return 'edge';
        if (dir === 'up') {
          if (pageTop() > 60) { pageScroll(-1); return true; }
          return 'top';
        }
        if (dir === 'down') { pageScroll(1); return true; }
        return false;
      }

      var loc = cur ? rowOf(rows, cur) : null;
      if (!loc) {
        // Fuoco perso: nodo rimosso (popup chiuso, swap SPA) o fuori dalla
        // finestra live → riprendi dal candidato più vicino all'ultima
        // posizione dell'anello, senza mai restare "orfani".
        var ax = lastCenter ? lastCenter.x : innerWidth / 2;
        var ay = lastCenter ? lastCenter.y : innerHeight / 2;
        var near = nearestTo(ax, ay) || firstVisible();
        if (near) { setFocus(near); return true; }
        return false;
      }

      var row = loc.row, idx = loc.idx;
      var colX = row.members[idx].r.left + row.members[idx].r.width / 2;

      if (dir === 'left') {
        // P0: cammina ai membri VIVI (i morti si saltano, non bloccano).
        for (var jl = idx - 1; jl >= 0; jl--) {
          var mL = row.members[jl].el;
          if (mL && document.contains(mL) &&
              row.members[jl].r.width > 3 && row.members[jl].r.height > 3) {
            setFocus(mL); return true;
          }
        }
        // Primo elemento della riga: 'edge' solo dal bordo sinistro del
        // contenuto reale (padding dinamico, vedi pagePadX).
        return row.members[0].r.left <= pagePadX(rows) + 8 ? 'edge' : false;
      }
      if (dir === 'right') {
        for (var jr = idx + 1; jr < row.members.length; jr++) {
          var mR = row.members[jr].el;
          if (mR && document.contains(mR) &&
              row.members[jr].r.width > 3 && row.members[jr].r.height > 3) {
            setFocus(mR);
            return true;
          }
        }
        if (idx < row.members.length - 1) {
          setFocus(row.members[idx + 1].el);
          return true;
        }
        // B7: DESTRA a fine riga → prima card della riga successiva (con la
        // stessa guardia antisoscillazione del GIÙ); su ultima riga scorri
        // la pagina invece di morire sul bordo.
        var fyR = lastCenter ? lastCenter.y : 0;
        for (var rr2 = loc.ri + 1; rr2 < rows.length; rr2++) {
          if (rows[rr2].members.some(function (m) { return m.el === cur; })) continue;
          var cyR = 0;
          for (var mr2 = 0; mr2 < rows[rr2].members.length; mr2++) {
            cyR = Math.max(cyR, rows[rr2].members[mr2].r.top);
          }
          if (cyR < fyR - 4) continue; // riga interamente sopra il fuoco
          setFocus(rows[rr2].members[0].el);
          return true;
        }
        pageScroll(1);
        return true;
      }
      if (dir === 'down') {
        // La riga successiva: la prima SOPRA lo sfondo del fuoco che non
        // contenga il fuoco stesso (i cluster cambiano tra le scansioni —
        // senza questo, giù oscilla tra righe sovrapposte).
        var fy = lastCenter ? lastCenter.y : 0;
        for (var ri2 = loc.ri + 1; ri2 < rows.length; ri2++) {
          if (rows[ri2].members.some(function (m) { return m.el === cur; })) continue;
          var cy2 = 0;
          for (var mi2 = 0; mi2 < rows[ri2].members.length; mi2++) {
            cy2 = Math.max(cy2, rows[ri2].members[mi2].r.top);
          }
          if (cy2 < fy - 4) continue;
          setFocus(pickByX(rows[ri2], colX).el);
          return true;
        }
        // Ultima riga: scorri verso fine pagina.
        pageScroll(1);
        return true;
      }
      // up
      // B11: guardia antisoscillazione speculare a quella del GIÙ — salta le
      // righe che contengono il fuoco e le righe interamente SOTTO la linea
      // di fuoco (min-top > fy+4: ogni membro inizia sotto fy). I cluster
      // cambiano tra le scansioni: senza guardia, SU oscilla tra righe
      // sovrapposte come faceva GIÙ.
      var fyU = lastCenter ? lastCenter.y : Infinity;
      for (var ru = loc.ri - 1; ru >= 0; ru--) {
        if (rows[ru].members.some(function (m) { return m.el === cur; })) continue;
        var cyU = Infinity;
        for (var mu = 0; mu < rows[ru].members.length; mu++) {
          cyU = Math.min(cyU, rows[ru].members[mu].r.top);
        }
        if (cyU > fyU + 4) continue; // riga interamente sotto il fuoco
        setFocus(pickByX(rows[ru], colX).el);
        return true;
      }
      // Prima riga navigabile sopra: se c'è ancora pagina sopra scorri,
      // altrimenti 'top' (il nativo apre la sidebar).
      if (pageTop() > 60) { pageScroll(-1); return true; }
      return 'top';
    }

    // ---- B8: attivazione robusta ----------------------------------------------
    // Alcune card hanno il click agganciato a un ANTENATO senza semantica né
    // cursor:pointer (zona morta per OK): il click sul candidato non produce
    // alcun effetto. Sequenza dopo il click primario (real || cur):
    //   1. verifica a ~400ms e poi ~1300ms: successo se il pathname è
    //      cambiato, il body è in fade (playVideo anima document.body per
    //      750ms PRIMA di navigare), classe/style del target mutate, nuova
    //      .preview-wrap o nuovi nodi in `recent` (dropdown v-if, toggle
    //      watchlist, qualunque re-render Vue con nodi nuovi);
    //   2. nessun effetto ⇒ cammina su max 4 antenati e clicka il primo con
    //      rect più grande e handler plausibile (on* attribute, __vue__ o
    //      _vei su di sé o sul padre). MAI seguire gli href (bugiardi sulle
    //      righe episodi, mappa §7.4): solo dispatch di eventi, il navigate
    //      di default resta agli handler del sito;
    //   3. anche questo senza effetto ⇒ tap sintetico completo al CENTRO del
    //      target via elementFromPoint (colpisce l'elemento più profondo del
    //      punto — può essere il portatore reale dell'handler).
    // Ogni setFocus/reset/hide/nuovo activate() invalida la catena (token):
    // nessun click fantasma su elementi che l'utente ha già lasciato.
    var actToken = 0;

    function dispatchFullClick(t) {
      var cr = t.getBoundingClientRect();
      var opts = {
        bubbles: true, cancelable: true, view: window,
        clientX: cr.left + cr.width / 2,
        clientY: cr.top + cr.height / 2
      };
      try { t.dispatchEvent(new PointerEvent('pointerdown', opts)); } catch (e1) {}
      try { t.dispatchEvent(new MouseEvent('mousedown', opts)); } catch (e2) {}
      try { t.dispatchEvent(new PointerEvent('pointerup', opts)); } catch (e3) {}
      try { t.dispatchEvent(new MouseEvent('mouseup', opts)); } catch (e4) {}
      try { t.click(); } catch (e5) {}
    }

    // Handler "plausibile" su un nodo: attributi on*, istanza Vue 2
    // (__vue__) o registro dei listener Vue 3 (_vei) su di sé o sul padre.
    function plausibleHandler(el) {
      try {
        if (!el || el.nodeType !== 1) return false;
        var at = el.attributes;
        for (var i = 0; i < at.length; i++) {
          if (/^on[a-z]+$/i.test(at[i].name || '')) return true;
        }
        if (el.__vue__) return true;
        if (el._vei) { for (var k in el._vei) return true; }
        var p = el.parentElement;
        if (p) {
          if (p.__vue__) return true;
          if (p._vei) { for (var k2 in p._vei) return true; }
        }
      } catch (eH) { /* no-op */ }
      return false;
    }

    // Primo antenato (max 4 salti) con rect più grande e handler plausibile.
    // nav.tab-nav escluso: è la root Vue di TabNav e porta il listener
    // onTabswitch — cliccarla emette tab "undefined" e nasconde tutti i
    // contenuti (mappa §7.2). Esclusi anche i nodi blacklisted/animati.
    function findPlausibleAncestor(tgt) {
      var tr = tgt.getBoundingClientRect();
      var ta = tr.width * tr.height;
      var n = tgt.parentElement;
      for (var hops = 0; n && hops < 4; hops++) {
        if (n === document.body || n === document.documentElement) break;
        var isTabNav = false;
        try { isTabNav = !!(n.matches && n.matches('nav.tab-nav')); } catch (eM) {}
        if (!isTabNav && !blacklisted(n) && !inAnimating(n) && plausibleHandler(n)) {
          var nr = n.getBoundingClientRect();
          if (nr.width >= tr.width && nr.height >= tr.height &&
              nr.width * nr.height > ta) return n;
        }
        n = n.parentElement;
      }
      return null;
    }

    // Ultimo tentativo: tap sintetico COMPLETO al centro del target.
    // elementFromPoint restituisce l'elemento più profondo al centro — può
    // essere un discendente (img dentro l'anchor): il click bubbla alla
    // catena giusta anche quando il target "logico" non riceve gli eventi.
    function syntheticCenterTap(tgt) {
      var hit = tgt;
      try {
        var r = tgt.getBoundingClientRect();
        hit = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2) || tgt;
      } catch (eP) { /* no-op */ }
      dispatchFullClick(hit);
    }

    function newRecentSince(ts) {
      var found = false;
      recent.forEach(function (t) { if (t >= ts) found = true; });
      return found;
    }

    function bodyStyleAttr() {
      try { return document.body.getAttribute('style') || ''; } catch (eB) { return ''; }
    }

    // Qualunque effetto visibile dell'attivazione (vedi sequenza B8 sopra).
    function activationHasEffect(sig, tgt) {
      if (location.pathname !== sig.p) return true;
      if (bodyStyleAttr() !== sig.bs) return true;
      try {
        var cls = (typeof tgt.className === 'string') ? tgt.className : '';
        if (cls !== sig.cls) return true;
        if ((tgt.getAttribute('style') || '') !== sig.sty) return true;
      } catch (eC) { /* no-op */ }
      var pv = false;
      try { pv = !!document.querySelector('.preview-wrap'); } catch (eP2) {}
      if (pv !== sig.pv) return true;
      return newRecentSince(sig.at);
    }

    // Captura classe/style correnti del target di questa fase: la verifica
    // confronta lo stato PRE-click del nodo cliccato in quella fase (tgt al
    // primo giro, l'antenato al secondo).
    function beginStageSig(sig, el) {
      try { sig.cls = (typeof el.className === 'string') ? el.className : ''; } catch (eS1) {}
      try { sig.sty = el.getAttribute('style') || ''; } catch (eS2) {}
    }

    // Doppio controllo (400ms poi +1300ms): il secondo copre le nav SPA
    // lente (fetch del component Inertia) prima di dichiarare il click
    // "morto" — senza di lui una card lenta ma funzionante prenderrebbe il
    // fallback e navigherebbe due volte.
    function verifyActivation(token, sig, tgt, stage) {
      if (token !== actToken) return;
      setTimeout(function () {
        if (token !== actToken) return;
        if (activationHasEffect(sig, tgt)) return;
        setTimeout(function () {
          if (token !== actToken) return;
          if (activationHasEffect(sig, tgt)) return;
          if (stage === 0) {
            var anc = findPlausibleAncestor(tgt);
            if (anc) {
              beginStageSig(sig, anc);
              dispatchFullClick(anc);
              verifyActivation(token, sig, anc, 1);
            } else {
              syntheticCenterTap(tgt);
            }
          } else {
            syntheticCenterTap(tgt);
          }
        }, 1300);
      }, 400);
    }

    function activate() {
      if (isWatchDoc()) return;
      if (!cur) {
        var f = firstVisible();
        if (f) setFocus(f);
        return;
      }
      if (isFieldEl(cur)) {
        // focus() e basta: la tastiera/digitazione è gestita dal nativo
        // (che può interrogare __sctvNav.isTyping()).
        try { cur.focus(); } catch (e) {}
        return;
      }
      // Mappa §5.5: rail in animazione — il click sarebbe perso
      // (pointer-events:none per 750ms).
      if (inAnimating(cur)) return;
      // Span/div "decorativi" con cursor:pointer dentro un anchor/bottone:
      // il click reale sta sull'antenato attivabile.
      var real = cur.closest ? cur.closest('a[href],button,[role="button"]') : null;
      var tgt = real || cur;
      // Mappa §5.2: click REALE — sequenza completa pointer/mouse + click
      // (dispatchFullClick). Gli href NON vengono mai seguiti direttamente
      // (bugiardi sulle righe episodi): il navigate di default resta agli
      // handler del sito.
      var myToken = ++actToken;
      var sig = {
        p: location.pathname,
        bs: bodyStyleAttr(),
        cls: '', sty: '', pv: false,
        at: Date.now()
      };
      beginStageSig(sig, tgt);
      try { sig.pv = !!document.querySelector('.preview-wrap'); } catch (eS3) {}
      dispatchFullClick(tgt);
      verifyActivation(myToken, sig, tgt, 0);
    }

    // Reset esplicito (documentato nell'header del modulo): azzera fuoco e
    // cache e ricentra sul candidato più vicino al centro viewport.
    function reset() {
      pendingCenter = false;
      spaAnchor = false;
      clearTimeout(spaTimer);
      actToken++; // B8: nessuna catena di attivazione attraverso il reset
      centerFocus();
    }

    // B15: ancora del fuoco attraverso la navigazione SPA. Al pushState
    // (chiamata spaReset senza argomenti, subito dopo il push: history.length
    // è già quella dello stack che contiene la pagina di partenza) lastCenter
    // viene salvato in sessionStorage; al popstate (spaReset(true)) il fuoco
    // riprende dal candidato più vicino all'ancora invece del centro — il
    // back torna alla card di partenza, non a una generica al centro.
    // history.length è stabile fra push e back (conta anche le entry forward),
    // quindi la stessa chiave identifica la pagina di ritorno. try/catch:
    // sessionStorage può mancare o essere a quota piena.
    function saveAnchor() {
      try {
        if (!lastCenter) return;
        sessionStorage.setItem('sctv-anc-' + history.length,
          JSON.stringify({ x: lastCenter.x, y: lastCenter.y }));
      } catch (e) { /* no-op */ }
    }
    function readAnchor() {
      try {
        var s = sessionStorage.getItem('sctv-anc-' + history.length);
        if (!s) return null;
        var a = JSON.parse(s);
        return (a && typeof a.x === 'number' && typeof a.y === 'number') ? a : null;
      } catch (e) { return null; }
    }

    function doSpaFocus() {
      var anchor = spaAnchor ? readAnchor() : null;
      spaAnchor = false;
      if (anchor) {
        if (cur) {
          cur.classList.remove(RING);
          try { cur.dispatchEvent(new MouseEvent('mouseout', { bubbles: true })); } catch (e) {}
          cur = null;
        }
        hideFrame();
        cache = null;
        var f = nearestTo(anchor.x, anchor.y) || firstVisible();
        if (f) setFocus(f);
        return;
      }
      centerFocus();
    }

    // Navigazione SPA (Inertia pushState/popstate — richiamata dagli hook
    // history in fondo al file): il DOM cambierà a breve. L'observer consuma
    // pendingCenter alla quiete delle mutazioni; questo timer è la rete di
    // sicurezza se il swap tarda. replaceState NON chiama spaReset (B5).
    function spaReset(useAnchor) {
      if (!useAnchor) saveAnchor();
      pendingCenter = true;
      spaAnchor = !!useAnchor;
      lastMutAt = Date.now();
      clearTimeout(spaTimer);
      spaTimer = setTimeout(function () {
        if (!pendingCenter) return;
        pendingCenter = false;
        doSpaFocus();
      }, 1200);
    }

    function hide() {
      if (cur) { cur.classList.remove(RING); cur = null; }
      hideFrame();
      actToken++; // B8: nessuna catena di attivazione a modulo nascosto
    }

    // Drena la coda dei comandi D-pad arrivati prima dell'iniezione
    // (document-start stub installato da MainActivity).
    function drainQueue() {
      var q = window.__sctvQueue;
      if (!q || !q.length) return;
      window.__sctvQueue = [];
      // B13: drena la sequenza in ordine — ogni move già lascia il fuoco
      // al posto giusto. Il vecchio setFocus(first) finale RI-portava
      // l'anello al primo tasto della coda, annullando l'intera sequenza.
      for (var i = 0; i < q.length; i++) {
        var cmd = q[i];
        if (cmd[0] === 'move') {
          move(cmd[1]);
        } else if (cmd[0] === 'activate') {
          activate();
        }
      }
    }
    drainQueue();

    // Diagnostica (esposta per i test CDP): stato corrente del navigatore.
    function debug() {
      var cands = candidates();
      var rows = buildRows(cands);
      var loc = cur ? rowOf(rows, cur) : null;
      return {
        cur: cur ? cur.tagName + ' "' + (cur.textContent || '').trim().slice(0, 30) + '"' : null,
        curRect: cur ? (function () { var r = cur.getBoundingClientRect(); return [Math.round(r.left), Math.round(r.top), Math.round(r.width), Math.round(r.height)]; })() : null,
        cand: cands.length,
        rows: rows.length,
        loc: loc ? { ri: loc.ri, idx: loc.idx } : null,
        url: location.href.slice(0, 50)
      };
    }

    window.__sctvNav = {
      move: move,
      activate: activate,
      debug: debug,
      reset: reset,
      spaReset: spaReset,
      isTyping: isTyping,
      hide: hide,
      __real: true
    };
  })();

  // First load: report quickly, then again in case the router settled late.
  setTimeout(reportPageInfo, 500);
  setTimeout(reportPageInfo, 2500);
  // Trailer hero: l'iframe può essere montato dall'hydration o dal timer
  // "show-after" del sito dopo l'iniezione — doppio passaggio garantito
  // (il MutationObserver sopra copre tutte le montature successive).
  setTimeout(tameHeroTrailerIframes, 600);
  setTimeout(tameHeroTrailerIframes, 2000);

  // SPA navigation: Inertia drives history.pushState/replaceState — hook
  // them (plus popstate) and re-report after the new page settles. The same
  // hooks reset the D-pad ring to the center-most candidate (section 9).
  // B5: replaceState NON è navigazione (Inertia lo usa anche per querystring
  // e stato di scroll senza cambiare pagina): solo pushState/popstate fanno
  // spaReset, altrimenti l'anello si ricentra a ogni aggiornamento di stato.
  var navReport = function () {
    setTimeout(reportPageInfo, 700);
  };
  try {
    ['pushState', 'replaceState'].forEach(function (name) {
      var orig = history[name].bind(history);
      history[name] = function () {
        var r = orig.apply(null, arguments);
        navReport();
        if (name === 'pushState' && window.__sctvNav) {
          try { window.__sctvNav.spaReset(); } catch (e) { /* no-op */ }
        }
        return r;
      };
    });
  } catch (e) { /* no-op */ }
  window.addEventListener('popstate', function () {
    setTimeout(reportPageInfo, 700);
    // B15: back/forward → ripristino del fuoco dall'ancora salvata al push.
    try { if (window.__sctvNav) window.__sctvNav.spaReset(true); } catch (e) { /* no-op */ }
  });

  // Re-run the cosmetic hide on DOM mutations (the site is a SPA), debounced.
  // hideAds() no longer mutates the DOM on no-op calls (see above), so the
  // observer cannot feed back into itself. Nella stessa finestra va rilanciata
  // la rimozione degli iframe trailer vixcloud: l'hero li può ri-montare a
  // ogni swap/re-render (idempotente).
  var hidePending = false;
  var observer = new MutationObserver(function () {
    if (hidePending) return;
    hidePending = true;
    setTimeout(function () {
      hidePending = false;
      hideAds();
      tameHeroTrailerIframes();
    }, 250);
  });
  try {
    observer.observe(document.documentElement, { childList: true, subtree: true });
  } catch (e) { /* no-op */ }

  // ---- 5. Scroll guard -------------------------------------------------------
  // The site's framework (Laravel Inertia) resets the scroll to the top after
  // background visits / state refreshes (vendor code: window.scrollTo(0,0)
  // plus scrollTop=0 on every scroll region). On Fire TV this fights the
  // user's edge-scrolling: the page "snaps back up" while browsing.
  //
  // Guard: remember when the user last scrolled; swallow SCRIPT-driven
  // scrolls that target the TOP of the page during that window. Scrolls to
  // any other position, and any scroll once the user has been idle, pass
  // through untouched — so real navigation ("go to top of new page") keeps
  // working.
  function installScrollGuard() {
    var lastUserScroll = 0;

    // Any real scroll (native edge-scroll, fling, touch) fires a scroll
    // event — that is the user's signal.
    document.addEventListener('scroll', function () {
      lastUserScroll = Date.now();
    }, true);

    function pageTop() {
      return window.scrollY || document.documentElement.scrollTop || document.body.scrollTop || 0;
    }

    function shouldBlock(top) {
      // Scroll generati dalla navigazione D-pad (centratura dell'anello,
      // flag della sezione 9): passano sempre, anche se finiscono in cima
      // pagina durante una sequenza veloce di movimenti.
      if (window.__scvNavScrolling) return false;
      return top !== undefined && top <= 1 &&
             pageTop() > 200 &&
             (Date.now() - lastUserScroll) < 1000;
    }

    function topOf(args) {
      var a = args[0];
      if (a && typeof a === 'object') return a.top;
      return args[1];
    }

    // window.scrollTo / window.scroll (Inertia's primary reset path)
    ['scrollTo', 'scroll'].forEach(function (name) {
      var orig = window[name] && window[name].bind(window);
      if (!orig) return;
      window[name] = function () {
        try { if (shouldBlock(topOf(arguments))) return; } catch (e) { /* no-op */ }
        return orig.apply(window, arguments);
      };
    });

    // Element.prototype.scrollTo on the document scroller (Inertia's
    // scroll-region fallback) — only guarded on the page scroller itself so
    // carousels and inner lists are untouched.
    try {
      var ep = Element.prototype, origElScrollTo = ep.scrollTo;
      if (origElScrollTo) {
        ep.scrollTo = function () {
          try {
            if (this === document.documentElement || this === document.body ||
                this === document.scrollingElement) {
              if (shouldBlock(topOf(arguments))) return;
            }
          } catch (e) { /* no-op */ }
          return origElScrollTo.apply(this, arguments);
        };
      }
      var se = document.scrollingElement || document.documentElement;
      var d = Object.getOwnPropertyDescriptor(Element.prototype, 'scrollTop');
      if (se && d && d.set) {
        Object.defineProperty(se, 'scrollTop', {
          get: function () { return d.get.call(this); },
          set: function (v) {
            try { if (shouldBlock(v)) return; } catch (e) { /* no-op */ }
            d.set.call(this, v);
          },
          configurable: true
        });
      }
    } catch (e) { /* no-op */ }
  }
})();
