package com.scviewer.fire.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.MediaIntentReceiver
import com.google.android.gms.cast.framework.media.NotificationOptions
import com.scviewer.fire.data.SettingsStore

/**
 * OptionsProvider del cast (C1): chiamato dal framework Google Play services
 * (referenziato nel manifest via meta-data OPTIONS_PROVIDER_CLASS_NAME).
 *
 * Receiver App ID: se l'utente ha impostato un App ID di receiver CUSTOM
 * (SettingsStore.castAppId — percorso 2, receiver SCtv registrato) viene
 * usato quello, altrimenti il Default Media Receiver di Google (percorso 1,
 * riproduzione HLS via master playlist).
 *
 * setStopReceiverApplicationWhenEndingSession(true): quando la sessione
 * termina il receiver si chiude — il Default receiver torna alla ambient e
 * il receiver custom non resta appeso con il player morto.
 *
 * NOTIFICA CAST (via CastMediaOptions.setNotificationOptions — nella 22
 * NotificationOptions NON si imposta direttamente su CastOptions.Builder):
 * il framework mostra la notifica di sistema SOLO mentre c'è una
 * riproduzione remota attiva (MediaStatus → MediaNotificationManager, la
 * gestisce da solo) e la rimuove a sessione finita. Il tap sul body apre
 * RemoteActivity (setTargetActivityClassName). Le azioni sono le stringhe
 * di MediaIntentReceiver (nella 22 NON esiste NotificationOptions.Action):
 *  - REWIND / FORWARD: skip ±SKIP_STEP_THIRTY_SECONDS_IN_MS (30s, default
 *    del receiver);
 *  - TOGGLE_PLAYBACK: play/pausa;
 *  - STOP_CASTING: endSession con stop — stesso comportamento del bottone
 *    "Disconnetti" del telecomando.
 * compatActionIndices = indici (0,1,2) mostrati nella vista compatta:
 * rew · play/pause · forward.
 *
 * NOTA: il percorso FAKE (debug, SctvCastController.FakePlayer) non ha una
 * CastSession vera → la notifica del framework non può partire: lì il
 * controller mostra una notifica manuale (vedi SctvCastController).
 * POST_NOTIFICATIONS (Android 13+) è richiesto a runtime in RemoteActivity.
 */
class CastOptionsProvider : OptionsProvider {

    override fun getCastOptions(appContext: Context): CastOptions {
        val appId = SettingsStore.getCastAppId(appContext).trim()
            .takeIf { it.isNotBlank() }
            ?: CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
        val notificationOptions = NotificationOptions.Builder()
            .setActions(
                listOf(
                    MediaIntentReceiver.ACTION_REWIND,
                    MediaIntentReceiver.ACTION_TOGGLE_PLAYBACK,
                    MediaIntentReceiver.ACTION_FORWARD,
                    MediaIntentReceiver.ACTION_STOP_CASTING
                ),
                intArrayOf(0, 1, 2) // vista compatta: rew · play/pause · forward
            )
            .setSkipStepMs(NotificationOptions.SKIP_STEP_THIRTY_SECONDS_IN_MS)
            .setTargetActivityClassName(RemoteActivity::class.java.name)
            .build()
        return CastOptions.Builder()
            .setReceiverApplicationId(appId)
            .setStopReceiverApplicationWhenEndingSession(true)
            .setCastMediaOptions(
                CastMediaOptions.Builder()
                    .setNotificationOptions(notificationOptions)
                    .build()
            )
            .build()
    }

    /** Nessun provider di sessioni extra: serve solo il CastSession standard. */
    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
