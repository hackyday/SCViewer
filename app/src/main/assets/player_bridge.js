/* SC Viewer — player bridge (PLAYER V2, modal direct-embed).
 * Iniettato a document start in OGNI frame SOLO in direct mode
 * (PlayerActivity con extra embedUrl): l'embed vixcloud È il main frame e
 * questo script fa da ponte tra l'istanza JW live e i controlli nativi.
 *
 * Cosa fa (piano §3, tutto verificato con curl 2026-11-20):
 *  - gira solo su hostname vixcloud (guard): negli altri frame è no-op;
 *  - polla l'esistenza dell'istanza JW (jwplayer() senza argomenti = istanza
 *    live del bundle nogui) e al primo hit:
 *      · setMute(false) — il bundle nogui parte muted;
 *      · si aggancia agli eventi time/play/pause/mute/complete/error e li
 *        inoltra a AndroidJs.onPlayerEvent(JSON) ('state' e 'mute' portano
 *        il mute corrente per la sincronizzazione del bottone nativo);
 *      · invia l'evento 'ready' con uno snapshot completo (stato, pos/dur,
 *        mute, livelli qualità, tracce audio, sottotitoli);
 *  - FIX audio alla prima riproduzione: ri-applica setMute(false) al primo
 *    evento 'play' (il mute:true della config può venire ri-applicato dal
 *    player dopo il ready, o la pipeline audio non era ancora attiva);
 *  - espone window.__sctvPlayer { play, pause, seek, pos, dur, setAudio,
 *    setCaptions, setQuality, setMute, mute, snapshot } per i comandi
 *    nativi diretti — bypassa la coda di JsInterface (video_agent non gira
 *    in questo mode);
 *  - forza object-fit:contain sul video (nogui usa stretching:fill).
 */
(function () {
  if (window.__sctvPlayerBridge) return;
  if (location.hostname.indexOf('vixcloud') === -1) return;
  window.__sctvPlayerBridge = true;

  var ready = false;

  // FIX audio "a volte muto alla prima riproduzione": setMute(false) al
  // ready può arrivare PRIMA che la pipeline audio sia attiva, oppure il
  // mute:true della config nogui può venire ri-applicato dal player
  // all'evento di partenza. Al primo 'play' lo stato mute viene quindi
  // ri-verificato (una sola volta: dopo, il mute è voluto dall'utente).
  var unmuteOnFirstPlay = true;
  // Ultimo mute esplicito richiesto dal nativo (bottone MUTO): null = mai
  // richiesto. L'auto-unmute non deve mai combattere con l'intento utente.
  var nativeMuteWanted = null;

  // Snapshot completo dell'istanza JW live (usato per l'evento 'ready' e
  // per il dialog LINGUA del nativo). Il mute è letto LIVE (getMute), mai
  // memoizzato: è lo stesso valore che il getter mute() espone al nativo.
  function snap() {
    var p = jwplayer();
    return {
      state: p.getState(), pos: p.getPosition(), dur: p.getDuration(), mute: p.getMute(),
      levels: p.getQualityLevels(), quality: p.getCurrentQuality(),
      audio: p.getAudioTracks(), audioIx: p.getCurrentAudioTrack(),
      cc: p.getCaptionsList(), ccIx: p.getCurrentCaptions()
    };
  }

  // Unico canale JS→nativo (AndroidJs è disponibile in TUTTI i frame).
  function send(ev) {
    try { AndroidJs.onPlayerEvent(JSON.stringify(ev)); } catch (e) { /* no-op */ }
  }

  // Poll fino a che l'istanza JW esiste (il bundle nogui crea il player
  // dopo il suo bootstrap); 200ms è abbastanza reattivo senza costare CPU.
  var iv = setInterval(function () {
    if (typeof jwplayer !== 'function') return;
    var p;
    try { p = jwplayer(); } catch (e) { return; }
    if (!p || !p.getContainer) return;
    clearInterval(iv);
    try { p.setMute(false); } catch (e) {}            // nogui parte muted
    try {
      p.on('time', function (e) {
        send({ t: 'time', pos: e.position, dur: p.getDuration() });
      });
    } catch (e2) {}
    try {
      p.on('play', function () {
        // FIX audio (vedi unmuteOnFirstPlay): ri-applica UNA volta al primo
        // 'play' — qui la pipeline audio è attiva e qualunque re-applica-
        // zione del mute:true della config è già avvenuta.
        if (unmuteOnFirstPlay) {
          unmuteOnFirstPlay = false;
          if (nativeMuteWanted !== true) {
            try { if (p.getMute()) p.setMute(false); } catch (eU) {}
          }
        }
        // mute incluso: il bottone MUTO nativo resta sincronizzato anche se
        // il player ha ri-mutato alla partenza.
        send({ t: 'state', state: 'playing', mute: !!p.getMute() });
      });
    } catch (e3) {}
    try {
      p.on('pause', function () { send({ t: 'state', state: 'paused', mute: !!p.getMute() }); });
    } catch (e4) {}
    try {
      // Evento nativo JW di toggle mute: la verità per il bottone del
      // nativo (arriva anche per mute impostati fuori dai nostri comandi).
      p.on('mute', function (e) { send({ t: 'mute', mute: !!(e && e.mute) }); });
    } catch (e7) {}
    try { p.on('complete', function () { send({ t: 'complete' }); }); } catch (e5) {}
    try { p.on('error', function (e) { send({ t: 'error', msg: e && e.message }); }); } catch (e6) {}
    if (!ready) {
      ready = true;
      send({ t: 'ready', snapshot: snap() });
    }
  }, 200);

  // API nativo→JS: i controlli D-pad del player chiamano qui direttamente.
  window.__sctvPlayer = {
    play: function () { jwplayer().play(); },
    pause: function () { jwplayer().pause(); },
    seek: function (s) { jwplayer().seek(s); },
    pos: function () { return jwplayer().getPosition(); },
    dur: function () { return jwplayer().getDuration(); },
    setAudio: function (i) { jwplayer().setCurrentAudioTrack(i); },
    setCaptions: function (i) { jwplayer().setCurrentCaptions(i); },
    setQuality: function (i) { jwplayer().setCurrentQuality(i); },
    // FIX audio: mute esplicito del nativo (bottone MUTO direct mode) —
    // registra l'intento utente così l'auto-unmute al primo 'play' non lo
    // sovrascrive mai.
    setMute: function (b) {
      nativeMuteWanted = !!b;
      try { jwplayer().setMute(!!b); } catch (e) {}
    },
    // Getter live del mute: usato dal snapshot del bottone nativo (sempre
    // lo stato REALE dell'istanza JW, mai uno stato memoizzato).
    mute: function () {
      try { return !!jwplayer().getMute(); } catch (e) { return true; }
    },
    snapshot: function () { return JSON.stringify(snap()); }
  };

  // contain, non fill (nogui usa stretching:fill). A document start
  // document.head può non esistere ancora: fallback su documentElement
  // (stesso schema di early_hide.js).
  try {
    var st = document.createElement('style');
    st.textContent = '.jw-video{object-fit:contain !important;} .jw-reset{color:inherit;}';
    (document.head || document.documentElement).appendChild(st);
  } catch (e) { /* no-op */ }
})();
