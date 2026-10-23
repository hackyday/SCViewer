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
    'div[id*="ads-container"]'
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
      // Block window.open entirely — all clicks should navigate in-place.
      window.open = function () { return null; };
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

  // ---- 3. Skip-intro detection ---------------------------------------------
  function setupSkipIntro() {
    try {
      var tried = 0;
      var interval = setInterval(function () {
        tried++;
        if (tried > 60) { // give up after ~30 s
          clearInterval(interval);
          return;
        }
        var v = document.querySelector('video');
        if (!v) return;
        clearInterval(interval);

        var reported = false;
        function check() {
          if (!v.duration) return;
          if (!reported && v.currentTime < 30 && v.currentTime > 0.5) {
            try { AndroidJs.onSkipIntroAvailable(true); } catch (e) {}
            reported = true;
          } else if (reported && v.currentTime >= 30) {
            try { AndroidJs.onSkipIntroAvailable(false); } catch (e) {}
            reported = false;
          }
        }
        v.addEventListener('timeupdate', check);
        // Also poll, since some sites never fire timeupdate during scrubbing.
        setInterval(check, 1000);

        // Provide a JS-side skip too, in case the native button isn't focused.
        AndroidJs.skipIntro = function () {
          v.currentTime = Math.min(v.duration || 60, 30);
        };
      }, 500);
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

  // ---- Run ----------------------------------------------------------------
  hideAds();
  killPopunders();
  setupSkipIntro();
  installScrollGuard();

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
