package com.scviewer.fire.data

import android.content.Context
import androidx.core.content.edit

/** Cache dei poster per titleId — popolata quando si visita la pagina
 *  del titolo (dove i dati sono completi) e consultata ovunque serva
 *  una copertina (Cronologia, Preferiti, Riprendi, watch page). */
object PosterCache {

    private const val PREFS = "sc_poster_cache"

    fun put(context: Context, titleId: Int, url: String) {
        if (url.isBlank()) return
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(titleId.toString(), url).apply()
    }

    fun get(context: Context, titleId: Int): String? =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(titleId.toString(), null)
}
