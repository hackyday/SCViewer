package com.scviewer.fire.cast

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.scviewer.fire.R
import com.scviewer.fire.data.ProgressStore

/**
 * Telecomando a schermo pieno per la sessione Chromecast.
 *
 * Il telefono controlla il Default Media Receiver (play/pausa, ±10 s,
 * ±30 s, seekbar, interrompi). La posizione riprodotta viene salvata nel
 * ProgressStore con la stessa chiave usata dal Fire TV: "Riprendi" resta
 * sincronizzato tra telefono e stick.
 *
 * Lanciata da: [com.scviewer.fire.MainActivity] (dopo il collegamento) o
 * dalla notifica Cast (NotificationOptions.targetActivityClassName).
 */
class CastRemoteActivity : Activity() {

    private lateinit var titleView: TextView
    private lateinit var timeView: TextView
    private lateinit var durationView: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var playPause: Button

    private var castContext: CastContext? = null
    private var titleId = 0
    private var progressDirty = false

    /** Aggiorna l'UI dal RemoteMediaClient (eventi + tick 1 s). */
    private val uiTick = object : Runnable {
        override fun run() {
            updateUi()
            window?.decorView?.postDelayed(this, 1000)
        }
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {}
        override fun onSessionEnded(session: CastSession, error: Int) {
            finish()   // trasmissione finita: chiudi il telecomando
        }
        override fun onSessionResumed(session: CastSession, sessionEnded: Boolean) {}
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStartFailed(session: CastSession, error: Int) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cast_remote)

        titleView = findViewById(R.id.castTitle)
        timeView = findViewById(R.id.castPosition)
        durationView = findViewById(R.id.castDuration)
        seekBar = findViewById(R.id.castSeek)
        playPause = findViewById(R.id.castPlayPause)

        titleId = intent.getIntExtra(EXTRA_TITLE_ID, 0)

        // Pulsante ufficiale di selezione dispositivo (icona cast in alto).
        CastButtonFactory.setUpMediaRouteButton(this, findViewById<androidx.mediarouter.app.MediaRouteButton>(R.id.castRouteBtn))

        castContext = runCatching { CastContext.getSharedInstance(this) }.getOrNull()
        castContext?.sessionManager?.addSessionManagerListener(sessionListener, CastSession::class.java)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {
                val dur = seekBar.max
                if (dur > 0) remote()?.seek(dur * seekBar.progress / 1000L)
            }
        })

        fun relSeek(deltaSec: Long) {
            val c = remote() ?: return
            val target = (c.approximateStreamPosition + deltaSec * 1000L)
                .coerceIn(0L, c.streamDuration)
            c.seek(target)
        }
        findViewById<Button>(R.id.castMinus30).setOnClickListener { relSeek(-30) }
        findViewById<Button>(R.id.castMinus10).setOnClickListener { relSeek(-10) }
        findViewById<Button>(R.id.castPlus10).setOnClickListener { relSeek(10) }
        findViewById<Button>(R.id.castPlus30).setOnClickListener { relSeek(30) }
        playPause.setOnClickListener {
            val c = remote() ?: return@setOnClickListener
            if (c.mediaStatus?.playerState == com.google.android.gms.cast.MediaStatus.PLAYER_STATE_PLAYING) c.pause() else c.play()
        }
        findViewById<Button>(R.id.castStop).setOnClickListener {
            castContext?.sessionManager?.endCurrentSession(true)
            finish()
        }

        window?.decorView?.post(uiTick)
    }

    override fun onResume() {
        super.onResume()
        castContext?.sessionManager?.addSessionManagerListener(sessionListener, CastSession::class.java)
        updateUi()
    }

    override fun onPause() {
        super.onPause()
        castContext?.sessionManager?.removeSessionManagerListener(sessionListener, CastSession::class.java)
    }

    override fun onDestroy() {
        window?.decorView?.removeCallbacks(uiTick)
        castContext?.sessionManager?.removeSessionManagerListener(sessionListener, CastSession::class.java)
        super.onDestroy()
    }

    private fun remote() = castContext?.sessionManager?.currentCastSession?.remoteMediaClient

    /** Aggiorna testo/seekbar dallo stato del receiver. */
    private fun updateUi() {
        runCatching { updateUiInner() }.onFailure { it.printStackTrace() }
    }

    private fun updateUiInner() {
        val c = remote()
        val info = c?.mediaInfo
        if (info == null) {
            titleView.text = getString(R.string.cast_no_media)
            timeView.text = "0:00"
            durationView.text = "0:00"
            seekBar.progress = 0
            playPause.text = getString(R.string.cast_play)
            return
        }
        titleView.text = info.metadata?.getString(MediaMetadata.KEY_TITLE)
            ?: getString(R.string.cast_no_media)

        val pos = c.approximateStreamPosition
        val dur = c.streamDuration
        timeView.text = format(pos)
        durationView.text = format(dur)
        if (dur > 0 && !seekBar.isPressed) {
            seekBar.max = dur.toInt()
            seekBar.progress = pos.toInt()
        }
        playPause.text = getString(
                if (c.mediaStatus?.playerState == com.google.android.gms.cast.MediaStatus.PLAYER_STATE_PLAYING) R.string.cast_pause else R.string.cast_play)

        // Salva la posizione nel ProgressStore (stessa chiave del Fire TV):
        // una volta al minuto è sufficiente.
        if (titleId != 0 && dur > 60 && !progressDirty) {
            saveCastProgress(pos.toInt(), dur.toInt())
            progressDirty = true
            window?.decorView?.postDelayed({ progressDirty = false }, 60_000)
        }
    }

    private fun saveCastProgress(posSec: Int, durSec: Int) {
        val p = intent.getSerializableExtra(EXTRA_PROGRESS) as? ProgressSnapshot ?: return
        ProgressStore.get(this).save(
            p.toWatchProgress().copy(
                positionSec = posSec,
                durationSec = durSec,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private fun format(sec: Long): String {
        if (sec <= 0) return "0:00"
        val s = sec / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val r = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, r)
        else String.format("%d:%02d", m, r)
    }

    /** Serializable minimale per passare il record di visione all'activity
     *  (WatchProgress non è Serializable). */
    class ProgressSnapshot(
        val titleId: Int, val name: String, val slug: String, val type: String,
        val season: Int?, val episode: Int?, val episodeName: String?,
        val positionSec: Int, val durationSec: Int, val url: String, val updatedAt: Long
    ) : java.io.Serializable {
        fun toWatchProgress() = com.scviewer.fire.data.WatchProgress(
            titleId, name, slug, type, season, episode, episodeName,
            positionSec, durationSec, url, updatedAt
        )

        companion object {
            fun of(p: com.scviewer.fire.data.WatchProgress) = ProgressSnapshot(
                p.titleId, p.name, p.slug, p.type, p.season, p.episode,
                p.episodeName, p.positionSec, p.durationSec, p.url, p.updatedAt
            )
        }
    }

    companion object {
        const val EXTRA_TITLE_ID = "title_id"
        const val EXTRA_PROGRESS = "progress"
    }
}
