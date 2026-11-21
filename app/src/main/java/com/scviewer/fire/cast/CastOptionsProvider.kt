package com.scviewer.fire.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
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
 */
class CastOptionsProvider : OptionsProvider {

    override fun getCastOptions(appContext: Context): CastOptions {
        val appId = SettingsStore.getCastAppId(appContext).trim()
            .takeIf { it.isNotBlank() }
            ?: CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
        return CastOptions.Builder()
            .setReceiverApplicationId(appId)
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()
    }

    /** Nessun provider di sessioni extra: serve solo il CastSession standard. */
    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
