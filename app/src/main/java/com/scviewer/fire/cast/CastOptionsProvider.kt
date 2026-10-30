package com.scviewer.fire.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions

/**
 * Configurazione Google Cast per l'app.
 *
 * Usa il **Default Media Receiver** di Google: il Chromecast riproduce da
 * sé il flusso HLS (playlist vixcloud con token) con il suo player nativo —
 * non serve registrare un receiver custom né pubblicare nulla.
 * Il telefono resta il telecomando (CastRemoteActivity) e continua a
 * salvare la posizione di visione in ProgressStore, così "Riprendi" è
 * sincronizzato tra telefono e Fire TV.
 */
class CastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions {
        val notificationOptions = NotificationOptions.Builder()
            .setTargetActivityClassName(CastRemoteActivity::class.java.name)
            .build()
        val mediaOptions = CastMediaOptions.Builder()
            .setNotificationOptions(notificationOptions)
            .setExpandedControllerActivityClassName(CastRemoteActivity::class.java.name)
            .build()

        return CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setCastMediaOptions(mediaOptions)
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
