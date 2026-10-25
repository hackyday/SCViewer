/* SC Viewer — early hide of the site's Telegram banner.
 * Runs at document start in EVERY frame (registered via
 * WebViewCompat.addDocumentStartJavaScript), so the blue "clicca qui" bar is
 * hidden by CSS before its first paint instead of flashing for a second
 * until page_inject.js runs at page end.
 * The banner is rendered by the site's own Vue code as:
 *   <header><a href=...><div class="banner" style="background:...">…</div></a>
 *           <div class="close-banner">…</div></header>
 * The site also honours a hide_banner cookie (that is what its own ✕ button
 * sets), so we plant it for a year: after the first response the banner is
 * not even sent by the server any more.
 */
(function () {
  if (window.__scvEarlyHide) return;
  window.__scvEarlyHide = true;

  try {
    var s = document.createElement('style');
    s.id = 'scv-early-hide';
    s.textContent =
      'header a>div.banner, header div.close-banner { display:none !important; }';
    (document.head || document.documentElement).appendChild(s);
  } catch (e) { /* no-op */ }

  try {
    document.cookie = 'hide_banner=""; max-age=' + 3600 * 24 * 365 + '; path=/;';
  } catch (e) { /* no-op */
  }
})();
