/**
 * SCViewer — Custom Web Receiver CAF (percorso 2): logica della vista
 * compatta SCtv + bridge via namespace "urn:x-cast:sctv".
 *
 * PROTOCOLLO (telefono → receiver, stesso canale):
 *  - eventi SCtv (envelope nativo {t, req, d}): boot | catalog | titleDetail |
 *    titleSeason | searchResults | categories | genreData | myListChanged |
 *    progressChanged | domainChanged | error — IDENTICI a window.__sctvReceive
 *    dell'app (SctvActivity.push li moltiplica qui via relayReceive);
 *  - comandi remoto: {cmd:"remote", action:"up|down|left|right|ok|back|home"}.
 *
 * PROTOCOLLO (receiver → telefono): {cmd:"androidjs", fn, args} — shadow di
 * AndroidJs: il telefono (SctvActivity.dispatchCastAndroidJs) esegue le
 * funzioni del bridge SCtv con gli handler reali e push() rimanda i dati.
 *
 * FUNZIONI ANDROIDJs USATE DAL RECEIVER (relay verso il telefono):
 *   bootReady()                  → boot completo (catalogo + righe + CW)
 *   loadTab(tab)                 → righe home/tv/movie/new
 *   loadDetail(titleId, req)     → titleDetail (episodi, seasons)
 *   search(q, req)               → searchResults
 *   loadCategories(req)          → categories (generi reali)
 *   loadGenre(genreId, req)      → genreData
 *   loadDetailSeason(id, s, req) → titleSeason (cambio stagione)
 *   toggleMyList(titleId)        → myListChanged
 *   getContinueWatching()        → progressChanged
 *   play(titleId, s, e)          → il telefono risolve lo stream e fa
 *                                  loadItem SU QUESTO RECEIVER (PlayerManager)
 *   setActiveProfile(profileId)  → dati scoped per profilo
 * NON inoltrate (vissute sul telefono): openSettings, openSiteInterface,
 * reportProgress (il progresso lo gestisce CAF sul receiver).
 *
 * PLAYER: PlayerManager CAF. Percorso principale = player nativo MPL (HLS
 * nativo, chiavi AES incluse). Con window.__sctvUseHlsJs = true il LOAD
 * viene inoltrato a hls.js (CDN) attaccato a #sctv-video via
 * playerManager.setMediaElement: i comandi play/pause/seek dei sender
 * restano standard CAF (PlayerManager guida l'elemento video).
 *
 * window.__receiverMode = true: marker per future iniezioni (early_hide.js
 * ecc. NON girano qui — nessuna pagina del sito viene caricata).
 */
'use strict';

window.__receiverMode = true;
window.__sctvUseHlsJs = false; // true = hls.js instead of native MPL

const NAMESPACE = 'urn:x-cast:sctv';

// ============================================================================
// 1. CAST CONTEXT + PLAYER
// ============================================================================

const context = cast.framework.CastReceiverContext.getInstance();
const playerManager = context.getPlayerManager();

/** Avvia con il namespace dichiarato (JSON, bidirezionale). */
function startReceiver() {
  const opts = new cast.framework.CastReceiverOptions();
  opts.customNamespaces = Object.assign({}, opts.customNamespaces);
  opts.customNamespaces[NAMESPACE] = cast.framework.system.MessageType.JSON;
  opts.maxInactivity = 3600; // 1h: sessione lunga, il telefono manda keepalive impliciti
  context.start(opts);
}

/**
 * Percorso hls.js: intercepisce LOAD, alimenta #sctv-video con hls.js e
 * passa l'elemento a PlayerManager (play/pause/seek dei sender restano
 * nativi perché PlayerManager guida l'elemento HTML5 video).
 * Attivo SOLO con __sctvUseHlsJs = true (default: MPL nativo).
 */
function wireHlsJs() {
  if (!window.Hls || !window.__sctvUseHlsJs) return;
  try {
    const videoEl = document.getElementById('sctv-video');
    playerManager.setMediaElement(videoEl);
    let hls = null;
    playerManager.setMessageInterceptor(
      cast.framework.messages.MessageType.LOAD,
      (loadRequestData) => {
        try {
          if (hls) { hls.destroy(); hls = null; }
          const src = loadRequestData.media && loadRequestData.media.contentId;
          if (src && window.Hls.isSupported()) {
            hls = new Hls({ enableWorker: true, lowLatencyMode: false });
            hls.attachMedia(videoEl);
            hls.on(window.Hls.Events.MANIFEST_PARSED, () => { try { videoEl.play(); } catch (e) {} });
            hls.on(window.Hls.Events.ERROR, (ev, data) => {
              if (data && data.fatal) console.warn('hls.js fatal', data.type, data.details);
            });
            hls.loadSource(src);
            // La master deve contenere tutte le varianti (StreamResolver
            // appende h=1): l'ABR di hls.js sceglie la migliore.
          }
        } catch (e) {
          console.warn('hls.js LOAD error', e);
        }
        // Non modificare il loadRequestData: MediaInfo resta quello del
        // telefono (title/CustomData) — lo stato verso i sender è corretto.
        return loadRequestData;
      }
    );
    console.log('receiver: hls.js wired');
  } catch (e) {
    console.warn('hls.js wiring failed — fallback MPL nativo', e);
  }
}
wireHlsJs();

/** Eventi player → viste receiver (player attivo ↔ browse). */
playerManager.addEventListener(
  cast.framework.events.category.CORE,
  (event) => {
    try {
      if (event.type === cast.framework.events.EventType.PLAYING ||
          event.type === cast.framework.events.EventType.BUFFERING) {
        setView('player');
      } else if (event.type === cast.framework.events.EventType.PLAY ||
                 event.type === cast.framework.events.EventType.PAUSE) {
        updateNowPlaying(playerManager.getMediaInformation());
      }
    } catch (e) { /* no-op */ }
  }
);

// ============================================================================
// 2. SHADOW AndroidJs — comandi verso il telefono
// ============================================================================

let reqCounter = 0;
function nextReq() { reqCounter += 1; return reqCounter; }

/** Invia {cmd:"androidjs", fn, args} al telefono (broadcast a tutti i sender). */
function sendAndroidJs(fn, args) {
  try {
    const msg = { cmd: 'androidjs', fn: fn, args: args || [] };
    context.sendCustomMessage(NAMESPACE, '*', JSON.stringify(msg));
  } catch (e) {
    console.warn('sendAndroidJs failed', fn, e);
  }
  setStatus('Comando al telefono: ' + fn);
}

/** SHADOW AndroidJs — stesse firme del bridge SCtv (SctvBridge). */
window.AndroidJs = {
  bootReady: () => { sendAndroidJs('bootReady'); setStatus('Boot…'); },
  loadTab: (tab) => sendAndroidJs('loadTab', [String(tab)]),
  loadDetail: (titleId, req) => sendAndroidJs('loadDetail', [Number(titleId), Number(req)]),
  search: (query, req) => sendAndroidJs('search', [String(query), Number(req)]),
  loadCategories: (req) => sendAndroidJs('loadCategories', [Number(req)]),
  loadGenre: (genreId, req) => sendAndroidJs('loadGenre', [Number(genreId), Number(req)]),
  loadDetailSeason: (titleId, season, req) =>
    sendAndroidJs('loadDetailSeason', [Number(titleId), Number(season), Number(req)]),
  toggleMyList: (titleId) => sendAndroidJs('toggleMyList', [Number(titleId)]),
  getContinueWatching: () => sendAndroidJs('getContinueWatching'),
  play: (titleId, season, episode) =>
    sendAndroidJs('play', [Number(titleId), Number(season), Number(episode)]),
  reportProgress: () => { /* gestito da CAF sul receiver */ },
  openSettings: () => { /* dialog sul telefono: non relayabile */ },
  openSiteInterface: () => { /* vive sul telefono */ },
  setActiveProfile: (pid) => sendAndroidJs('setActiveProfile', [String(pid)]),
  retryConnection: () => sendAndroidJs('retryConnection')
};

// ============================================================================
// 3. MESSAGGI DAL TELEFONO
// ============================================================================

context.addCustomMessageListener(NAMESPACE, (message) => {
  try {
    const data = message.data;
    if (typeof data === 'string') data = JSON.parse(data);
    if (data && data.cmd === 'remote') {
      handleRemoteAction(data.action);
    } else if (data && typeof data.t === 'string') {
      window.__sctvReceive(data);
    }
  } catch (e) {
    console.warn('custom message error', e);
  }
});

// ============================================================================
// 4. STATO + DISPATCHER __sctvReceive (stesso contratto dell'app)
// ============================================================================

const state = {
  catalog: [],
  rows: {},
  myList: [],
  watchProgress: {},
  activeTab: 'home',
  detailTitleId: 0,
  detailSeason: 1,
  pendingDetailReq: 0,
  pendingSeasonReq: 0,
  pendingSearchReq: 0,
  view: 'browse' // browse | player
};

/** UNICO entry dati dal telefono (moltiplicato da SctvActivity.push). */
window.__sctvReceive = function (ev) {
  try {
    if (!ev || typeof ev.t !== 'string') return;
    const d = ev.d || {};
    switch (ev.t) {
      case 'boot':
        state.catalog = Array.isArray(d.catalog) ? d.catalog : [];
        state.rows = d.rows || {};
        state.myList = Array.isArray(d.myList) ? d.myList : [];
        state.watchProgress = {};
        (d.continueWatching || []).forEach((cw) => {
          state.watchProgress[cw.id] = cw;
          if (cw && cw.titleId && !state.catalog.some((c) => c.titleId === cw.titleId)) {
            state.catalog.push({
              id: cw.id, titleId: cw.titleId, title: cw.name || 'In corso',
              type: cw.type || 'movie', posterUrl: cw.poster || '',
              url: cw.url || '', synopsis: cw.tag || '', episodes: []
            });
          }
        });
        setView('browse');
        renderHome();
        setStatus('');
        break;

      case 'catalog':
        if (d.tab === 'new') {
          (d.top10 || d.catalog || []).forEach((it) => upsertItem(it));
        } else {
          state.catalog = Array.isArray(d.catalog) ? d.catalog : [];
          if (Array.isArray(d.rows)) state.rows[d.tab] = d.rows;
        }
        state.activeTab = d.tab || 'home';
        renderHome();
        break;

      case 'titleDetail':
        if (ev.req !== state.pendingDetailReq) break;
        upsertItem(d.item);
        openDetailModal(d.item);
        break;

      case 'titleSeason':
        if (ev.req !== state.pendingSeasonReq) break;
        {
          const it = itemById(state.detailTitleId);
          if (it) {
            it.episodes = (it.episodes || [])
              .filter((ep) => (ep.season || 1) !== (d.season || state.detailSeason))
              .concat((d.episodes || []).map((ep) => Object.assign({ season: d.season || 1 }, ep)));
            state.detailSeason = d.season || state.detailSeason;
            renderDetail();
          }
        }
        break;

      case 'searchResults':
        if (ev.req !== state.pendingSearchReq) break;
        state.catalog = Array.isArray(d.catalog) ? d.catalog : state.catalog;
        renderGrid('Risultati per "' + (d.query || '') + '"', state.catalog.slice(0, 40));
        break;

      case 'categories':
        renderCategories(d.genres || [], d.catalog || []);
        break;

      case 'genreData':
        renderGrid('Genere', d.catalog || []);
        break;

      case 'myListChanged':
        state.myList = Array.isArray(d.ids) ? d.ids : state.myList;
        break;

      case 'progressChanged':
        (d.continueWatching || []).forEach((cw) => { state.watchProgress[cw.id] = cw; });
        if (state.view === 'browse' && state.detailTitleId === 0) renderHome();
        break;

      case 'domainChanged':
        setStatus('Dominio: ' + (d.host || ''));
        break;

      case 'error':
        setStatus('Errore: ' + (d.message || ''));
        break;
    }
  } catch (e) {
    console.error('__sctvReceive', e);
  }
};

function upsertItem(item) {
  if (!item || !item.id) return;
  const idx = state.catalog.findIndex((c) => c.id === item.id);
  if (idx >= 0) state.catalog[idx] = item; else state.catalog.push(item);
}

function itemById(titleId) {
  return state.catalog.find((c) => Number(c.titleId) === Number(titleId));
}

// ============================================================================
// 5. RENDER (vista compatta: righe orizzontali + griglie + dettaglio)
// ============================================================================

const viewEl = document.getElementById('sctv-view');
const detailEl = document.getElementById('detail');
let focusables = [];
let focusIdx = 0;

function setStatus(text) {
  document.getElementById('status').textContent = text || '';
}

function setView(name) {
  state.view = name;
  document.getElementById('sctv-ui').style.display = (name === 'player') ? 'none' : 'flex';
  const np = document.getElementById('now-playing');
  if (name === 'player') {
    np.style.display = 'block';
    updateNowPlaying(playerManager.getMediaInformation());
  } else {
    np.style.display = 'none';
  }
}

function updateNowPlaying(mi) {
  const md = mi && mi.metadata;
  const title = md && (md.title || '');
  const sub = md && (md.subtitle || '');
  const np = document.getElementById('now-playing');
  np.querySelector('.np-title').textContent = title || '';
  np.querySelector('.np-sub').textContent = sub || '';
}

/** RIGHE: da state.rows[activeTab] (shape {label, ids[]} — come l'app). */
function renderHome() {
  const rows = state.rows[state.activeTab] || state.rows.home || [];
  let html = '';
  if (rows.length === 0) {
    html = '<div class="rail"><div class="rail-title">Catalogo</div><div class="rail-cards">' +
      cardsHtml(state.catalog.slice(0, 12)) + '</div></div>';
  } else {
    rows.forEach((row) => {
      const items = (row.ids || [])
        .map((rid) => state.catalog.find((c) => c.id === rid))
        .filter(Boolean);
      if (items.length === 0) return;
      const label = row.isTop10 ? row.label + ' (Top 10)' : row.label;
      html += '<div class="rail"><div class="rail-title">' + esc(label) +
        '</div><div class="rail-cards">' + cardsHtml(items) + '</div></div>';
    });
  }
  viewEl.innerHTML = html;
  bindCards();
  refreshFocus();
}

function cardsHtml(items) {
  return items.map((it, i) => {
    const poster = it.posterUrl || '';
    const tag = seasonTag(it);
    return '<div class="card" data-idx="' + state._cardsLen + '" data-title-id="' + (it.titleId || 0) + '">' +
      '<img src="' + esc(poster) + '" alt="" onerror="this.style.visibility=\'hidden\'"/>' +
      '<div class="label">' + esc(it.title || '') + (tag ? ' · ' + esc(tag) : '') + '</div></div>';
  }).join('');
}

function seasonTag(it) {
  const cw = state.watchProgress[it.id];
  return cw ? cw.tag || '' : '';
}

/** Griglie (search/genre). */
function renderGrid(title, items) {
  let html = '<div class="rail"><div class="rail-title">' + esc(title) +
    '</div><div class="rail-cards">' + cardsHtml(items) + '</div></div>';
  viewEl.innerHTML = html;
  bindCards();
  refreshFocus();
}

/** Categorie: bottoni generi → loadGenre. */
function renderCategories(genres, catalog) {
  let html = '<div class="rail"><div class="rail-title">Categorie</div><div class="rail-cards">';
  genres.slice(0, 30).forEach((g) => {
    html += '<div class="card genre-card" data-genre-id="' + esc(String(g.id)) + '">' +
      '<div class="ph" style="height:60px;display:flex;align-items:center;justify-content:center;">' +
      esc(g.name || '') + '</div></div>';
  });
  html += '</div></div>';
  if (catalog.length) {
    html += '<div class="rail"><div class="rail-title">In evidenza</div><div class="rail-cards">' +
      cardsHtml(catalog.slice(0, 24)) + '</div></div>';
  }
  viewEl.innerHTML = html;
  bindCards();
  refreshFocus();
}

function bindCards() {
  viewEl.querySelectorAll('.card').forEach((el) => {
    el.addEventListener('click', () => {
      if (el.classList.contains('genre-card')) {
        const req = nextReq();
        window.AndroidJs.loadGenre(parseInt(el.getAttribute('data-genre-id'), 10), req);
        setStatus('Carico genere…');
        return;
      }
      const titleId = parseInt(el.getAttribute('data-title-id'), 10);
      if (!titleId) return;
      state.pendingDetailReq = nextReq();
      window.AndroidJs.loadDetail(titleId, state.pendingDetailReq);
      setStatus('Dettaglio…');
    });
  });
}

/** Modal dettaglio (titolo + My List + stagioni/episodi). */
function openDetailModal(item) {
  if (!item) return;
  state.detailTitleId = Number(item.titleId) || 0;
  state.detailSeason = Number(item.currentSeason) || 1;
  if (!itemById(state.detailTitleId)) state.catalog.push(item);
  renderDetail();
  detailEl.classList.add('open');
  refreshFocus();
}

function closeDetailModal() {
  detailEl.classList.remove('open');
  state.detailTitleId = 0;
  detailEl.innerHTML = '';
  refreshFocus();
}

function renderDetail() {
  const it = itemById(state.detailTitleId);
  if (!it) return;
  const inList = state.myList.some((id) => Number(id) === state.detailTitleId);
  const eps = (it.episodes || []).filter((ep) => (ep.season || 1) === state.detailSeason);
  const isTv = it.type === 'tv';
  let html = '<h2>' + esc(it.title || '') + '</h2>';
  html += '<div class="meta">' + esc(isTv ? (it.seasons ? it.seasons + ' stagioni' : 'Serie') : 'Film') +
    (it.year ? ' · ' + it.year : '') +
    ' · My List: ' + (inList ? 'SI' : 'NO') + '</div>';
  html += '<div class="synopsis">' + esc(it.synopsis || '') + '</div>';
  html += '<div><span class="btn" data-act="play">▶ Riproduci</span>' +
    '<span class="btn ghost" data-act="list">My List</span></div>';
  if (isTv) {
    html += '<div><span class="btn ghost" data-act="season-prev">◀</span>' +
      '<span style="font-weight:700;">Stagione ' + state.detailSeason + '</span>' +
      '<span class="btn ghost" data-act="season-next">▶</span></div>';
    html += '<div class="episodes">' + eps.map((ep) =>
      '<div class="ep-item" data-ep-id="' + (ep.id || 0) + '" data-ep-s="' + (ep.season || 1) +
      '" data-ep-n="' + (ep.ep || 0) + '">' +
      '<div class="ep-title">E' + (ep.ep || 0) + ' · ' + esc(ep.title || '') + '</div>' +
      '<div class="ep-desc">' + esc((ep.desc || '').slice(0, 120)) + '</div></div>'
    ).join('') + '</div>';
  }
  detailEl.innerHTML = html;

  detailEl.querySelectorAll('.btn').forEach((el) => {
    el.addEventListener('click', () => {
      const act = el.getAttribute('data-act');
      if (act === 'play') {
        if (isTv && eps.length) {
          const ep = eps[0];
          window.AndroidJs.play(state.detailTitleId, ep.season || 1, ep.ep || 1);
        } else {
          window.AndroidJs.play(state.detailTitleId, 1, 1);
        }
        setStatus('Play → il telefono risolve lo stream…');
      } else if (act === 'list') {
        window.AndroidJs.toggleMyList(state.detailTitleId);
      } else if (act === 'season-prev' || act === 'season-next') {
        const next = state.detailSeason + (act === 'season-next' ? 1 : -1);
        if (next >= 1) {
          state.pendingSeasonReq = nextReq();
          window.AndroidJs.loadDetailSeason(state.detailTitleId, next, state.pendingSeasonReq);
          setStatus('Carico stagione ' + next + '…');
        }
      }
    });
  });
  detailEl.querySelectorAll('.ep-item').forEach((el) => {
    el.addEventListener('click', () => {
      window.AndroidJs.play(
        state.detailTitleId,
        parseInt(el.getAttribute('data-ep-s'), 10),
        parseInt(el.getAttribute('data-ep-n'), 10)
      );
      setStatus('Play episodio…');
    });
  });
}

// ============================================================================
// 6. NAVIGAZIONE SPAZIALE (D-pad) — handleRemoteAction come nell'app
// ============================================================================

function refreshFocus() {
  const scope = detailEl.classList.contains('open') ? detailEl : viewEl;
  focusables = Array.prototype.slice.call(
    scope.querySelectorAll('.card, .btn, .ep-item')
  );
  focusIdx = 0;
  applyFocus();
}

function applyFocus() {
  focusables.forEach((el, i) => el.classList.toggle('focused', i === focusIdx));
  const cur = focusables[focusIdx];
  if (cur && cur.scrollIntoView) {
    try { cur.scrollIntoView({ block: 'nearest' }); } catch (e) { /* no-op */ }
  }
}

function handleRemoteAction(action) {
  if (state.view === 'player') {
    // Player attivo: i tasti comandinano il PLAYER del receiver.
    if (action === 'ok') {
      if (playerManager.playerState === cast.framework.messages.PlayerState.PAUSED) {
        playerManager.play();
      } else {
        playerManager.pause();
      }
    } else if (action === 'left' || action === 'right') {
      const dur = playerManager.getMediaInformation() ? playerManager.getDuration() || 0 : 0;
      const pos = playerManager.getCurrentTime() || 0;
      const delta = action === 'left' ? -10 : 10;
      playerManager.seek(Math.min(Math.max(pos + delta, 0), Math.max(dur - 1, 0)));
    } else if (action === 'back') {
      // Torna alla browse: il media CONTINUA in background.
      setView('browse');
      renderHome();
    }
    return;
  }

  // Browse: navigazione spaziale (stessa semantica dell'app SCtv).
  if (action === 'up' || action === 'down') {
    if (focusables.length === 0) return;
    // Su/giù: salta alla card vicina della riga sopra/sotto (euristicamente
    // +12 elementi per riga — card 128px su viewport 1280).
    const step = 12;
    focusIdx = (focusIdx + (action === 'down' ? step : -step) + focusables.length * step) % focusables.length;
    applyFocus();
  } else if (action === 'left' || action === 'right') {
    if (focusables.length === 0) return;
    focusIdx = (focusIdx + (action === 'right' ? 1 : -1) + focusables.length) % focusables.length;
    applyFocus();
  } else if (action === 'ok') {
    const cur = focusables[focusIdx];
    if (cur) cur.click();
  } else if (action === 'back') {
    if (detailEl.classList.contains('open')) closeDetailModal();
    else window.AndroidJs.loadTab('home');
  } else if (action === 'home') {
    closeDetailModal();
    window.AndroidJs.loadTab('home');
  }
}

function esc(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

// ============================================================================
// 7. AVVIO
// ============================================================================

function tickClock() {
  const el = document.getElementById('sctv-clock');
  if (el) el.textContent = new Date().toLocaleTimeString('it-IT', { hour: '2-digit', minute: '2-digit' });
}
setInterval(tickClock, 30_000);
tickClock();

// Boot: chiedi al telefono il payload completo (come bootReady della WebView).
startReceiver();
window.AndroidJs.bootReady();
setStatus('Attendo dati dal telefono…');
