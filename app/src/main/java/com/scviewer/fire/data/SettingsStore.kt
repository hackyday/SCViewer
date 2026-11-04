package com.scviewer.fire.data

import android.content.Context

/**
 * Persisted app settings (SharedPreferences-backed).
 *
 * Currently stores the virtual cursor speed used by
 * [com.scviewer.fire.ui.CursorController], editable from the
 * "Impostazioni" entry in the hamburger menu.
 */
object SettingsStore {

    private const val PREFS = "sc_settings"
    private const val KEY_CURSOR_SPEED = "cursor_speed"

    const val MIN_SPEED = 1
    const val MAX_SPEED = 10
    const val DEFAULT_SPEED = 5

    fun getCursorSpeed(context: Context): Int {
        val p = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getInt(KEY_CURSOR_SPEED, DEFAULT_SPEED).coerceIn(MIN_SPEED, MAX_SPEED)
    }

    fun setCursorSpeed(context: Context, value: Int) {
        val p = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putInt(KEY_CURSOR_SPEED, value.coerceIn(MIN_SPEED, MAX_SPEED)).apply()
    }

    // -- Qualità massima (HlsRewriter) ---------------------------------------

    private const val KEY_QUALITY = "quality_cap"
    /** Stop del selettore: 0 = Auto (1080p), 1 = 720p, 2 = 480p. */
    const val QUALITY_AUTO = 0
    const val QUALITY_720 = 1
    const val QUALITY_480 = 2

    fun getQualityStop(context: Context): Int =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_QUALITY, QUALITY_AUTO)

    fun setQualityStop(context: Context, stop: Int) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_QUALITY, stop.coerceIn(0, 2)).apply()
    }

    /** Altezza massima passata ad HlsRewriter (pixel verticali). */
    fun qualityCapHeight(context: Context): Int = when (getQualityStop(context)) {
        QUALITY_720 -> 720
        QUALITY_480 -> 480
        else -> 1080
    }
}
