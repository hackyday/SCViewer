package com.scviewer.fire

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.scviewer.fire.data.SettingsStore

/**
 * Router del launcher (Theme.NoDisplay — non crea finestra, zero flash).
 *
 * Riceve l'intent-filter MAIN/LAUNCHER/LEANBACK_LAUNCHER (spostato da
 * MainActivity in M1) e avvia l'interfaccia primaria scelta:
 * [SctvActivity] in modalità SCtv (default) oppure [MainActivity]
 * in modalità "sito". Al ritorno dallo store il flag decide da solo.
 */
class LauncherRouterActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = if (SettingsStore.isSctvMode(this)) SctvActivity::class.java
        else MainActivity::class.java
        startActivity(Intent(this, target))
        finish()
    }
}
