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

    // -- Modalità interfaccia primaria (M1) -----------------------------------

    private const val KEY_UI_MODE = "ui_mode"

    /** "sctv" = nuova interfaccia nativa SCtv (default); "site" = il sito in
     *  WebView gestito da MainActivity. Decide il launcher (router) e le
     *  sezioni visibili nel SettingsDialog. */
    const val UI_MODE_SCTV = "sctv"
    const val UI_MODE_SITE = "site"

    fun getUiMode(context: Context): String =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_UI_MODE, UI_MODE_SCTV) ?: UI_MODE_SCTV

    fun setUiMode(context: Context, mode: String) {
        val value = if (mode == UI_MODE_SITE) UI_MODE_SITE else UI_MODE_SCTV
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_UI_MODE, value).apply()
    }

    fun isSctvMode(context: Context): Boolean = getUiMode(context) == UI_MODE_SCTV

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

    // -- Navigazione sito: puntatore o D-pad ----------------------------------

    private const val KEY_SITE_POINTER = "site_pointer_mode"

    /** true = il sito si naviga col puntatore (mouse); false = navigazione
     *  spaziale D-pad iniettata (default). Nel player si usa sempre il
     *  puntatore (i controlli del sito stanno in un iframe). */
    fun getSitePointerMode(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SITE_POINTER, false)

    fun setSitePointerMode(context: Context, value: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SITE_POINTER, value).apply()
    }

    // -- UA alternativo (anti-blocco Cloudflare) ------------------------------

    private const val KEY_ALTERNATE_UA = "alternate_ua"

    /** true = usa lo UA di Chrome su Android invece del desktop Chrome.
     *  Cambia fingerprint davanti a Cloudflare (issue #1: traffico da TV
     *  bloccato, da telefono no) a costo del layout mobile del sito. */
    fun getAlternateUa(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ALTERNATE_UA, false)

    fun setAlternateUa(context: Context, value: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ALTERNATE_UA, value).apply()
    }

    // -- Lingua audio del player SCtv (M4b) -----------------------------------

    private const val KEY_SCTV_AUDIO_LANG = "sctv_audio_lang"

    /** Parametro `lang` dell'embed vixcloud: "it" (default del sito) o "en"
     *  (dove il titolo ha una traccia inglese). Gli sottotitoli NON hanno un
     *  parametro equivalente (bundle vixcloud verificato: l'unico parametro
     *  gestito è lang) — restano nel menu del player web. */
    fun getSctvAudioLang(context: Context): String =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SCTV_AUDIO_LANG, "it") ?: "it"

    fun setSctvAudioLang(context: Context, value: String) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SCTV_AUDIO_LANG, if (value == "en") "en" else "it").apply()
    }

    // -- Cast Chromecast (C1, solo phone mode) ---------------------------------

    private const val KEY_CAST_APP_ID = "cast_app_id"

    /** Receiver App ID del Cast CUSTOM (percorso 2): l'utente registra il
     *  receiver SCtv su Google Cast SDK Developer Console e incolla qui l'ID
     *  (es. "ABCD1234"). Vuoto = Default Media Receiver di Google (percorso 1). */
    fun getCastAppId(context: Context): String =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CAST_APP_ID, "") ?: ""

    fun setCastAppId(context: Context, value: String) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_CAST_APP_ID, value.trim()).apply()
    }

    private const val KEY_CAST_FAKE = "cast_fake"

    /** DEBUG ONLY (honored SOLO se BuildConfig.DEBUG): "cast finto" — un
     *  RemoteMediaClient stub locale per provare il telecomando RemoteActivity
     *  senza un Chromecast fisico. Attivabile dal SettingsDialog (voce visibile
     *  solo nei build debug) e via long-press sul MediaRouteButton. */
    fun getCastFake(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_CAST_FAKE, false)

    fun setCastFake(context: Context, value: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CAST_FAKE, value).apply()
    }
}
