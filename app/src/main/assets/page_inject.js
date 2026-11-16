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
  //                           ATTIVO — in quello stato SINISTRA/DESTRA non
  //                           muovono l'anello (servono al cursore testo);
  //                           il nativo può usarlo per inoltrare le frecce
  //                           all'input o aprire la tastiera.
  //   __sctvNav.spaReset()  : interno — hook pushState/popState di Inertia
  //                           (sezione 10): ricentra il fuoco dopo ogni
  //                           navigazione SPA.
  (function () {
    if (window.__sctvNav) return;

    // ---- Costanti di design -------------------------------------------------
    // Clustering: sovrapposizione verticale richiesta perché un elemento
    // entri in una riga — 40% della sua altezza (spec), con pavimento di 8px
    // così il rumore di layout non incolla righe di testo vicine.
    var ROW_OVERLAP_RATIO = 0.4;
    var ROW_OVERLAP_MIN_PX = 8;
    // Bordo sinistro assoluto del contenuto: SINISTRA qui ritorna 'edge'.
    // Le righe partono con un padding pagina ~2-4%: 48px copre il padding
    // senza inglobare card indentate.
    var EDGE_X = 48;
    // Culling dei candidati: finestra attorno al viewport molto più larga
    // del viewport (le righe sopra/sotto restano navigabili e la messa a
    // fuoco le centra), abbastanza stretta da tenere la scansione < 100ms.
    var CULL_Y_FACTOR = 1.2, CULL_X_FACTOR = 0.5, CULL_MIN = 160;
    var MIN_W = 24, MIN_H = 12;
    // Margini oltre i quali la messa a fuoco centra l'elemento.
    var SNAP_Y = 70, SNAP_X = 8;

    var RING = 'sctv-dpad-focus';
    var st = document.createElement('style');
    st.textContent = '.' + RING + '{outline:none!important;filter:brightness(1.25)}' +
      // Login e ricerca nell'header del sito: inutili nell'app (la ricerca
      // è nella sidebar nativa) — rimossi dalla home e da tutte le pagine.
      '.nav-item.btn-login,.nav-item.search-bar-item{display:none!important}';
    document.head.appendChild(st);

    // ---- Anello -------------------------------------------------------------
    // Il ring è un frame position:fixed sopra TUTTO (z-index massimo): le
    // card vicine del sito non possono coprirlo. (Nella versione precedente
    // il frame non veniva mai appeso al DOM: l'anello visibile era solo il
    // filtro brightness della classe.)
    var frame = document.createElement('div');
    frame.style.cssText = 'position:fixed;pointer-events:none;display:none;' +
      'z-index:2147483000;border:4px solid #FFFFFF;border-radius:14px;' +
      'box-shadow:0 0 0 7px rgba(229,9,20,.5),0 22px 60px rgba(0,0,0,.92);' +
      'transition:left .14s ease,top .14s ease,width .14s ease,height .14s ease';
    (document.body || document.documentElement).appendChild(frame);
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

    window.addEventListener('scroll', function () {
      if (cur) placeFrame(cur);
    }, true);
    window.addEventListener('resize', function () {
      if (cur) placeFrame(cur);
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
    var cache = null, cacheTimer = null;
    var pendingCenter = false; // ricentro dopo SPA nav (consumato dall'observer)
    var lastMutAt = 0;

    // Subtree aggiunti di recente al DOM = popup hover aperti (vedi merge
    // righe-p popup in buildRows). Mappa nodo → timestamp.
    var recent = new Map();
    var RECENT_TTL = 4000;
    function popupish(el) {
      var n = el, hops = 0;
      while (n && hops < 6) {
        if (recent.has(n)) return true;
        if (n === document.body) break;
        n = n.parentElement;
        hops++;
      }
      return false;
    }

    // Il sito è una SPA: ricalcola i candidati solo quando il DOM cambia
    // (debounce 120 ms), non a ogni movimento del D-pad.
    function invalidate() {
      cache = null;
    }
    // I trailer in autoplay nell'hero del sito: la loro superficie video
    // (fork Fire TV del WebView) copre la sidebar nativa e mangia CPU su
    // uno stick da 1 GB — pausa e nasconde i video del frame principale.
    // Il player watch è in un iframe separato: non viene toccato.
    function tameVideos() {
      var vs = document.querySelectorAll('video');
      for (var i = 0; i < vs.length; i++) {
        var v = vs[i];
        if (v.dataset.sctvTamed) continue;
        v.dataset.sctvTamed = '1';
        try {
          v.pause();
          v.muted = true;
          v.style.display = 'none';
        } catch (e) {}
      }
    }

    var obs = new MutationObserver(function (muts) {
      lastMutAt = Date.now();
      for (var i = 0; i < muts.length; i++) {
        var added = muts[i].addedNodes;
        for (var j = 0; j < added.length; j++) {
          var n = added[j];
          if (n.nodeType === 1) recent.set(n, lastMutAt);
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
        invalidate();
        tameVideos();
        // Fuoco da ricentrire dopo una navigazione SPA: attendi la quiete
        // delle mutazioni (il swap di Inertia arriva a batch), poi ricentra.
        if (pendingCenter && Date.now() - lastMutAt > 250) {
          pendingCenter = false;
          clearTimeout(spaTimer);
          centerFocus();
        }
      }, 120);
    });
    function startObserver() {
      if (document.body && !obs._started) {
        obs.observe(document.body, { childList: true, subtree: true, attributes: true });
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
      function add(e, rr) { e.__sctvRect = rr; seen.add(e); out.push(e); }

      // 1. semantica: link, bottoni, campi, tabindex
      var nodes = document.querySelectorAll(
        'a[href],button,[role="button"],input,select,textarea,[tabindex]:not([tabindex="-1"])');
      for (i = 0; i < nodes.length; i++) {
        el = nodes[i];
        if (el.disabled || (el.getAttribute && el.getAttribute('aria-disabled') === 'true')) continue;
        r = el.getBoundingClientRect();
        if (!ok(r)) continue;
        cs = getComputedStyle(el);
        if (cs.visibility === 'hidden' || cs.display === 'none') continue;
        add(el, r);
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
        add(el, r);
      }

      // 3. dedup per contenimento: fra elementi sovrapposti tiene il più
      //    interno (il target reale del click), scarta il contenitore.
      out.sort(function (a, b) {
        return a.__sctvRect.width * a.__sctvRect.height -
               b.__sctvRect.width * b.__sctvRect.height;
      });
      var kept = [];
      for (i = 0; i < out.length; i++) {
        var rr = out[i].__sctvRect;
        var inside = false;
        for (var m = 0; m < kept.length; m++) {
          var kr = kept[m].__sctvRect;
          if (rr.left >= kr.left - 2 && rr.right <= kr.right + 2 &&
              rr.top >= kr.top - 2 && rr.bottom <= kr.bottom + 2) { inside = true; break; }
        }
        if (!inside) kept.push(out[i]);
      }
      for (i = 0; i < out.length; i++) delete out[i].__sctvRect;
      return kept;
    }

    function candidates() {
      if (cache) return cache;
      cache = scan();
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
      for (var i = 0; i < cands.length; i++) {
        var el = cands[i];
        var r = el.getBoundingClientRect();
        if (r.width < 4 || r.height < 4) continue;               // sparito
        if (r.bottom < -cullY || r.top > vh + cullY ||
            r.right < -cullX || r.left > vw + cullX) continue;   // fuori finestra live
        items.push({ el: el, r: r, pop: popupish(el) });
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
      var ms = row.members, best = ms[0], bd = Infinity;
      for (var i = 0; i < ms.length; i++) {
        var c = ms[i].r.left + ms[i].r.width / 2;
        var d = Math.abs(c - x);
        if (d < bd) { bd = d; best = ms[i]; }
      }
      return best;
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
          try { sx.scrollTo({ left: tx, behavior: 'smooth' }); }
          catch (e) { sx.scrollLeft = tx; }
        }
      }

      var shift = 0;
      var sy = scrollerFor(el, 'y');
      if (sy) {
        var cr2 = sy.getBoundingClientRect();
        var ty = sy.scrollTop + (r.top + r.height / 2 - (cr2.top + cr2.height / 2));
        ty = clampN(ty, 0, sy.scrollHeight - sy.clientHeight);
        if (Math.abs(ty - sy.scrollTop) > 4) {
          try { sy.scrollTo({ top: ty, behavior: 'smooth' }); }
          catch (e2) { sy.scrollTop = ty; }
          shift = ty - sy.scrollTop;
        }
      }

      var vTop = r.top - shift, vBot = r.bottom - shift;
      if (vTop < SNAP_Y || vBot > innerHeight - SNAP_Y) {
        var pt = pageTop();
        var want = clampN(pt + (vTop + r.height / 2 - innerHeight / 2), 0, maxPage());
        if (Math.abs(want - pt) > 4) {
          try { window.scrollTo({ top: want, behavior: 'smooth' }); }
          catch (e3) { window.scrollTo(0, want); }
        }
      }
    }

    function pageScroll(sign) {
      invalidate(); // lo scroll cambia la finestra dei candidati
      try {
        window.scrollBy({ top: sign * innerHeight * 0.66, behavior: 'smooth' });
      } catch (e) {
        window.scrollBy(0, sign * innerHeight * 0.66);
      }
      navScrollStart();
      startFollow(900);
    }

    // ---- Fuoco --------------------------------------------------------------
    function isFieldEl(el) {
      var t = el && el.tagName;
      return t === 'INPUT' || t === 'TEXTAREA' || t === 'SELECT';
    }
    function isTyping() {
      return !!cur && isFieldEl(cur) && document.activeElement === cur;
    }

    function setFocus(el) {
      if (cur && cur !== el) {
        cur.classList.remove(RING);
        // mouseout solo se il nuovo fuoco NON è dentro il vecchio: muoversi
        // dalla card a un bottone del suo popup non deve chiudere il popup
        // (e quindi non deve far perdere il fuoco).
        if (el && !el.contains(cur)) {
          try { cur.dispatchEvent(new MouseEvent('mouseout', { bubbles: true })); } catch (e) {}
        }
        if (isFieldEl(cur)) { try { cur.blur(); } catch (e2) {} }
      }
      cur = el || null;
      if (!cur) { hideFrame(); return; }
      var r0 = cur.getBoundingClientRect();
      lastCenter = { x: r0.left + r0.width / 2, y: r0.top + r0.height / 2 };
      cur.classList.add(RING);
      navScrollStart(); // prima dello scroll: il guard deve lasciarlo passare
      scrollFix(cur);
      try {
        cur.dispatchEvent(new MouseEvent('mouseover', { bubbles: true }));
        cur.dispatchEvent(new MouseEvent('mouseenter', { bubbles: true }));
      } catch (e3) {}
      // il popup hover del sito entra nel DOM: invalida e riscansiona presto
      invalidate();
      startFollow(900);
      clearTimeout(lateTimer);
      lateTimer = setTimeout(function () {
        invalidate();
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
      // Campi di testo attivi: SINISTRA/DESTRA appartengono al cursore testo,
      // non all'anello (SU/GIÙ escono dal campo normalmente).
      if (isTyping() && (dir === 'left' || dir === 'right')) return false;

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
        if (idx > 0) { setFocus(row.members[idx - 1].el); return true; }
        // Primo elemento della riga: 'edge' solo dal bordo sinistro assoluto.
        return row.members[0].r.left <= EDGE_X ? 'edge' : false;
      }
      if (dir === 'right') {
        if (idx < row.members.length - 1) {
          setFocus(row.members[idx + 1].el);
          return true;
        }
        return false;
      }
      if (dir === 'down') {
        if (loc.ri < rows.length - 1) {
          setFocus(pickByX(rows[loc.ri + 1], colX).el);
          return true;
        }
        // Ultima riga: scorri verso fine pagina.
        pageScroll(1);
        return true;
      }
      // up
      if (loc.ri > 0) {
        setFocus(pickByX(rows[loc.ri - 1], colX).el);
        return true;
      }
      // Prima riga: se c'è ancora pagina sopra scorri, altrimenti 'top'.
      if (pageTop() > 60) { pageScroll(-1); return true; }
      return 'top';
    }

    function activate() {
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
      try { cur.click(); } catch (e2) {}
    }

    // Reset esplicito (documentato nell'header del modulo): azzera fuoco e
    // cache e ricentra sul candidato più vicino al centro viewport.
    function reset() {
      pendingCenter = false;
      clearTimeout(spaTimer);
      centerFocus();
    }

    // Navigazione SPA (Inertia pushState/replaceState/popstate — richiamata
    // dagli hook history in fondo al file): il DOM cambierà a breve.
    // L'observer consuma pendingCenter alla quiete delle mutazioni; questo
    // timer è la rete di sicurezza se il swap tarda.
    function spaReset() {
      pendingCenter = true;
      lastMutAt = Date.now();
      clearTimeout(spaTimer);
      spaTimer = setTimeout(function () {
        if (!pendingCenter) return;
        pendingCenter = false;
        centerFocus();
      }, 1200);
    }

    window.__sctvNav = {
      move: move,
      activate: activate,
      reset: reset,
      spaReset: spaReset,
      isTyping: isTyping
    };
  })();

  // First load: report quickly, then again in case the router settled late.
  setTimeout(reportPageInfo, 500);
  setTimeout(reportPageInfo, 2500);

  // SPA navigation: Inertia drives history.pushState/replaceState — hook
  // them (plus popstate) and re-report after the new page settles. The same
  // hooks reset the D-pad ring to the center-most candidate (section 9).
  var navReport = function () {
    setTimeout(reportPageInfo, 700);
    try { if (window.__sctvNav) window.__sctvNav.spaReset(); } catch (e) { /* no-op */ }
  };
  try {
    ['pushState', 'replaceState'].forEach(function (name) {
      var orig = history[name].bind(history);
      history[name] = function () {
        var r = orig.apply(null, arguments);
        navReport();
        return r;
      };
    });
  } catch (e) { /* no-op */ }
  window.addEventListener('popstate', function () {
    setTimeout(reportPageInfo, 700);
    try { if (window.__sctvNav) window.__sctvNav.spaReset(); } catch (e) { /* no-op */ }
  });

  // Re-run the cosmetic hide on DOM mutations (the site is a SPA), debounced.
  // hideAds() no longer mutates the DOM on no-op calls (see above), so the
  // observer cannot feed back into itself.
  var hidePending = false;
  var observer = new MutationObserver(function () {
    if (hidePending) return;
    hidePending = true;
    setTimeout(function () {
      hidePending = false;
      hideAds();
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
