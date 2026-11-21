package com.scviewer.fire.cast

import android.content.Context
import android.content.pm.PackageManager

/**
 * Profilo del device per il cast (C1): il Chromecast esiste SOLO in
 * "phone mode" — un telefono/touchscreen CON Google Play services. Fire TV
 * e box leanback restano sulla strada nativa (PlayerActivity direct) e NON
 * devono istanziare alcun codice cast.
 *
 * Rilevamento: LEANBACK assente + TOUCHSCREEN presente = telefono. Il
 * doppio check evita falsi positivi (alcuni tablet hanno touchscreen ma
 * anche profili TV; i Fire TV dichiarano leanback anche con touchscreen).
 */
object DeviceProfile {

    /** true su telefono/touchscreen senza profilo TV: lì il cast si abilita. */
    fun isPhone(ctx: Context): Boolean {
        val pm = ctx.packageManager
        val hasLeanback = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val hasTouchscreen = pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        return !hasLeanback && hasTouchscreen
    }
}
