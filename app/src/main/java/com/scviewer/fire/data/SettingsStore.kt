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
}
