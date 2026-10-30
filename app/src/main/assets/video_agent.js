/* SC Viewer — video agent.
 * Injected at document start in EVERY frame (main + vixcloud player iframe)
 * via WebViewCompat.addDocumentStartJavaScript; also evaluated in the main
 * frame after page load as a fallback. Idempotent.
 *
 * Two jobs:
 *  1. Report whether "a film is being watched" — a long, large, started
 *     <video> — to the native side (AndroidJs.onVideoState). The native side
 *     uses this to auto-hide the cursor while watching.
 *  2. Apply playback commands from the native side (AndroidJs.takeCommand —
 *     remote play/pause key and "Riprendi" seek-to). Native -> JS pushing
 *     isn't possible into cross-origin iframes, so the agent polls; the
 *     command is only consumed by the frame that owns an active video.
 *  3. Report playback position periodically (AndroidJs.onProgress) so the
 *     native side can persist "continue watching" state (position + the
 *     season/episode info it reads from the main frame).
 */
(function () {
  if (window.__scvAgent) return;
  window.__scvAgent = true;

  var MIN_FILM_DURATION = 120;   // seconds; filters out trailer/preview loops
  var MIN_VIEWPORT_AREA = 0.4;   // video must cover >40% of its frame viewport
  var lastActive = -1;           // last reported state (0/1), -1 = never

  function activeVideo() {
    try {
      var vs = document.querySelectorAll('video');
      var vw = window.innerWidth || 1, vh = window.innerHeight || 1;
      for (var i = 0; i < vs.length; i++) {
        var v = vs[i];
        if (!v.duration || v.duration < MIN_FILM_DURATION) continue;
        if (v.readyState < 2) continue;
        if (v.paused && v.currentTime < 30) continue; // not started yet
        var r = v.getBoundingClientRect();
        if (r.width < 200) continue;
        if ((r.width * r.height) / (vw * vh) < MIN_VIEWPORT_AREA) continue;
        return v;
      }
    } catch (e) { /* no-op */ }
    return null;
  }

  function report() {
    var active = activeVideo() ? 1 : 0;
    if (active === lastActive) return;
    lastActive = active;
    try { AndroidJs.onVideoState(active === 1); } catch (e) { /* no-op */ }
  }

  setInterval(report, 700);
  // React quickly to transitions instead of waiting up to 700 ms.
  ['play', 'pause', 'ended', 'loadedmetadata', 'emptied'].forEach(function (t) {
    document.addEventListener(t, report, true);
  });

  // Progress reporting for the "Riprendi" (continue watching) feature.
  // Throttled to one report every 10 s while playing, plus an immediate
  // report when the video pauses (capture phase, any frame).
  var lastProgressAt = 0;
  function maybeReportProgress(force) {
    try {
      var v = activeVideo();
      if (!v || !v.duration) return;
      var now = Date.now();
      if (!force && now - lastProgressAt < 10000) return;
      lastProgressAt = now;
      AndroidJs.onProgress(Math.round(v.currentTime), Math.round(v.duration));
    } catch (e) { /* no-op */ }
  }
  setInterval(function () { maybeReportProgress(false); }, 2000);
  document.addEventListener('pause', function () { maybeReportProgress(true); }, true);

  // Command pump: remote play/pause and resume seek-to. Only the frame
  // owning the active video takes the command, so the main frame and the
  // player iframe never fight over one request.
  setInterval(function () {
    try {
      var v = activeVideo();
      if (!v) return;
      var cmd = AndroidJs.takeCommand();
      if (cmd === 'toggle-play') {
        if (v.paused) {
          var p = v.play();
          if (p && p.catch) p.catch(function () { /* autoplay refusal */ });
        } else {
          v.pause();
        }
      } else if (cmd === 'pause') {
        if (!v.paused) v.pause();
      } else if (cmd === 'play') {
        if (v.paused) {
          var pp = v.play();
          if (pp && pp.catch) pp.catch(function () { /* autoplay refusal */ });
        }
      } else if (cmd.indexOf('seek-to:') === 0) {
        var target = parseFloat(cmd.slice(8));
        if (!isNaN(target)) {
          try { v.currentTime = Math.max(0, Math.min((v.duration || 1e9) - 1, target)); } catch (e) { /* no-op */ }
        }
      }
    } catch (e) { /* no-op */ }
  }, 80);
})();
