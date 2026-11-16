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
 * NOTE: D-pad navigation is handled NATIVELY by CursorController
 * (virtual mouse cursor overlay). No key handling happens here anymore —
 * injecting key listeners would only risk fighting the native cursor.
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

  // ---- 9. Navigazione D-pad spaziale (sito senza mouse) ---------------------
  // Mappa i candidati focalizzabili del sito (link, card, bottoni, popup
  // hover) e muove un anello di selezione con l'algoritmo spaziale. Il
  // fuoco simula anche il mouseover per aprire le card popup del sito, le
  // cui opzioni diventano candidati a loro volta.
  (function () {
    if (window.__sctvNav) return;
    var RING = 'sctv-dpad-focus';
    var st = document.createElement('style');
    st.textContent = '.' + RING + '{outline:none!important;filter:brightness(1.25)}' +
      // Login e ricerca nell'header del sito: inutili nell'app (la ricerca
      // è nella sidebar nativa) — rimossi dalla home e da tutte le pagine.
      '.nav-item.btn-login,.nav-item.search-bar-item{display:none!important}';
    document.head.appendChild(st);

    // Il ring è un frame position:fixed sopra TUTTO (z-index massimo):
    // le card vicine del sito non possono coprirlo come accadeva col
    // box-shadow sull'elemento.
    var frame = document.createElement('div');
    frame.style.cssText = 'position:fixed;pointer-events:none;display:none;' +
      'z-index:2147483000;border:4px solid #FFFFFF;border-radius:14px;' +
      'box-shadow:0 0 0 7px rgba(229,9,20,.5),0 22px 60px rgba(0,0,0,.92);' +
      'transition:left .14s ease,top .14s ease,width .14s ease,height .14s ease';
    var framePad = 7;
    function placeFrame(el) {
      var r = el.getBoundingClientRect();
      frame.style.display = 'block';
      frame.style.left = (r.left - framePad) + 'px';
      frame.style.top = (r.top - framePad) + 'px';
      frame.style.width = (r.width + framePad * 2) + 'px';
      frame.style.height = (r.height + framePad * 2) + 'px';
    }
    function hideFrame() { frame.style.display = 'none'; }
    window.addEventListener('scroll', function () {
      if (cur) placeFrame(cur);
    }, true);
    window.addEventListener('resize', function () {
      if (cur) placeFrame(cur);
    });

    var cur = null;
    var cache = null, cacheTimer = null;

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

    var obs = new MutationObserver(function () {
      if (cacheTimer) return;
      cacheTimer = setTimeout(function () { cacheTimer = null; invalidate(); tameVideos(); }, 120);
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

    function candidates() {
      if (cache) return cache;
      var out = [];
      var kept = [];

      function visible(el) {
        if (!el.getClientRects().length) return false;
        var r = el.getBoundingClientRect();
        if (r.width < 24 || r.height < 12) return false;
        if (r.bottom < -60 || r.top > innerHeight + 60 ||
            r.right < -60 || r.left > innerWidth + 60) return false;
        return true;
      }

      function add(el) {
        if (seen.has(el)) return;
        var r = el.getBoundingClientRect();
        el.__r = r;
        seen.add(el);
        out.push(el);
      }

      var seen = new Set();
      // 1. semantica: link, bottoni, campi
      var nodes = document.querySelectorAll(
        'a[href],button,[role="button"],input,select,textarea,[tabindex]:not([tabindex="-1"])');
      for (var i = 0; i < nodes.length; i++) {
        var el = nodes[i];
        if (visible(el)) add(el);
      }
      // 2. euristica Vue: QUALSIASI elemento cliccabile senza semantica
      //    è riconoscibile dal cursore pointer (card, voci, tab, chip).
      var all = document.querySelectorAll('body *');
      for (var j = 0; j < all.length; j++) {
        var el2 = all[j];
        if (seen.has(el2)) continue;
        var cs = getComputedStyle(el2);
        if (cs.cursor !== 'pointer' || cs.visibility === 'hidden' ||
            cs.display === 'none') continue;
        if (visible(el2)) add(el2);
      }

      // 3. dedup per contenimento: fra elementi sovrapposti tiene il più
      //    interno (il target reale del click), scarta il contenitore.
      out.sort(function (a, b) {
        return a.__r.width * a.__r.height - b.__r.width * b.__r.height;
      });
      for (var k = 0; k < out.length; k++) {
        var r2 = out[k].__r;
        var inside = false;
        for (var m = 0; m < kept.length; m++) {
          var kr = kept[m].__r;
          if (r2.left >= kr.left - 2 && r2.right <= kr.right + 2 &&
              r2.top >= kr.top - 2 && r2.bottom <= kr.bottom + 2) { inside = true; break; }
        }
        if (!inside) kept.push(out[k]);
      }
      cache = kept;
      return kept;
    }

    function center(el) {
      var r = el.getBoundingClientRect();
      return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
    }

    function setFocus(el) {
      if (cur && cur !== el) {
        cur.classList.remove(RING);
        try { cur.dispatchEvent(new MouseEvent('mouseout', { bubbles: true })); } catch (e) {}
      }
      cur = el;
      if (!el) { hideFrame(); return; }
      el.classList.add(RING);
      try {
        el.scrollIntoView({ behavior: 'smooth', block: 'center', inline: 'center' });
        el.dispatchEvent(new MouseEvent('mouseover', { bubbles: true }));
        el.dispatchEvent(new MouseEvent('mouseenter', { bubbles: true }));
        // il popup hover del sito entra nel DOM: invalida e riscansiona presto
        invalidate();
        setTimeout(function () { invalidate(); if (cur === el) placeFrame(el); }, 260);
      } catch (e) {}
      placeFrame(el);
    }

    function firstVisible() {
      var cands = candidates(), best = null, bd = Infinity;
      for (var i = 0; i < cands.length; i++) {
        var c = center(cands[i]);
        var d = Math.abs(c.y - innerHeight / 2) + Math.abs(c.x - innerWidth / 2) * 0.3;
        if (d < bd) { bd = d; best = cands[i]; }
      }
      return best;
    }

    function move(dir) {
      var cands = candidates();
      if (!cands.length) return dir === 'left' ? 'edge' : false;
      if (!cur || !document.contains(cur)) {
        hideFrame();
        setFocus(firstVisible());
        return true;
      }
      var from = center(cur), best = null, bs = Infinity;
      for (var i = 0; i < cands.length; i++) {
        var el = cands[i];
        if (el === cur) continue;
        var c = center(el);
        var dx = c.x - from.x, dy = c.y - from.y;
        var ok = false, primary = 0, ortho = 0;
        if (dir === 'right' && dx > 12) { ok = true; primary = dx; ortho = Math.abs(dy); }
        else if (dir === 'left' && dx < -12) { ok = true; primary = -dx; ortho = Math.abs(dy); }
        else if (dir === 'down' && dy > 12) { ok = true; primary = dy; ortho = Math.abs(dx); }
        else if (dir === 'up' && dy < -12) { ok = true; primary = -dy; ortho = Math.abs(dx); }
        if (ok) { var sc = primary + ortho * 2.6; if (sc < bs) { bs = sc; best = el; } }
      }
      if (best) { setFocus(best); return true; }
      // nessun candidato nella direzione: su/giù scorrono la pagina
      if (dir === 'down' || dir === 'up') {
        window.scrollBy({ top: (dir === 'down' ? 1 : -1) * innerHeight * 0.66, behavior: 'smooth' });
        return true;
      }
      return dir === 'left' ? 'edge' : false;
    }

    function activate() {
      if (!cur) { setFocus(firstVisible()); return; }
      var tag = cur.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') { cur.focus(); return; }
      cur.click();
    }

    window.__sctvNav = { move: move, activate: activate };
  })();

  // First load: report quickly, then again in case the router settled late.
  setTimeout(reportPageInfo, 500);
  setTimeout(reportPageInfo, 2500);

  // SPA navigation: Inertia drives history.pushState/replaceState — hook
  // them (plus popstate) and re-report after the new page settles.
  var navReport = function () { setTimeout(reportPageInfo, 700); };
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
  window.addEventListener('popstate', function () { setTimeout(reportPageInfo, 700); });

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
