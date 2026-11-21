package com.scviewer.fire.cast

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.scviewer.fire.R
import com.scviewer.fire.data.SettingsStore
import coil.load

/**
 * Telecomando Chromecast (C2 — phone mode): schermata con TUTTI i comandi
 * del player sul TV, in stile Fire TV remoto.
 *
 * Comandi (Percorso 1 — Default Media Receiver):
 *  - Play/Pausa (toggle) e ⟲10/10⟳ singoli (seek ±10s);
 *  - SCRUB: ⟲/⟳ TENUTI → accelerazione 10/30/60s (stessa UX di
 *    PlayerActivity: video in pausa, preview del target, conferma esplicita —
 *    su telefono "Vai a quel punto" fa da OK, "Annulla"/BACK cancella);
 *  - LINGUA: Audio Italiano/Inglese via re-resolve master con lang nuovo +
 *    loadItem a posizione preservata; SOTTOTITOLI via setActiveMediaTracks
 *    (voce disabilitata se il MediaStatus non espone tracce text);
 *  - Skip Intro (pos<30 && dur>60 → seek 30);
 *  - Prossimo episodio: card a dur-pos<90 → castPlay(next) con
 *    nextEpisodeId dai metadati correnti;
 *  - BACK → chiude IL TELECOMANDO (la sessione cast resta viva);
 *  - Disconnect → endSession esplicita (il receiver si ferma).
 *
 * Comandi (Percorso 2 — receiver custom): la sezione D-pad in fondo invia
 * {cmd:"remote", action} via namespace urn:x-cast:sctv — l'interfaccia SCtv
 * compatta sul TV esegue handleRemoteAction. La sezione appare SOLO se un
 * Cast receiver App ID custom è impostato nelle impostazioni.
 *
 * Stato: Snapshot del [SctvCastController] a ~1s (ProgressListener + tick)
 * aggiorna poster/titolo/barra/tempi/stato/card; i progressi li salva il
 * controller (throttle 10s, stesso schema di PlayerActivity).
 *
 * DEBUG FAKE CAST (senza Chromecast):
 *  1. build debug + Impostazioni → "CAST FINITO (DEBUG): ATTIVO";
 *  2. long-press sul bottone cast nell'interfaccia SCtv → si apre questa
 *     schermata col nome "Cast finto (debug)";
 *  3. scegli un titolo: castPlay va allo [SctvCastController.FakePlayer]
 *     stub (film finto di 42 min, stato in memoria, zero rete) e il
 *     telecomando lavora identico al caso reale.
 */
class RemoteActivity : Activity() {

    private lateinit var posterView: ImageView
    private lateinit var titleView: TextView
    private lateinit var episodeView: TextView
    private lateinit var receiverView: TextView
    private lateinit var statusView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var timeCur: TextView
    private lateinit var timeTot: TextView
    private lateinit var noMediaView: TextView
    private lateinit var playBtn: TextView
    private lateinit var rewBtn: TextView
    private lateinit var ffwBtn: TextView
    private lateinit var langBtn: TextView
    private lateinit var subsBtn: TextView
    private lateinit var skipBtn: TextView
    private lateinit var disconnectBtn: TextView
    private lateinit var nextCard: View
    private lateinit var nextLabel: TextView
    private lateinit var nextPlay: TextView
    private lateinit var nextCancel: TextView
    private lateinit var dpadSection: View
    private lateinit var scrubBox: View
    private lateinit var scrubTime: TextView
    private lateinit var scrubCommit: TextView
    private lateinit var scrubCancel: TextView

    private var ctl: SctvCastController? = null
    private val handler = Handler(Looper.getMainLooper())

    /** true quando l'utente ha annullato la card prossimo episodio. */
    private var nextCardDismissed = false

    // -- SCRUB MODE (portato da PlayerActivity, adattato al touch) -------------
    private var scrubActive = false
    private var scrubTargetSec = 0
    private var scrubBaseSec = -1
    private var scrubSteps = 0
    private var scrubDir = 1
    private var scrubWasPlaying = false

    /** Timer del gesto touch tenuto su ⟲/⟳: emette gli incrementi con
     *  accelerazione mentre il dito resta premuto. */
    private var scrubAdjustScheduled = false

    // -- Soglie (stesse di PlayerActivity) ---------------------------------------
    private companion object {
        const val TAG = "RemoteActivity"
        /** Hold minimo (ms) prima di entrare in scrub mode col dito. */
        const val SCRUB_HOLD_MS = 200L
        /** Intervallo degli incrementi durante il hold. */
        const val SCRUB_REPEAT_MS = 300L
        /** Passo base / medio / massimo dell'accelerazione. */
        const val SCRUB_STEP_NEAR = 10
        const val SCRUB_STEP_MED = 30
        const val SCRUB_STEP_FAR = 60
        /** Incrementi prima del passo medio (30s) e massimo (60s). */
        const val SCRUB_REPEATS_MED = 6
        const val SCRUB_REPEATS_FAST = 12
        /** Passo fine ai bordi della timeline. */
        const val SCRUB_STEP_EDGE = 5
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val controller = SctvCastController.peek()
        if (controller == null) {
            // Su Fire TV / senza wiring cast questa schermata non ha senso.
            Log.w(TAG, "controller assente: RemoteActivity chiude subito")
            finish()
            return
        }
        ctl = controller
        setContentView(R.layout.activity_remote)

        posterView = findViewById(R.id.remotePoster)
        titleView = findViewById(R.id.remoteTitle)
        episodeView = findViewById(R.id.remoteEpisode)
        receiverView = findViewById(R.id.remoteReceiverName)
        statusView = findViewById(R.id.remoteStatus)
        progress = findViewById(R.id.remoteProgress)
        timeCur = findViewById(R.id.remoteTimeCur)
        timeTot = findViewById(R.id.remoteTimeTot)
        noMediaView = findViewById(R.id.remoteNoMedia)
        playBtn = findViewById(R.id.remotePlay)
        rewBtn = findViewById(R.id.remoteRew)
        ffwBtn = findViewById(R.id.remoteFfw)
        langBtn = findViewById(R.id.remoteLang)
        subsBtn = findViewById(R.id.remoteSubs)
        skipBtn = findViewById(R.id.remoteSkip)
        disconnectBtn = findViewById(R.id.remoteDisconnect)
        nextCard = findViewById(R.id.remoteNextCard)
        nextLabel = findViewById(R.id.remoteNextLabel)
        nextPlay = findViewById(R.id.remoteNextPlay)
        nextCancel = findViewById(R.id.remoteNextCancel)
        dpadSection = findViewById(R.id.remoteDpadSection)
        scrubBox = findViewById(R.id.remoteScrubBox)
        scrubTime = findViewById(R.id.remoteScrubTime)
        scrubCommit = findViewById(R.id.remoteScrubCommit)
        scrubCancel = findViewById(R.id.remoteScrubCancel)

        playBtn.setOnClickListener {
            controller.togglePlayback()
            updatePlayIcon()
        }
        rewBtn.setOnClickListener { seekBy(-SCRUB_STEP_NEAR) }
        ffwBtn.setOnClickListener { seekBy(SCRUB_STEP_NEAR) }
        attachScrubTouch(rewBtn, -1)
        attachScrubTouch(ffwBtn, 1)

        langBtn.setOnClickListener { showLanguageDialog() }
        subsBtn.setOnClickListener {
            controller.toggleSubtitles()
        }
        skipBtn.setOnClickListener {
            val c = currentPlayerOrNull() ?: return@setOnClickListener
            if (c.positionSec < 30 && c.durationSec > 60) controller.seekTo(30)
        }
        disconnectBtn.setOnClickListener {
            controller.disconnect()
            // endSession notifica 'ended' → il listener chiude l'activity;
            // finish() difensivo per il caso in cui la notifica non arrivi.
            finish()
        }
        nextPlay.setOnClickListener { playNextEpisode() }
        nextCancel.setOnClickListener {
            nextCardDismissed = true
            nextCard.visibility = View.GONE
        }
        scrubCommit.setOnClickListener { commitScrub() }
        scrubCancel.setOnClickListener { cancelScrub() }

        // Percorso 2: sezione D-pad SOLO con receiver custom impostato.
        bindDpad()

        controller.addSnapshotListener(onSnapshot)
        controller.addSessionEndedListener(onSessionEnded)
        controller.pushSnapshotNow()
    }

    override fun onDestroy() {
        super.onDestroy()
        ctl?.let {
            it.removeSnapshotListener(onSnapshot)
            it.removeSessionEndedListener(onSessionEnded)
        }
        handler.removeCallbacksAndMessages(null)
    }

    // -- Stato (Snapshot del controller, ~1s) -----------------------------------

    private val onSnapshot: (SctvCastController.Snapshot) -> Unit = { snap -> runOnUiThread {
        if (scrubActive) {
            // In scrub la barra/tempo corrente mostrano il TARGET: gli eventi
            // residui non devono sovrascriverli (stesso schema PlayerActivity).
            return@runOnUiThread
        }
        receiverView.text = if (snap.receiverName.isBlank())
            getString(R.string.remote_title) else snap.receiverName
        if (!snap.hasMedia) {
            titleView.text = ""
            episodeView.text = ""
            posterView.load(null as String?) { crossfade(false) }
            statusView.text = ""
            progress.progress = 0
            timeCur.text = ""
            timeTot.text = ""
            noMediaView.visibility = View.VISIBLE
            playBtn.visibility = View.GONE
            rewBtn.visibility = View.GONE
            ffwBtn.visibility = View.GONE
            langBtn.visibility = View.GONE
            subsBtn.visibility = View.GONE
            skipBtn.visibility = View.GONE
            nextCard.visibility = View.GONE
            return@runOnUiThread
        }
        noMediaView.visibility = View.GONE
        playBtn.visibility = View.VISIBLE
        rewBtn.visibility = View.VISIBLE
        ffwBtn.visibility = View.VISIBLE
        langBtn.visibility = View.VISIBLE
        titleView.text = snap.title
        episodeView.text = snap.episodeLabel
        if (snap.isFake) receiverView.text = getString(R.string.remote_receiver_fake)
        statusView.text = stateLabel(snap.state)
        if (snap.poster.isNotBlank()) posterView.load(snap.poster) { crossfade(true) }
        progress.max = snap.durationSec.coerceAtLeast(1)
        progress.progress = snap.positionSec
        timeCur.text = fmt(snap.positionSec)
        timeTot.text = fmt(snap.durationSec)
        updatePlayIcon(playState = snap.state == "playing")

        // Skip Intro: primi 30s di un titolo >60s (euristica PlayerActivity).
        val skipOk = snap.durationSec > 60 && snap.positionSec > 0 && snap.positionSec < 30
        skipBtn.visibility = if (skipOk) View.VISIBLE else View.GONE

        // Sottotitoli: abilitati solo se il receiver espone tracce text.
        subsBtn.isEnabled = snap.hasTextTracks
        subsBtn.setTextColor(
            getColor(if (snap.hasTextTracks) R.color.sctv_text_primary else R.color.sctv_text_muted)
        )

        // Prossimo episodio: card negli ultimi 90s (solo serie).
        val meta = ctl?.currentMeta
        val showNext = !nextCardDismissed &&
            (meta?.nextEpisodeId ?: 0) > 0 &&
            snap.durationSec - snap.positionSec < 90 &&
            snap.positionSec > snap.durationSec / 2
        nextCard.visibility = if (showNext) View.VISIBLE else View.GONE
        if (showNext) {
            nextLabel.text = getString(R.string.remote_next_ep) +
                " — S${meta?.nextEpisodeSeason} E${meta?.nextEpisodeNumber}"
        }
    } }

    private val onSessionEnded: () -> Unit = {
        runOnUiThread { finish() }
    }

    /** Snapshot immediato al mount (il tick 1s arriva comunque dopo). */
    private fun updatePlayIcon(playState: Boolean? = null) {
        val playing = playState ?: (ctl?.currentSnapshot()?.state == "playing")
        playBtn.text = if (playing) "❚❚" else "▶"
    }

    /** Lettura sincrona dello stato (azioni esplicite dell'utente). */
    private fun currentPlayerOrNull(): SctvCastController.Snapshot? =
        ctl?.currentSnapshot()

    private fun stateLabel(state: String): String = when (state) {
        "playing" -> getString(R.string.remote_state_playing)
        "paused" -> getString(R.string.remote_state_paused)
        "buffering" -> getString(R.string.remote_state_buffering)
        else -> getString(R.string.remote_state_idle)
    }

    private fun fmt(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)

    // -- Seek singoli ------------------------------------------------------------

    /** Seek ±10s (durata ignota → no-op, come PlayerActivity). */
    private fun seekBy(delta: Int) {
        val snap = currentPlayerOrNull() ?: return
        if (snap.durationSec <= 0) return
        ctl?.seekTo((snap.positionSec + delta).coerceIn(0, snap.durationSec - 1))
    }

    // -- SCRUB via touch (hold su ⟲/⟳) --------------------------------------------

    /**
     * Hold su ⟲/⟳: DOWN → seek ±10 immediato (come pressione singola) e
     * parte il timer di hold; oltre la soglia [SCRUB_HOLD_MS] entra SCRUB
     * MODE (pausa + preview del target) e gli incrementi accelerano
     * 10s → 30s → 60s finché il dito resta premuto. UP → il timer si ferma
     * ma lo scrub RESTA attivo: il commit è esplicito ("Vai a quel punto"
     * su telefono = OK su Fire TV; "Annulla"/BACK ripristina la base).
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachScrubTouch(btn: TextView, dir: Int) {
        btn.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val snap = currentPlayerOrNull()
                    if (snap != null && snap.durationSec > 0) {
                        scrubWasPlaying = snap.state == "playing"
                        // Base PRE-gesto per il cancel (net-zero movement).
                        scrubBaseSec = snap.positionSec.coerceIn(0, snap.durationSec)
                        // Pressione singola: seek ±10 immediato.
                        if (!scrubActive) {
                            ctl?.seekTo((snap.positionSec + dir * SCRUB_STEP_NEAR)
                                .coerceIn(0, snap.durationSec - 1))
                        }
                        // Timer hold → scrub accelerato.
                        scrubSteps = 0
                        scrubDir = dir
                        scheduleScrubHold()
                    }
                    v.performClick()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopScrubHoldTimer()
                    true
                }
                else -> false
            }
        }
    }

    private var scrubHoldStartAt = 0L
    private var scrubEntered = false

    private fun scheduleScrubHold() {
        scrubHoldStartAt = SystemClock.elapsedRealtime()
        scrubEntered = false
        scrubAdjustScheduled = true
        handler.postDelayed(scrubTick, SCRUB_HOLD_MS)
    }

    private val scrubTick: Runnable = object : Runnable {
        override fun run() {
            val elapsed = SystemClock.elapsedRealtime() - scrubHoldStartAt
            if (!scrubEntered) {
                if (elapsed < SCRUB_HOLD_MS) return
                enterScrub(scrubDir)
                scrubEntered = true
            }
            adjustScrubTarget(scrubDir, accelerated = true)
            handler.postDelayed(this, SCRUB_REPEAT_MS)
        }
    }

    private fun stopScrubHoldTimer() {
        if (scrubAdjustScheduled) {
            scrubAdjustScheduled = false
            handler.removeCallbacks(scrubTick)
        }
        scrubEntered = false
    }

    /** Attiva la SCRUB MODE: pausa (preview) + indicatore del target. */
    private fun enterScrub(dir: Int) {
        val snap = currentPlayerOrNull() ?: return
        if (snap.durationSec <= 0) return
        scrubActive = true
        scrubWasPlaying = snap.state == "playing"
        scrubSteps = 0
        scrubDir = dir
        scrubTargetSec = (if (scrubBaseSec >= 0) scrubBaseSec else snap.positionSec)
            .coerceIn(0, snap.durationSec)
        ctl?.pause()
        updateScrubUi()
    }

    /** Aggiusta il target con accelerazione (10s → 30s → 60s), ai bordi
     *  passo fine, sempre dentro [0, dur]. Nessun seek reale. */
    private fun adjustScrubTarget(dir: Int, accelerated: Boolean) {
        if (!scrubActive) return
        val snap = currentPlayerOrNull() ?: return
        if (snap.durationSec <= 0) return
        val step = when {
            !accelerated -> SCRUB_STEP_NEAR
            scrubSteps >= SCRUB_REPEATS_FAST -> SCRUB_STEP_FAR
            scrubSteps >= SCRUB_REPEATS_MED -> SCRUB_STEP_MED
            else -> SCRUB_STEP_NEAR
        }
        if (accelerated) scrubSteps++
        scrubDir = dir
        var next = scrubTargetSec + dir * step
        if (next < 0 || next > snap.durationSec) {
            next = scrubTargetSec + dir * minOf(step, SCRUB_STEP_EDGE)
        }
        scrubTargetSec = next.coerceIn(0, snap.durationSec)
        updateScrubUi()
    }

    /** Preview: indicatore grande (▶▶/◀◀ + target) + barra/tempo sul target. */
    private fun updateScrubUi() {
        if (!scrubActive) return
        scrubBox.visibility = View.VISIBLE
        scrubTime.text = (if (scrubDir < 0) "◀◀ " else "▶▶ ") + fmt(scrubTargetSec)
        progress.progress = scrubTargetSec.coerceAtMost(progress.max)
        timeCur.text = fmt(scrubTargetSec)
    }

    /** Commit: seek al target + play (il video riparte da lì). */
    private fun commitScrub() {
        if (!scrubActive) return
        val target = scrubTargetSec
        exitScrub()
        stopScrubHoldTimer()
        ctl?.seekTo(target)
        ctl?.play()
    }

    /** Cancel: NESSUN seek al target; il video torna alla base pre-gesto e
     *  riprende il play se era in play (net-zero movement). */
    private fun cancelScrub() {
        if (!scrubActive) return
        val wasPlaying = scrubWasPlaying
        val base = scrubBaseSec
        exitScrub()
        stopScrubHoldTimer()
        if (base >= 0) ctl?.seekTo(base.coerceIn(0, (currentPlayerOrNull()?.durationSec ?: base) - 1))
        if (wasPlaying) ctl?.play()
    }

    /** Esce dalla scrub mode e nasconde l'indicatore. */
    private fun exitScrub() {
        scrubActive = false
        scrubTargetSec = 0
        scrubSteps = 0
        scrubBaseSec = -1
        scrubBox.visibility = View.GONE
    }

    // -- Lingua / sottotitoli ------------------------------------------------------

    /** Dialog LINGUA: AUDIO Italiano/Inglese (re-resolve master + loadItem a
     *  posizione preservata) e SOTTOTITOLI (toggle setActiveMediaTracks —
     *  voce disabilitata senza tracce text nel MediaStatus). */
    private fun showLanguageDialog() {
        val snap = currentPlayerOrNull()
        val items = mutableListOf<String>()
        items.add("Audio: Italiano")
        items.add("Audio: Inglese")
        val subsOk = snap?.hasTextTracks == true
        items.add(
            if (subsOk) "Sottotitoli (attiva/disattiva)"
            else getString(R.string.remote_subs_unavailable)
        )
        AlertDialog.Builder(this, R.style.SCOverlay)
            .setTitle("Lingua e sottotitoli")
            .setItems(items.toTypedArray()) { dlg, which ->
                dlg.dismiss()
                val c = ctl ?: return@setItems
                when (which) {
                    0 -> if (snap?.lang != "it") scopeLaunch { c.switchLanguage("it") }
                    1 -> if (snap?.lang != "en") scopeLaunch { c.switchLanguage("en") }
                    2 -> if (subsOk) c.toggleSubtitles()
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun scopeLaunch(block: suspend () -> Unit) {
        // Scope di SctvCastController è Main: qui si riporta con un mini
        // wrapper (la resolve interna hop pure su IO per le fetch).
        val c = ctl ?: return
        c.scopeLaunchForActivity(block)
    }

    // -- Prossimo episodio ------------------------------------------------------------

    /** castPlay(next): stessa puntata-meta con season/episode avvantati, id
     *  dal CustomData.nextEpisodeId; la resolve ricatta il token master per
     *  la puntata nuova (cache per scwsId diverso). */
    private fun playNextEpisode() {
        val c = ctl ?: return
        val meta = c.currentMeta ?: return
        if (meta.nextEpisodeId <= 0) return
        val next = meta.copy(
            season = if (meta.type == "tv") meta.nextEpisodeSeason.coerceAtLeast(1) else 1,
            episode = if (meta.type == "tv") meta.nextEpisodeNumber.coerceAtLeast(1) else 1,
            episodeName = "",
            watchPath = "/it/watch/${meta.titleId}?e=${meta.nextEpisodeId}",
            nextEpisodeId = 0,
            nextEpisodeSeason = 0,
            nextEpisodeNumber = 0,
            startPositionSec = 0
        )
        nextCardDismissed = true
        nextCard.visibility = View.GONE
        scopeLaunch { c.castPlay(next) }
    }

    // -- Percorso 2: sezione D-pad ------------------------------------------------------

    /** D-pad → {cmd:"remote", action} verso il receiver custom. Visibile
     *  SOLO con un Cast receiver App ID impostato (Default receiver: nessun
     *  namespace → sezione nascosta). */
    private fun bindDpad() {
        val custom = SettingsStore.getCastAppId(this).isNotBlank()
        dpadSection.visibility = if (custom) View.VISIBLE else View.GONE
        if (!custom) return
        findViewById<TextView>(R.id.remoteDpadUp).setOnClickListener {
            ctl?.sendRemoteAction("up")
        }
        findViewById<TextView>(R.id.remoteDpadDown).setOnClickListener {
            ctl?.sendRemoteAction("down")
        }
        findViewById<TextView>(R.id.remoteDpadLeft).setOnClickListener {
            ctl?.sendRemoteAction("left")
        }
        findViewById<TextView>(R.id.remoteDpadRight).setOnClickListener {
            ctl?.sendRemoteAction("right")
        }
        findViewById<TextView>(R.id.remoteDpadOk).setOnClickListener {
            ctl?.sendRemoteAction("ok")
        }
    }

    // -- Tasti hardware ------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (code == KeyEvent.KEYCODE_BACK) {
                // Scrub aperto → annulla; altrimenti chiude SOLO il telecomando
                // (la sessione cast resta viva in background).
                if (scrubActive) cancelScrub() else finish()
                return true
            }
            // D-pad fisico (es. remoto Bluetooth): percorso 2 se attivo.
            if (dpadSection.visibility == View.VISIBLE) {
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> ctl?.sendRemoteAction("up")
                    KeyEvent.KEYCODE_DPAD_DOWN -> ctl?.sendRemoteAction("down")
                    KeyEvent.KEYCODE_DPAD_LEFT ->
                        if (scrubActive) adjustScrubTarget(-1, accelerated = false)
                        else ctl?.sendRemoteAction("left")
                    KeyEvent.KEYCODE_DPAD_RIGHT ->
                        if (scrubActive) adjustScrubTarget(1, accelerated = false)
                        else ctl?.sendRemoteAction("right")
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
                        if (scrubActive) commitScrub()
                        else ctl?.sendRemoteAction("ok")
                    else -> return super.dispatchKeyEvent(event)
                }
                return true
            }
            // Tasti media (remoto Bluetooth in percorso 1).
            when (code) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    ctl?.togglePlayback()
                    updatePlayIcon()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-SCRUB_STEP_NEAR); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(SCRUB_STEP_NEAR); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
