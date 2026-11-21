# Cast Chromecast — SCViewer (branch tv-prototype)

Cast in **phone mode** (SctvActivity su telefono con Google Play services).
Su Fire TV il codice cast non viene mai istanziato (guard `DeviceProfile.isPhone`
+ `GoogleApiAvailability`).

## Due percorsi

| | Percorso 1 (default) | Percorso 2 (receiver custom) |
|---|---|---|
| Receiver | Default Media Receiver di Google | Receiver SCtv registrato |
| Attivazione | nessuna | setting "Cast receiver App ID" |
| Player sul TV | PlayerManager del Default receiver (HLS master m3u8) | receiver.html (CAF, MPL nativo o hls.js) |
| Interfaccia sul TV | solo player | vista SCtv compatta (righe, dettaglio, search) navigabile dal telecomando |
| Stato | testabile subito | richiede registrazione Cast SDK Developer Console |

## Percorso 1 — Default Media Receiver (test subito)

1. Connectare il telefono e il Chromecast alla stessa rete WiFi.
2. Aprire l'app (.debug o release), premere il bottone cast in alto a destra
   nell'interfaccia SCtv e scegliere il Chromecast → si apre il **telecomando**
   (RemoteActivity).
3. Scegliere un titolo dal catalogo: `StreamResolver` risolve la catena
   watch → wrapper /it/iframe → embed vixcloud → `window.masterPlaylist`
   (WebView headless 64dp) → master m3u8 con token 60gg + `h=1` + `lang`.
4. Il play/pausa, ±10s, scrub tenuto (10/30/60s + conferma), lingua (it/en),
   skip intro, card prossimo episodio e i progressi ("Continua a guardare")
   lavorano sulla sessione cast.

### h=1 (FHD sul Chromecast)

`StreamResolver.APPEND_H1 = true` aggiunge `&h=1` alla master: la variante
1080p entra nella playlist e il receiver fa ABR. Se una verifica sul campo
mostrasse effetti indesiderati, ribaltare la costante (unico punto).

### Limiti noti (percorso 1)

- **Sottotitoli**: funzionano SOLO se la master espone tracce text via
  `MediaStatus.mediaInfo.mediaTracks` (Default receiver + param `lang` della
  master). Altrimenti la voce resta disabilitata — l'embed vixcloud NON ha un
  parametro sub (verificato sul bundle).
- **FHD**: richiede che la CDN onori `h=1` per quel token (stesso meccanismo
  di HlsRewriter sul player locale).
- **CORS**: se la CDN vixcloud non servisse `Access-Control-Allow-Origin`
  su master/media/segmenti, il Default receiver non riesce a fare fetch HLS →
  vedere la sezione CORS sotto (test 2026-11-21 in fondo).

## DEBUG FAKE CAST (senza Chromecast)

Solo **build debug**:

1. Impostazioni → "CAST FINITO (DEBUG): ATTIVO";
2. long-press sul bottone cast nell'interfaccia SCtv → si apre il telecomando
   con nome "Cast finto (debug)";
3. scegliere un titolo: il play va a un `RemoteMediaClient` stub in memoria
   (`SctvCastController.FakePlayer`, film finto di 42 min, zero rete) — tutta
   la UX del telecomando (scrub, lingua dialog UI, skip, next card) è
   verificabile senza hardware.

## Percorso 2 — Receiver custom (richiede registrazione)

### Passo-passo per l'utente

1. **Hostare il receiver su HTTPS**: mettere
   `app/src/main/assets/receiver/receiver.html` e `receiver.js` nella STESSA
   cartella di un hosting HTTPS pubblico (es. GitHub Pages, Netlify — con
   Content-Type `text/javascript` per il .js). L'URL di receiver.html è
   quello da registrare.
2. **Registrare l'app receiver**: su [Google Cast SDK Developer Console]
   (https://cast.google.com/publish/) → "Add New Application" → tipo
   **Custom Receiver** → inserire l'URL del punto 1. Salvare l'**App ID**
   (formato tipo `ABCD1234`).
3. **Whitelisting seriale** (prima della pubblicazione): nella stessa console
   registrare il seriale del proprio Chromecast ("Devices") per poter
   caricare il receiver in preview — il seriale si legge dalla pagina
   `http://<ip-del-chromecast>:9191/support` (dalla rete locale) oppure dalla
   Google Home app (Device info).
4. **Impostare l'App ID nell'app**: Impostazioni → "Cast receiver App ID —
   opzionale, per receiver personalizzato" → incollare l'App ID → Salva →
   **riavviare l'app** (l'OptionsProvider viene letto alla creazione del
   CastContext).
5. **Test**: bottone cast → il receiver custom si avvia sul TV e mostra la
   vista SCtv compatta; la sezione **D-pad** appare in fondo al telecomando e
   i tasti inviano `{cmd:"remote", action}` via namespace
   `urn:x-cast:sctv`. Le booted rails arrivano dal telefono (relay
   `bootReady` → push `boot` → namespace).
6. **Pubblicazione**: quando funziona, dalla console pubblicare l'app
   ("Publish") — senza pubblicazione funziona solo sui seriali whitelisted.

### Protocollo receiver (riferimento rapido)

- telefono → receiver: eventi `{t, req, d}` (`window.__sctvReceive`) e
  `{cmd:"remote", action}` (D-pad);
- receiver → telefono: `{cmd:"androidjs", fn, args}` — funzioni AndroidJs
  relayate (bootReady, loadTab, loadDetail, search, loadCategories, loadGenre,
  loadDetailSeason, toggleMyList, getContinueWatching, play, setActiveProfile);
  openSettings/openSiteInterface/reportProgress restano sul telefono;
- player: `PlayerManager` CAF con master m3u8 mandate dal telefono
  (MediaInfo HLS). Percorso alternativo hls.js dietro flag
  `window.__sctvUseHlsJs` (default false — player nativo MPL).

## CORS della CDN vixcloud (verifica 2026-11-21, curl)

Catena reale: wrapper `/it/iframe/7166?episode_id=45838` → embed vixcloud
(scws=794997) → `window.masterPlaylist` (inline nell'HTML embed, token 60gg)
→ master / media playlist / segmenti / chiave AES, tutti richiesti con
`Origin: https://example.com` (l'origin arbitrario di un Default receiver).

| Risorsa | Status | Access-Control-Allow-Origin |
|---|---|---|
| Master `vixcloud.cc/playlist/794997?token=<60gg>&lang=it` | 200 | `*` |
| Media playlist video (`?type=video&rendition=720p&token=...`) | 200 | `*` |
| Media playlist audio (`?type=audio&rendition=ita&token=...`) | 200 | `*` |
| Segmento `.ts` su `sc-u18-01.vix-content.net` | 200 | `*` |
| Chiave AES `vixcloud.cc/storage/enc.key` | 200 | `*` |

**CONCLUSIONE: il CORS NON è un blocco.** La CDN serve `Access-Control-Allow-Origin: *`
su tutta la catena HLS → il Default receiver può fare fetch di master, media,
segmenti e chiave.

### h=1 (FHD) — BLOCCO VERIFICATO

La master CON `&h=1` risponde **403** da qualunque contesto testato (UA
desktop/mobile/TV, con/senza Origin, con/senza Referer). Correlato: l'embed
renderizza `window.canPlayFHD = false` e il bundle del sito aggiunge h=1
SOLO se la query dell'embed la porta (`r.get("canPlayFHD")`). Senza h=1 la
master contiene 480p + 720p (1080p assente).

Conseguenze e mitigazione implementata:
- `StreamResolver` NON appende h=1 alla cieca: `probeH1()` tenta la master+h=1
  e la tiene solo se risponde 200 con `#EXT-X-STREAM-INF`; altrimenti
  fallback automatico alla master senza h (cap 720p) — il cast non muore mai
  per h=1. Il risultato del probe è in cache.
- **FHD su cast: attualmente limitato a 720p** da questo account/rete (il
  player del sito, stesso contesto, resta comunque a 720p). Da riverificare
  col Chromecast reale / da una rete diversa: se la CDN abilita canPlayFHD
  per quel client, il probe lo raccoglie automaticamente.

Nota tecnica: param `scz=1` (portato dall'embed) ok da solo; `scz=1&h=1` →
403. Non lo appendiamo (master senza scz → 200).

## File

- `cast/DeviceProfile.kt` — isPhone (LEANBACK/TOUCHSCREEN)
- `cast/CastOptionsProvider.kt` — App ID default/custom
- `cast/StreamResolver.kt` — catena watch→wrapper→WebView→masterPlaylist+cache
- `cast/SctvCastController.kt` — sessione, loadItem, stato ~1s, record, fake
- `cast/RemoteActivity.kt` — telecomando (+ D-pad per percorso 2)
- `assets/receiver/receiver.html|.js` — receiver custom (percorso 2)
- Manifest: `OPTIONS_PROVIDER_CLASS_NAME`, `ACCESS_WIFI_STATE`, RemoteActivity
