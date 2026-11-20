# PLAYER V2 SCtv — modal DIRECT-EMBED (sintesi RE completo)

Fonti: RE embed vixcloud (bundle 3890B ricostruito, tutto VERIFICATO su curl 2026-11-20) + RE schermata watch (inventario visuale + skin CSS) + IMPLEMENTATION_PLAN.md.

## 1. PROBLEMA ATTUALE (perché v2)

PlayerActivity carica /it/watch/<id> → wrapper /it/iframe/ → iframe vixcloud (JWPlayer con skin di streamingcommunity): control bar JW + freccia back + "Pross. ep." + titolo/descrizione + display icons (i "tasti originali" segnalati dall'utente), doppio layer con i controlli nativi. Flash dell'home durante il warm-up cookie. Lingua solo via reload con lang param.

## 2. ARCHITETTURA V2 — DIRECT EMBED

Flusso (tutto verificato):
1. **Risoluzione (SctvActivity, OkHttp immediato pre-lancio)**:
   `GET {domain}/it/iframe/<titleId>?episode_id=<epId>` con `Referer: {domain}/` e Cookie header dai WebView cookies (CookieManager.getInstance().getCookie(domain) — fetchProps supporta cookieHeader, fatto in fix player v1) → HTML wrapper 812B statico → regex `src="(https://vixcloud.cc/embed/...)"` → token monouso 10s: usarlo SUBITO.
2. **Parametri**: al src estratto appendo `&nogui=1` (+ `&canPlayFHD=1` SOLO se noto FHD — altrimenti senza: max 720p in v2, FHD via test in-frame opzionale §4). NON passare minute (resume gestito da pendingSeek in secondi, più preciso).
3. **PlayerActivity mode "direct"** (extra `embedUrl` presente): `loadUrl(embedUrl, mapOf("Referer" to "{domain}/"))` — l'embed vixcloud È il main frame: nessun sito, nessun wrapper, nessun flash. 403 senza Referer (VERIFICATO).
4. **Registro document-start DIVERSO in direct mode**: player_bridge.js SOLO (no video_agent, no page_inject — inutili qui e potenzialmente rumorosi). player_bridge.js gira nel frame vixcloud e fa da ponte.

## 3. player_bridge.js (asset, document-start, guard hostname vixcloud)

```js
(function(){
  if (location.hostname.indexOf('vixcloud') === -1) return;
  var ready = false;
  function snap(){ var p = jwplayer(); return {
    state:p.getState(), pos:p.getPosition(), dur:p.getDuration(), mute:p.getMute(),
    levels:p.getQualityLevels(), quality:p.getCurrentQuality(),
    audio:p.getAudioTracks(), audioIx:p.getCurrentAudioTrack(),
    cc:p.getCaptionsList(), ccIx:p.getCurrentCaptions() }; }
  function send(ev){ try { AndroidJs.onPlayerEvent(JSON.stringify(ev)); } catch(e){} }
  var iv = setInterval(function(){
    if (typeof jwplayer !== 'function') return;
    var p; try { p = jwplayer(); } catch(e){ return; }
    if (!p || !p.getContainer) return;
    clearInterval(iv);
    try { p.setMute(false); } catch(e){}            // nogui parte muted
    try { p.on('time', function(e){ send({t:'time', pos:e.position, dur:p.getDuration()}); }); } catch(e2){}
    try { p.on('play', function(){ send({t:'state', state:'playing'}); }); } catch(e3){}
    try { p.on('pause', function(){ send({t:'state', state:'paused'}); }); } catch(e4){}
    try { p.on('complete', function(){ send({t:'complete'}); }); } catch(e5){}
    try { p.on('error', function(e){ send({t:'error', msg: e && e.message}); }); } catch(e6){}
    if (!ready) { ready = true; send({t:'ready', snapshot: snap()}); }
  }, 200);
  window.__sctvPlayer = {
    play:function(){ jwplayer().play(); },
    pause:function(){ jwplayer().pause(); },
    seek:function(s){ jwplayer().seek(s); },
    pos:function(){ return jwplayer().getPosition(); },
    dur:function(){ return jwplayer().getDuration(); },
    setAudio:function(i){ jwplayer().setCurrentAudioTrack(i); },
    setCaptions:function(i){ jwplayer().setCurrentCaptions(i); },
    setQuality:function(i){ jwplayer().setCurrentQuality(i); },
    snapshot:function(){ return JSON.stringify(snap()); }
  };
  // contain, non fill (nogui usa stretching:fill)
  var st = document.createElement('style');
  st.textContent = '.jw-video{object-fit:contain !important;} .jw-reset{color:inherit;}';
  document.head.appendChild(st);
})();
```
AndroidJs è disponibile in TUTTI i frame (addJavascriptInterface). onPlayerEvent: NUOVO metodo in JsInterface → listener in PlayerActivity.

## 4. PlayerActivity direct mode (extra `embedUrl` presente → flag directEmbed)

- Carica embedUrl con Referer header; overlay nero finché onPlayerEvent('ready') (o onPageFinished + ready), poi via.
- Controlli nativi wired a `window.__sctvPlayer.*` (bypassa la coda JsInterface legacy in questo mode):
  - playBtn → play/pause toggle (via stato da 'state' events)
  - rewBtn/ffwBtn → seek(pos±10)
  - scrubber → seek(pct*dur); pos/dur dagli eventi 'time'
  - **LINGUA → dialog LIVE**: snapshot da `window.__sctvPlayer.snapshot()` → sezioni AUDIO (tracks[].label — "Italian", "English") e SOTTOTITOLI (captionsList — "Off" = index corrente? JW: getCaptionsList()[0] è "Off") → apply setAudio/setCaptions, NESSUN reload (master già caricata)
  - Qualità: levels dal snapshot; opzione "Auto/1080p": con nogui NON c'è force-720p e h=1 non è stato aggiunto → 720p max. v2: default senza h=1 (720p); FHD rimandato a nota (test in-frame con fetch h=1 + jwplayer().load — opzionale, rischiato, non fare in v2 salvo tempo)
  - Skip Intro: euristica app-side su eventi 'time' (pos<30 && dur>60 → mostra bottone → seek(30))
  - Prossimo episodio: SctvActivity passa extra `nextEpisodeId` (+ season/episode) quando esiste (dal detail cache); PlayerActivity mostra la card a dur-pos<90 (come oggi); "Play Now" → setResult(nextEpisodeId) + finish() → SctvActivity onActivityResult → onPlayRequested(next) — la catena riparte pulita
  - BACK: come oggi (controlli → nascondi → esci)
- Progress: eventi 'time' → ProgressStore.save (throttle come oggi: ogni ~10s) + HistoryStore.add al primo ready + PosterCache (poster passato dall'extra, preso dal detail cache)
- Resume: pendingSeek = startPositionSec (secondi, esatto), arming al primo 'ready' (non onVideoState — in direct mode non c'è video_agent), converge ±20s come M3, desistenza 15s
- **Legacy mode resta**: senza extra embedUrl il flusso watch-page esistente è identico (site mode). Il flag decide solo wiring comandi + document-start script.

## 5. PULIZIE (da RE schermata watch)

1. shouldInterceptRequest PlayerActivity: blocca `spbgc.com` e `analytics.vixcloud.cc` (oltre adblock esistente) — anche legacy mode.
2. CSS pulizia main frame watch (legacy mode, difensivo): modal premium `body>.modal` display:none; noscript display:none; html/body overflow hidden bg #000 (dal RE watch §2).
3. NON sandboxare mai l'iframe (anti-sandbox check nell'embed → body rimpiazzato).
4. La modal premium "CANNOT_PLAY_FHD" non arriva mai dall'embed (messaggio mai inviato — codice morto nel parent).

## 6. NOTE/limiti v2

- FHD: senza canPlayFHD → 720p max (l'embed normale forzava 720p comunque — stessa qualità, GUI pulita). FHD futuro: test h=1 in-frame + jwplayer().load, con fallback 403.
- reportProgress resta riservato; il progresso arriva dagli eventi 'time' del bridge.
- Gli hash dei bundle cambiano a ogni redeploy: NON fare riferimento agli URL dei bundle — solo all'URL embed.
- Token 10s monouso: SctvActivity resolve → loadUrl entro pochi secondi (single-shot, 410 su riuso → re-resolve).
- `minute` server-side NON usato (pendingSeek in secondi è più preciso).
- Non hardcodare riferimenti a bundle/skin.
