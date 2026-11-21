package com.scviewer.fire.cast

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.CastDevice
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.Session
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import com.scviewer.fire.R
import com.scviewer.fire.data.HistoryStore
import com.scviewer.fire.data.PosterCache
import com.scviewer.fire.data.ProgressStore
import com.scviewer.fire.data.WatchProgress
import com.scviewer.fire.data.sanitizeProfileId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * SctvCastController (C2): cuore del cast in phone mode. Singleton che
 * incapsula CastContext/SessionManager/RemoteMediaClient, risolve lo stream
 * via [StreamResolver] e carica il master HLS sul receiver; espone lo stato
 * (posizione/durata/stato, ~1s) al telecomando [RemoteActivity] e salva i
 * progressi nello stesso schema di PlayerActivity.
 *
 * DUE MODALITÀ:
 *  - REALE: CastSession attiva (Default Media Receiver o receiver custom) →
 *    [RealPlayer] che wrappa RemoteMediaClient (load/play/pause/seek +
 *    ProgressListener a 1s + MediaStatus callbacks).
 *  - FINTA (SOLO build DEBUG + setting "cast fake"): [FakePlayer] stub con
 *    stato in memoria — serve a provare il telecomando RemoteActivity senza
 *    un Chromecast fisico (documentazione debug in fondo al file).
 *
 * RECEIVER CUSTOM (percorso 2, namespace [NAMESPACE]): ogni evento nativo→JS
 * `window.__sctvReceive(ev)` dell'interfaccia SCtv viene MOLTIPLICATO verso
 * il namespace ("boot", "titleDetail", ...) tramite [relayReceive]; i comandi
 * inversi del receiver ({cmd:"androidjs", fn, args}) arrivano su
 * [Host.onCastAndroidJs] — SctvActivity li esegue con gli stessi handler del
 * bridge AndroidJs (loadTab/loadDetail/search/toggleMyList/play/...), e le
 * risposte tornano al receiver perché push() fa relay. I tasti D-pad del
 * telecomando in percorso 2 inviano {cmd:"remote", action} via
 * [sendRemoteAction].
 *
 * Il cast esiste SOLO in phone mode: su Fire TV questo controller non viene
 * MAI istanziato (guard in SctvActivity — niente CastContext, niente listener).
 */
class SctvCastController private constructor(private val appCtx: Context) {

    companion object {
        /** Namespace del receiver custom SCtv (percorso 2). */
        const val NAMESPACE = "urn:x-cast:sctv"
        private const val TAG = "SctvCast"

        /** Durata del video finto (debug): un film "tipico". */
        const val FAKE_DURATION_SEC = 42 * 60

        /** Notifica manuale del percorso FAKE (debug): un solo ID, un canale. */
        private const val NOTIF_ID = 42
        private const val NOTIF_CHANNEL = "sctv_cast_playback"

        @Volatile private var INSTANCE: SctvCastController? = null

        fun get(ctx: Context): SctvCastController =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SctvCastController(ctx.applicationContext).also { INSTANCE = it }
            }

        /** Peek senza creare: null su Fire TV / prima del primo attach. */
        fun peek(): SctvCastController? = INSTANCE
    }

    /** Contratto con SctvActivity: container della WebView headless (per
     *  StreamResolver) e dispatcher dei comandi AndroidJs del receiver. */
    interface Host {
        fun castWebHost(): ViewGroup?
        fun onCastAndroidJs(fn: String, args: JSONArray)
    }

    // -- Metadati del titolo in riproduzione (per MediaInfo + record) ----------

    data class CastMeta(
        val titleId: Int,
        val season: Int,
        val episode: Int,
        val title: String,
        val episodeName: String,
        val type: String,               // "tv" | "movie"
        val slug: String,
        val watchPath: String,          // "/it/watch/<id>[?e=<ep>]" (MAI l'embed)
        val poster: String,
        val nextEpisodeId: Int,
        val nextEpisodeSeason: Int,
        val nextEpisodeNumber: Int,
        val profileId: String,
        val startPositionSec: Int,
        val lang: String
    ) {
        /** "S1E2 · Titolo puntata" per il telecomando (film: vuoto). */
        fun episodeLabel(): String =
            if (type == "tv" && episode > 0)
                "S$season E$episode" + (episodeName.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
            else ""

        /** CustomData del MediaInfo (contratto piano: titleId, season,
         *  episode, nextEpisodeId, watchPath). */
        fun toCustomData(): JSONObject = JSONObject()
            .put("titleId", titleId)
            .put("season", season)
            .put("episode", episode)
            .put("nextEpisodeId", nextEpisodeId)
            .put("watchPath", watchPath)
    }

    // -- Player astratto: reale (RemoteMediaClient) o finto (stub debug) -------

    interface CastPlayer {
        fun isFake(): Boolean
        fun isPlaying(): Boolean
        fun stateLabel(): String            // "playing"|"paused"|"buffering"|"idle"
        fun positionSec(): Int
        fun durationSec(): Int
        fun hasTextTracks(): Boolean
        fun isMuted(): Boolean
        fun setMuted(muted: Boolean)
        fun play()
        fun pause()
        fun seekTo(sec: Int)
        fun teardown()
    }

    /** Player REALE: RemoteMediaClient della CastSession corrente. */
    private inner class RealPlayer(private val client: RemoteMediaClient) : CastPlayer {

        private val progressListener = RemoteMediaClient.ProgressListener { _, _ ->
            pushSnapshot()
        }

        init {
            runCatching { client.addProgressListener(progressListener, 1000L) }
            runCatching {
                client.registerCallback(object : RemoteMediaClient.Callback() {
                    override fun onStatusUpdated() = pushSnapshot()
                    override fun onMetadataUpdated() = pushSnapshot()
                })
            }
        }

        override fun isFake(): Boolean = false
        override fun isPlaying(): Boolean =
            client.mediaStatus?.playerState == MediaStatus.PLAYER_STATE_PLAYING
        override fun stateLabel(): String = when (client.mediaStatus?.playerState) {
            MediaStatus.PLAYER_STATE_PLAYING -> "playing"
            MediaStatus.PLAYER_STATE_PAUSED -> "paused"
            MediaStatus.PLAYER_STATE_BUFFERING -> "buffering"
            else -> "idle"
        }
        override fun positionSec(): Int =
            (client.approximateStreamPosition / 1000L).toInt().coerceAtLeast(0)
        override fun durationSec(): Int =
            (client.streamDuration / 1000L).toInt().coerceAtLeast(0)
        override fun hasTextTracks(): Boolean =
            client.mediaStatus?.mediaInfo?.mediaTracks?.any { it.type == MediaTrack.TYPE_TEXT } == true

        // Mute di stream (setStreamMute): il receiver abbassa l'audio del TV;
        // lo stato autoritativo arriva col prossimo MediaStatus.
        override fun isMuted(): Boolean =
            client.mediaStatus?.isMute() == true
        override fun setMuted(muted: Boolean) =
            runCatching { client.setStreamMute(muted) }.let { }

        override fun play() = runCatching { client.play() }.let { }
        override fun pause() = runCatching { client.pause() }.let { }
        override fun seekTo(sec: Int) = runCatching { client.seek(sec * 1000L) }.let { }

        override fun teardown() {
            runCatching { client.removeProgressListener(progressListener) }
        }
    }

    /** Player FINTO (debug): stato in memoria, posizione avanza in tempo
     *  reale mentre "playing". Nessuna rete, nessun receiver. */
    private inner class FakePlayer(private val durationSecValue: Int) : CastPlayer {
        @Volatile private var playing = true
        @Volatile private var startPos = 0
        @Volatile private var muted = false
        private var playStartedAt = System.currentTimeMillis()

        override fun isFake(): Boolean = true
        override fun isPlaying(): Boolean = playing
        override fun stateLabel(): String = if (playing) "playing" else "paused"
        override fun positionSec(): Int {
            if (!playing) return startPos
            val pos = startPos +
                ((System.currentTimeMillis() - playStartedAt) / 1000L).toInt()
            if (pos >= durationSecValue) {
                playing = false
                startPos = durationSecValue
                return durationSecValue
            }
            return pos
        }
        override fun durationSec(): Int = durationSecValue
        override fun hasTextTracks(): Boolean = false
        // Muto finto (solo UI): il player non ha audio, il flag serve a far
        // lavorare il bottone mute del telecomando identico al caso reale.
        override fun isMuted(): Boolean = muted
        override fun setMuted(m: Boolean) {
            muted = m
            pushSnapshot()
        }
        override fun play() {
            if (playing) return
            startPos = positionSec()
            playStartedAt = System.currentTimeMillis()
            playing = true
            pushSnapshot()
        }
        override fun pause() {
            if (!playing) return
            startPos = positionSec()
            playing = false
            pushSnapshot()
        }
        override fun seekTo(sec: Int) {
            startPos = sec.coerceIn(0, durationSecValue)
            playStartedAt = System.currentTimeMillis()
            pushSnapshot()
        }
        override fun teardown() { }
    }

    // -- Stato -------------------------------------------------------------------

    /** Host corrente (SctvActivity) — null se staccata. */
    @Volatile private var host: Host? = null

    /** Eventi sessione per SctvActivity: "started" | "resumed" | "ended". */
    @Volatile var sessionUiListener: ((String) -> Unit)? = null

    /** Listener di snapshot (RemoteActivity si registra in onCreate). */
    private val snapshotListeners = CopyOnWriteArrayList<(Snapshot) -> Unit>()

    /** Listener "sessione finita" (RemoteActivity si chiude da sé). */
    private val sessionEndedListeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile private var castContext: CastContext? = null
    @Volatile private var currentPlayer: CastPlayer? = null
    @Volatile var currentMeta: CastMeta? = null
        private set

    /** Cast finto attivo (debug): coesiste con l'assenza di CastSession. */
    @Volatile private var fakeActive = false

    /** Lang corrente del player (per il dialog lingua del telecomando). */
    @Volatile private var currentLang = "it"

    /** Retry singolo dopo load fallito (invalidazione cache token). */
    @Volatile private var loadRetryTried = false

    /** Throttle dei salvataggi progresso (schema PlayerActivity: 10s). */
    private var lastSaveAt = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Tick 1s: avanza il player finto, push snapshot, salvataggi. */
    private val tickRunnable = object : Runnable {
        override fun run() {
            tick()
            mainHandler.postDelayed(this, 1000L)
        }
    }
    private var tickStarted = false

    // -- Snapshot ----------------------------------------------------------------

    data class Snapshot(
        val hasMedia: Boolean,
        val title: String,
        val episodeLabel: String,
        val poster: String,
        val state: String,
        val positionSec: Int,
        val durationSec: Int,
        val receiverName: String,
        val isFake: Boolean,
        val hasTextTracks: Boolean,
        val isMuted: Boolean,
        val lang: String
    )

    fun addSnapshotListener(l: (Snapshot) -> Unit) { snapshotListeners.add(l) }
    fun removeSnapshotListener(l: (Snapshot) -> Unit) { snapshotListeners.remove(l) }
    fun addSessionEndedListener(l: () -> Unit) { sessionEndedListeners.add(l) }
    fun removeSessionEndedListener(l: () -> Unit) { sessionEndedListeners.remove(l) }

    /** Snapshot corrente SENZA push (lettura sincrona per il telecomando). */
    fun currentSnapshot(): Snapshot = buildSnapshot()

    /** Push immediato dello snapshot (al mount del telecomando). */
    fun pushSnapshotNow() { pushSnapshot() }

    private fun pushSnapshot() {
        val snap = buildSnapshot()
        for (l in snapshotListeners) runCatching { l(snap) }
        maybeSaveProgress(snap)
        detectLoadFailure(snap)
    }

    private fun buildSnapshot(): Snapshot {
        val meta = currentMeta
        val player = currentPlayer
        return if (player == null || meta == null) Snapshot(
            hasMedia = false, title = "", episodeLabel = "", poster = "",
            state = "idle", positionSec = 0, durationSec = 0,
            receiverName = receiverName(), isFake = fakeActive,
            hasTextTracks = false, isMuted = false, lang = currentLang
        ) else Snapshot(
            hasMedia = true,
            title = meta.title,
            episodeLabel = meta.episodeLabel(),
            poster = meta.poster,
            state = player.stateLabel(),
            positionSec = player.positionSec(),
            durationSec = player.durationSec(),
            receiverName = receiverName(),
            isFake = player.isFake(),
            hasTextTracks = player.hasTextTracks(),
            isMuted = player.isMuted(),
            lang = currentLang
        )
    }

    /** Nome del receiver collegato (per l'intestazione del telecomando). */
    fun receiverName(): String {
        if (fakeActive && !isRealSessionActive()) return "Cast finto (debug)"
        return currentCastSession()?.castDevice?.friendlyName ?: ""
    }

    // -- Ciclo di vita ------------------------------------------------------------

    /**
     * Attach da SctvActivity.onCreate (SOLO phone mode + GMS OK): crea il
     * CastContext (API Task — la variante sincrona è deprecata e può bloccare
     * il thread), registra i listener di sessione e avvia il tick 1s.
     */
    /** true dopo la PRIMA registrazione riuscita del listener di sessione
     *  (addSessionManagerListener non deduplica: senza flag, registrare due
     *  volte = eventi doppi). */
    @Volatile private var sessionListenerRegistered = false

    /** Registra il listener di sessione NON APPENA castContext è disponibile.
     *  FIX: prima la registrazione avveniva solo in attach(), ma
     *  CastContext.getSharedInstance è una Task ASINCRONA — se non era ancora
     *  completata, castContext era null e la registrazione veniva saltata
     *  PER SEMPRE: la sessione partiva (il framework la vedeva) ma il
     *  listener non riceveva onSessionStarted → niente telecomando e play
     *  andato al percorso sbagliato. */
    private fun registerSessionListener() {
        if (sessionListenerRegistered) return
        castContext?.sessionManager?.let { sm ->
            runCatching {
                sm.addSessionManagerListener(sessionListener, CastSession::class.java)
                sessionListenerRegistered = true
            }
        }
    }

    fun attach(newHost: Host) {
        host = newHost
        ensureCastContext()
        registerSessionListener()
        if (!tickStarted) {
            tickStarted = true
            mainHandler.post(tickRunnable)
        }
    }

    /** Detach da SctvActivity.onDestroy. */
    fun detach(oldHost: Host) {
        if (host === oldHost) host = null
        castContext?.sessionManager?.let { sm ->
            runCatching {
                sm.removeSessionManagerListener(sessionListener, CastSession::class.java)
            }
        }
        sessionListenerRegistered = false
    }

    /** CastContext lazy (guarded): null su GMS assente o init fallito. */
    private fun ensureCastContext() {
        if (castContext != null) return
        runCatching {
            CastContext.getSharedInstance(appCtx, Runnable::run)
                .addOnSuccessListener { ctx ->
                    castContext = ctx
                    // FIX race: ora che castContext esiste, registra il
                    // listener di sessione (a questo punto può anche esserci
                    // già una sessione attiva ripresa dal GMS).
                    registerSessionListener()
                    // La sessione può essere ripresa dal GMS prima dell'init:
                    // se c'è già una sessione attiva, notifica come "resumed".
                    if (ctx.sessionManager.currentCastSession != null) {
                        sessionUiListener?.invoke("resumed")
                    }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "CastContext init fallita: ${e.message}")
                }
        }.onFailure { Log.w(TAG, "CastContext.getSharedInstance error", it) }
    }

    /** GoogleApiAvailability OK? (guard chiamata da SctvActivity). */
    fun isAvailable(): Boolean {
        val r = com.google.android.gms.common.GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(appCtx)
        return r == com.google.android.gms.common.ConnectionResult.SUCCESS
    }

    private fun currentCastSession(): CastSession? =
        castContext?.sessionManager?.currentCastSession

    /** Sessione REALE attiva (il fake non conta). */
    fun isRealSessionActive(): Boolean = currentCastSession() != null

    /** Cast attivo per il branch di onPlayRequested: reale O finto (debug). */
    fun isSessionActive(): Boolean = isRealSessionActive() || fakeActive

    // -- Listener di sessione ------------------------------------------------------

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            Log.i(TAG, "Cast session started: ${session.castDevice?.friendlyName}")
            registerSessionMessageCallback(session)
            sessionUiListener?.invoke("started")
        }
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            Log.w(TAG, "Cast session start failed: $error")
        }
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            Log.i(TAG, "Cast session resumed: ${session.castDevice?.friendlyName}")
            registerSessionMessageCallback(session)
            sessionUiListener?.invoke("resumed")
        }
        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            Log.w(TAG, "Cast session resume failed: $error")
        }
        override fun onSessionEnded(session: CastSession, error: Int) {
            Log.i(TAG, "Cast session ended (error=$error)")
            onSessionGone()
        }
        override fun onSessionSuspended(session: CastSession, reason: Int) {
            // Sospensione temporanea (es. app in background): il telecomando
            // resta valido al ritorno — non chiudiamo nulla qui.
            Log.i(TAG, "Cast session suspended (reason=$reason)")
        }
        override fun onSessionEnding(session: CastSession) {}
    }

    private fun onSessionGone() {
        currentPlayer?.teardown()
        currentPlayer = null
        currentMeta = null
        // Difensivo: la notifica fake non dovrebbe mai essere visibile durante
        // una sessione reale, ma si rimuove a qualunque fine sessione (per le
        // sessioni reali la rimozione della SUA notifica la fa il framework).
        cancelPlaybackNotification()
        for (l in sessionEndedListeners) runCatching { l() }
        sessionUiListener?.invoke("ended")
    }

    /** Callback messaggi dal receiver custom ({cmd:"androidjs", fn, args}). */
    private fun registerSessionMessageCallback(session: CastSession) {
        runCatching {
            session.setMessageReceivedCallbacks(
                NAMESPACE,
                Cast.MessageReceivedCallback { _, ns, message ->
                    if (ns != NAMESPACE) return@MessageReceivedCallback
                    handleReceiverMessage(message)
                }
            )
        }.onFailure { Log.w(TAG, "message callback reg failed", it) }
    }

    private fun handleReceiverMessage(message: String) {
        val obj = runCatching { JSONObject(message) }.getOrNull() ?: return
        when (obj.optString("cmd")) {
            // Relay AndroidJs: il receiver custom chiama le STESSE funzioni
            // del bridge SCtv (loadTab/loadDetail/search/...) — l'Activity
            // li esegue con gli handler reali e push() rimanda i dati via
            // namespace (relayReceive).
            "androidjs" -> {
                val h = host ?: return
                h.onCastAndroidJs(
                    obj.optString("fn"),
                    obj.optJSONArray("args") ?: JSONArray()
                )
            }
        }
    }

    // -- castPlay -------------------------------------------------------------------

    /**
     * Risolve lo stream e carica la riproduzione sul receiver. Suspend
     * (StreamResolver fa IO + WebView headless): chiamare da un scope Main.
     * true = load inviato; false = risoluzione/sessione fallita (il chiamante
     * segnala l'errore in UI).
     */
    suspend fun castPlay(meta: CastMeta, forceRefresh: Boolean = false): Boolean {
        currentLang = meta.lang
        // -- Percorso FINTO (debug): nessuna rete, stub in memoria -----------
        if (fakeActive && !isRealSessionActive()) {
            loadRetryTried = false
            currentPlayer?.teardown()
            currentPlayer = FakePlayer(FAKE_DURATION_SEC)
            currentMeta = meta
            logHistory(meta)
            pushSnapshot()
            // Notifica manuale (il framework cast non vede questo percorso).
            showFakePlaybackNotification()
            Log.i(TAG, "fake castPlay: ${meta.title} start=${meta.startPositionSec}s")
            return true
        }

        val session = currentCastSession()
        val client = session?.remoteMediaClient
        if (client == null) {
            Log.w(TAG, "castPlay senza sessione attiva")
            return false
        }

        // -- Risoluzione master (C2) ----------------------------------------
        val masterUrl = StreamResolver.resolveMasterUrl(
            context = appCtx,
            host = host?.castWebHost(),
            domain = currentDomain(),
            titleId = meta.titleId,
            episodeId = if (meta.type == "tv") episodeIdOf(meta) else 0,
            lang = meta.lang,
            forceRefresh = forceRefresh
        )
        if (masterUrl.isNullOrBlank()) {
            Log.w(TAG, "castPlay: master non risolvibile per ${meta.watchPath}")
            return false
        }

        // -- MediaInfo ---------------------------------------------------------
        val metadata = MediaMetadata(
            if (meta.type == "tv") MediaMetadata.MEDIA_TYPE_TV_SHOW else MediaMetadata.MEDIA_TYPE_MOVIE
        ).apply {
            putString(MediaMetadata.KEY_TITLE, meta.title)
            if (meta.episodeLabel().isNotBlank()) {
                putString(MediaMetadata.KEY_SUBTITLE, meta.episodeLabel())
            }
            if (meta.poster.isNotBlank()) {
                runCatching { addImage(WebImage(android.net.Uri.parse(meta.poster))) }
            }
        }
        val mediaInfo = MediaInfo.Builder(masterUrl)
            .setContentType("application/vnd.apple.mpegurl")
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setMetadata(metadata)
            .setCustomData(meta.toCustomData())
            .build()

        loadRetryTried = false
        currentPlayer?.teardown()
        currentPlayer = RealPlayer(client)
        currentMeta = meta
        logHistory(meta)

        return runCatching {
            // loadItem = load media + autoplay alla posizione di ripresa
            // (startPositionSec = resume di ProgressStore, schema PlayerActivity).
            client.load(mediaInfo, true, meta.startPositionSec.coerceAtLeast(0) * 1000L)
            Log.i(TAG, "loadItem → ${meta.title} (${meta.episodeLabel()}) " +
                "start=${meta.startPositionSec}s master=${masterUrl.substringBefore('&')}")
            pushSnapshot()
            true
        }.onFailure {
            Log.w(TAG, "loadItem fallito", it)
            currentMeta = null
            currentPlayer = null
        }.getOrDefault(false)
    }

    /** episodeId dal watchPath (?e=) — la resolve deve puntare la puntata ESATTA. */
    private fun episodeIdOf(meta: CastMeta): Int =
        meta.watchPath.substringAfter("e=", "").substringBefore('&').toIntOrNull() ?: 0

    /** Cronologia + poster cache: una voce per riproduzione (schema
     *  PlayerActivity — il record DEVE portare il watch URL, mai l'embed). */
    private fun logHistory(meta: CastMeta) {
        if (meta.watchPath.isBlank()) return
        runCatching {
            HistoryStore.get(appCtx).add(
                meta.watchPath,
                if (meta.episodeLabel().isNotBlank()) "${meta.title} — ${meta.episodeLabel()}"
                else meta.title,
                meta.poster.takeIf { it.isNotBlank() },
                sanitizeProfileId(meta.profileId)
            )
            if (meta.poster.isNotBlank()) PosterCache.put(appCtx, meta.titleId, meta.poster)
        }
    }

    /** Dominio corrente (per StreamResolver). */
    private fun currentDomain(): String =
        com.scviewer.fire.data.DomainRepository.get(appCtx).currentDomain()

    // -- Comandi player (telecomando) ---------------------------------------------

    fun togglePlayback() {
        val p = currentPlayer ?: return
        // Toggle via ultimo stato noto (play/pause); la VERITÀ resta nello
        // stato del receiver (MediaStatus → snapshot → icona corretta).
        if (p.isPlaying()) p.pause() else p.play()
    }

    fun play() { currentPlayer?.play() }
    fun pause() { currentPlayer?.pause() }

    /** Seek assoluto (scrub OK, skip intro, riapertura lingua). */
    fun seekTo(sec: Int) { currentPlayer?.seekTo(sec) }

    /** Muto on/off (bottone del telecomando): toggle sul player corrente.
     *  Il push immediato dà il feedback in UI; lo stato autoritativo resta
     *  il prossimo MediaStatus del receiver (per il fake è il flag interno). */
    fun toggleMute() {
        val p = currentPlayer ?: return
        p.setMuted(!p.isMuted())
        pushSnapshot()
    }

    /** Cambio lingua audio (dialog telecomando): re-resolve master con lang
     *  nuovo + loadItem a posizione preservata. Fallback: se il re-resolve
     *  fallisce si riprova con la cache (master ancora valida per token). */
    suspend fun switchLanguage(lang: String): Boolean {
        val meta = currentMeta ?: return false
        val pos = currentPlayer?.positionSec() ?: 0
        val target = meta.copy(lang = lang, startPositionSec = pos)
        currentLang = lang
        // forceRefresh = catena completa (briefing: re-resolve con lang nuovo);
        // fallback sulla cache se il sito non risponde.
        var ok = castPlay(target, forceRefresh = true)
        if (!ok) ok = castPlay(target, forceRefresh = false)
        return ok
    }

    /** Sottotitoli (percorso 1): MediaStatus.mediaInfo.mediaTracks → toggle
     *  della prima traccia TEXT via setActiveMediaTracks. Se non ci sono
     *  tracce text la voce nel telecomando resta disabilitata. */
    fun toggleSubtitles(): Boolean {
        val client = currentCastSession()?.remoteMediaClient ?: return false
        val status = client.mediaStatus ?: return false
        val tracks = status.mediaInfo?.mediaTracks ?: return false
        val textTrack = tracks.firstOrNull { it.type == MediaTrack.TYPE_TEXT } ?: return false
        val active = status.activeTrackIds ?: longArrayOf()
        val textActive = active.any { id -> id == textTrack.id }
        return runCatching {
            if (textActive) {
                // Disattiva: tieni SOLO le tracce non-text attualmente attive
                // (setActiveMediaTracks vuole un array NON vuoto; se restassero
                // solo tracce text, il toggle fallisce in silenzio —
                // comportamento conservativo).
                val nonText = active.filter { id ->
                    tracks.none { it.id == id && it.type == MediaTrack.TYPE_TEXT }
                }
                if (nonText.isNotEmpty()) {
                    client.setActiveMediaTracks(nonText.toLongArray())
                    true
                } else false
            } else {
                client.setActiveMediaTracks(longArrayOf(textTrack.id))
                true
            }
        }.onFailure { Log.w(TAG, "setActiveMediaTracks failed", it) }
            .getOrDefault(false)
    }

    // -- Receiver custom (percorso 2) -------------------------------------------------

    /** Moltiplica un evento nativo→JS (`window.__sctvReceive(ev)`) verso il
     *  namespace del receiver custom. Chiamato da SctvActivity.push() a ogni
     *  push: il receiver SCtv ricostruisce la vista compatta da quei dati. */
    fun relayReceive(evJson: String) {
        val session = currentCastSession() ?: return
        runCatching { session.sendMessage(NAMESPACE, evJson) }
            .onFailure { Log.w(TAG, "relayReceive send failed", it) }
    }

    /** Comando remoto verso il receiver custom ({cmd:"remote", action}) — i
     *  tasti D-pad del telecomando in percorso 2 (up/down/left/right/ok/back). */
    fun sendRemoteAction(action: String) {
        val session = currentCastSession() ?: return
        val msg = JSONObject().put("cmd", "remote").put("action", action)
        runCatching { session.sendMessage(NAMESPACE, msg.toString()) }
            .onFailure { Log.w(TAG, "sendRemoteAction failed", it) }
    }

    // -- Disconnect -------------------------------------------------------------------

    /** Helper per le azioni suspend del telecomando (scope Main del
     *  controller — castPlay/switchLanguage si hoppano da sole su IO/Main). */
    fun scopeLaunchForActivity(block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }
                .onFailure { Log.w(TAG, "remote action failed", it) }
        }
    }
    /** Disconnetti la sessione (icona del telecomando): endSession esplicito
     *  con stop — il receiver si chiude (setStopReceiverApplicationWhenEndingSession). */
    fun disconnect() {
        if (fakeActive && !isRealSessionActive()) {
            endFakeSession()
            return
        }
        runCatching {
            currentPlayer?.teardown()
            currentPlayer = null
            currentMeta = null
            castContext?.sessionManager?.endCurrentSession(true)
        }
    }

    // -- Notifica di riproduzione (percorso FAKE — debug) ---------------------------------

    /**
     * Percorso REALE: la notifica la mostra il framework cast
     * (CastOptionsProvider → CastMediaOptions.setNotificationOptions) quando
     * il MediaStatus del receiver è attivo, il tap sul body apre
     * RemoteActivity e la rimozione a sessione finita è gestita dal framework.
     *
     * Percorso FAKE (FakePlayer): NON esiste una CastSession → il
     * MediaNotificationManager del framework non parte. Qui una notifica
     * MANUALE con gli stessi componenti UX: tap → RemoteActivity, progressi
     * aggiornati dal tick 1s già esistente, rimossa a sessione finita /
     * disconnect. Nessuna dipendenza extra: NotificationCompat (core) +
     * MediaStyle (androidx.media).
     *
     * Permessi (Android 13+): senza POST_NOTIFICATIONS la notify() non
     * mostra nulla e non crasha; qui si esce subito se le notifiche sono
     * disabilitate (areNotificationsEnabled copre sia il permesso negato
     * sia il canale spento dall'utente) — nessuna eccezione propagata.
     */
    @Volatile private var fakeNotifShown = false

    /** Canale di notifica (API 26+): IMPORTANCE_LOW = senza suono. */
    private fun ensureNotifChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = appCtx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(NOTIF_CHANNEL) != null) return
        runCatching {
            nm.createNotificationChannel(
                NotificationChannel(
                    NOTIF_CHANNEL,
                    "Riproduzione cast (debug)",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Riproduzione remota SCtv (cast finto, debug)"
                }
            )
        }
    }

    /** ContentIntent: apre/riporta in cima il telecomando (singleTop →
     *  onNewIntent con refresh metadati, nessun doppione). */
    private fun remotePendingIntent(): PendingIntent {
        val intent = Intent(appCtx, RemoteActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(RemoteActivity.EXTRA_REFRESH, true)
        return PendingIntent.getActivity(
            appCtx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Builder della notifica fake: titolo (+ episodio), stato, receiver e
     *  barra di progresso. Null se non c'è più media fake da mostrare. */
    private fun buildFakePlaybackNotification(): NotificationCompat.Builder? {
        val snap = buildSnapshot()
        if (!fakeActive || !snap.hasMedia) return null
        val stateText = when (snap.state) {
            "playing" -> appCtx.getString(R.string.remote_state_playing)
            "paused" -> appCtx.getString(R.string.remote_state_paused)
            "buffering" -> appCtx.getString(R.string.remote_state_buffering)
            else -> appCtx.getString(R.string.remote_state_idle)
        }
        val title = if (snap.episodeLabel.isNotBlank())
            "${snap.title} — ${snap.episodeLabel}" else snap.title
        return NotificationCompat.Builder(appCtx, NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText("$stateText · ${snap.receiverName}")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(remotePendingIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // MediaStyle senza MediaSession token: solo layout media (le
            // azioni live nel percorso fake stanno sul telecomando).
            .setStyle(MediaStyle())
            .setProgress(snap.durationSec.coerceAtLeast(1), snap.positionSec, false)
    }

    /** Mostra la notifica fake (a castPlay riuscito). Silenziosa se le
     *  notifiche sono disabilitate (nessun crash, nessun toast). */
    private fun showFakePlaybackNotification() {
        if (!fakeActive) return
        val nm = NotificationManagerCompat.from(appCtx)
        if (!nm.areNotificationsEnabled()) return
        ensureNotifChannel()
        val b = buildFakePlaybackNotification() ?: return
        runCatching { nm.notify(NOTIF_ID, b.build()) }
            .onSuccess { fakeNotifShown = true }
            .onFailure { Log.w(TAG, "notifica fake non mostrata", it) }
    }

    /** Aggiornamento progressi: chiamato dal tick 1s (rebuild del builder —
     *  NotificationCompat non è mutabile). Se il media è finito/assente ma
     *  la notifica era visibile, la rimuove. */
    private fun updateFakePlaybackNotification() {
        if (!fakeNotifShown) return
        val nm = NotificationManagerCompat.from(appCtx)
        if (!nm.areNotificationsEnabled()) return
        val b = buildFakePlaybackNotification()
        if (b == null) {
            cancelPlaybackNotification()
            return
        }
        runCatching { nm.notify(NOTIF_ID, b.build()) }
            .onFailure { Log.w(TAG, "aggiornamento notifica fake fallito", it) }
    }

    /** Rimozione della notifica fake (sessione finita / disconnect). */
    private fun cancelPlaybackNotification() {
        if (!fakeNotifShown) return
        fakeNotifShown = false
        runCatching { NotificationManagerCompat.from(appCtx).cancel(NOTIF_ID) }
    }

    // -- Cast finto (DEBUG) -------------------------------------------------------------

    /** Attiva la sessione finta (long-press sul MediaRouteButton con il
     *  setting "cast fake" ON, solo build debug). Notifica "started" →
     *  SctvActivity apre il telecomando. */
    fun startFakeSession() {
        fakeActive = true
        Log.i(TAG, "CAST FINITO attivato (debug)")
        sessionUiListener?.invoke("started")
    }

    private fun endFakeSession() {
        if (!fakeActive) return
        fakeActive = false
        currentPlayer?.teardown()
        currentPlayer = null
        currentMeta = null
        cancelPlaybackNotification()
        Log.i(TAG, "CAST FINITO terminato")
        for (l in sessionEndedListeners) runCatching { l() }
        sessionUiListener?.invoke("ended")
    }

    // -- Tick 1s: snapshot + progressi ----------------------------------------------------

    private fun tick() {
        // Il player finto avanza da solo via elapsedRealtime (positionSec());
        // qui serve solo il push regolare dello snapshot + il refresh dei
        // progressi nella notifica fake (se visibile).
        pushSnapshot()
        updateFakePlaybackNotification()
    }

    /** Salvataggio progresso (throttle 10s, schema PlayerActivity direct):
     *  SOLO a video maturo (dur>60) e solo se il play è scattato davvero
     *  (pos>0). Record con watchPath RELATIVO — mai l'embed/master. */
    private fun maybeSaveProgress(snap: Snapshot) {
        val meta = currentMeta ?: return
        val player = currentPlayer ?: return
        if (!snap.hasMedia || snap.durationSec <= 60 || snap.positionSec <= 0) return
        if (snap.state == "idle") return
        val now = System.currentTimeMillis()
        if (now - lastSaveAt < 10_000) return
        lastSaveAt = now
        runCatching {
            ProgressStore.get(appCtx).save(
                WatchProgress(
                    titleId = meta.titleId,
                    name = meta.title,
                    slug = meta.slug,
                    type = meta.type.ifBlank { "movie" },
                    season = if (meta.type == "tv") meta.season else null,
                    episode = if (meta.type == "tv") meta.episode else null,
                    episodeName = meta.episodeName.takeIf { it.isNotBlank() },
                    positionSec = snap.positionSec,
                    durationSec = snap.durationSec,
                    url = meta.watchPath,
                    poster = meta.poster.takeIf { it.isNotBlank() },
                    updatedAt = now
                ),
                sanitizeProfileId(meta.profileId)
            )
        }.onFailure { Log.w(TAG, "progress save failed", it) }
    }

    /** Load fallito sul receiver (idle+ERROR): invalida la cache del token e
     *  riprova UNA volta con re-resolve (single-shot, mai in loop). */
    private fun detectLoadFailure(snap: Snapshot) {
        if (!snap.hasMedia || snap.state != "idle") return
        val client = currentCastSession()?.remoteMediaClient ?: return
        val status = client.mediaStatus ?: return
        if (status.idleReason != MediaStatus.IDLE_REASON_ERROR) return
        val meta = currentMeta ?: return
        if (loadRetryTried) return
        loadRetryTried = true
        Log.w(TAG, "receiver load ERROR → invalido cache e riprovo (${meta.watchPath})")
        StreamResolver.invalidate(
            appCtx,
            meta.titleId,
            if (meta.type == "tv") episodeIdOf(meta) else 0
        )
        scope.launch { castPlay(meta, forceRefresh = true) }
    }
}
