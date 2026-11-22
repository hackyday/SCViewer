package com.scviewer.fire.work

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.scviewer.fire.App
import com.scviewer.fire.BuildConfig
import com.scviewer.fire.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Auto-aggiornamento dell'app, condiviso fra le due interfacce: logica
 * estratta da MainActivity (checkForUpdates / showUpdateChip /
 * startAutoUpdate / launchInstaller) senza cambiamenti di comportamento,
 * così anche l'interfaccia SCtv (SctvActivity) mantiene la funzione che
 * prima esisteva solo nella modalità sito.
 *
 * Flusso:
 *  - checkForUpdates: la proposta di un aggiornamento GIÀ TROVATO (prefs
 *    "available_tag"/"available_at") torna a ogni avvio, senza rete e senza
 *    gate — il gate 12h su "last_check" (condiviso fra le due activity: un
 *    check in una modalità copre anche l'altra) limita SOLO la fetch GET
 *    GitHub /releases?per_page=30. CANALI: ogni form factor (DeviceProfile:
 *    phone = touchscreen senza leanback, tv altrimenti) vede SOLO le release
 *    col proprio prefisso di tag — "phone-vX.Y.Z" / "tv-vX.Y.Z", numerazioni
 *    completamente indipendenti; le release legacy senza prefisso (v1.3–v1.9)
 *    non appartengono a nessun canale e non sono più proposte. Le release dei
 *    canali si pubblicano marcate PRERELEASE: /releases/latest le esclude, così
 *    anche i client VECCHI (che ancora leggono /releases/latest) non vedono
 *    mai il ramo dell'altro device. Confronto semantico dei tag — solo
 *    versioni PIÙ NUOVE (una beta con tag superiore non deve "aggiornare"
 *    v1.5.6). Motivo: Cancel nell'installer di sistema (Fire TV reale) — senza
 *    proposta persistita il gate silenziava l'app per 12 ore e l'utente
 *    percepiva "non rifà mai più l'update";
 *  - showChip: chip nativo stile bg_sctv_chip, NON focusabile (si usa solo
 *    col cursore, come tutti i chip nativi — B16), cliccabile → download;
 *  - startAutoUpdate: download in cacheDir (update-<tag>.apk), verifica
 *    dimensione + magic ZIP ("PK"), avvio del package installer via
 *    FileProvider (REQUEST_INSTALL_PACKAGES già nel manifest, cache-path
 *    già in file_paths.xml).
 *
 * Lo scope è del manager (non dell'activity): il download continua anche
 * se l'activity viene distrutta nel frattempo (es. cambio interfaccia),
 * e i toast usano applicationContext per non dipendere da un context morto.
 *
 * NOTA: MainActivity conserva la sua copia privata di queste funzioni
 * (vincolo di progetto: la modalità alternativa non si tocca); il manager
 * è pronto per essere adottato anche lì in un secondo momento.
 */
object UpdateManager {

    private const val TAG = "UpdateManager"
    /** Frequenza massima di check: 12 ore (prefs condivise fra le activity). */
    private const val CHECK_INTERVAL_MS = 12 * 3600_000L

    /** Tag dell'ultimo aggiornamento disponibile (usato dal click del chip). */
    @Volatile private var updateTag: String? = null

    /** TRUE mentre un download è in corso: click multipli sul chip no-op. */
    @Volatile private var autoUpdateRunning = false

    /** Scope proprio: sopravvive alla distruzione dell'activity host. */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val uiHandler = Handler(Looper.getMainLooper())

    /** Chip attivo (rimosso a showChip/removeChip per evitare doppioni e
     *  mantenere un riferimento alla view dell'activity corrente). */
    private var chipView: TextView? = null

    // -- Check ------------------------------------------------------------------

    /**
     * Check aggiornamento, due livelli separati:
     *
     *  1) Proposta persistita (prefs "available_tag"/"available_at", scritte
     *     da un check precedente): se il tag persistito è ancora più nuovo
     *     della versione installata il chip torna SUBITO, senza rete e senza
     *     gate — [onAvailable] con fromCache=true (il chiamante mostra solo
     *     il chip: il download parte dal click, così dopo un Cancel
     *     nell'installer la proposta resta visibile senza riaprire da sola
     *     l'installer a ogni avvio). Se il tag persistito NON è più nuovo
     *     dell'installato l'utente ha aggiornato (o la release è stata
     *     ritirata): pulizia delle chiavi, il chip non rientra.
     *  2) Fetch GitHub con gate 12h ("last_check"): il gate limita SOLO la
     *     rete, mai il livello 1. Release più nuova trovata → aggiorna le
     *     chiavi di proposta e invoca [onAvailable] con fromCache=false
     *     (chip + auto-download, comportamento invariato). Nulla di più
     *     nuovo dell'installata → pulizia delle chiavi di proposta e chip
     *     via (se era comparso da cache in questo avvio: release ritirata).
     *
     * Gate bloccato e nessuna proposta persistita: uscita silenziosa (come
     * prima del fix).
     */
    fun checkForUpdates(activity: Activity, onAvailable: (tag: String, fromCache: Boolean) -> Unit) {
        ioScope.launch {
            val prefs = activity.getSharedPreferences("scviewer_updates", Activity.MODE_PRIVATE)
            val current = "v" + BuildConfig.VERSION_NAME

            // 1) Aggiornamento già trovato in un check precedente: chip
            //    immediato a ogni avvio (prima del fix il gate 12h nascondeva
            //    la proposta fino al prossimo check: "Cancel → mai più").
            val cached = prefs.getString("available_tag", null)
            if (!cached.isNullOrBlank()) {
                if (isVersionNewer(cached, current)) {
                    uiHandler.post { onAvailable(cached, true) }
                } else {
                    // Non più nuovo dell'installato = aggiornamento completato:
                    // le chiavi di proposta non servono più (pulizia automatica).
                    prefs.edit().remove("available_tag").remove("available_at").apply()
                }
            }

            // 2) Gate 12h: solo la fetch di rete GitHub, mai la proposta sopra.
            val last = prefs.getLong("last_check", 0L)
            if (System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return@launch
            // CANALI DI RILASCIO (phone/tv): ogni form factor vede SOLO le
            // release col proprio prefisso di tag — "phone-vX.Y.Z" e
            // "tv-vX.Y.Z", versioni completamente indipendenti fra i canali.
            // La fetch è unica (/releases?per_page=30), il filtro è il
            // prefisso; il download usa il tag completo (URL canonica
            // releases/download/<tag>/app-release.apk, idente per tutti).
            // Regola di pubblicazione: l'asset DEVE chiamarsi
            // app-release.apk e i tag di un canale crescono sempre
            // (phone-v1.10.1 dopo phone-v1.10.0, ecc.).
            val channel = if (com.scviewer.fire.cast.DeviceProfile.isPhone(activity))
                CHANNEL_PHONE else CHANNEL_TV
            val latest = fetchNewestChannelRelease(channel, current)
            if (latest.isNullOrBlank()) return@launch
            // Il gate si aggiorna ad ogni fetch RIUSCITO (anche se la release
            // corrente è già la più recente): stessa semantica di MainActivity.
            prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
            // Solo versioni PIÙ NUOVE: il confronto a stringa mostrava il chip
            // anche a chi ha una versione superiore (es. "v1.5.6" offerto alla
            // pre-release 1.6.0-beta — la beta non deve essere "aggiornata").
            if (latest.isNotBlank() && isVersionNewer(latest, current)) {
                // Persisti la proposta: i prossimi avvi (anche dopo un Cancel
                // nell'installer) mostrano il chip subito via livello 1.
                prefs.edit()
                    .putString("available_tag", latest)
                    .putLong("available_at", System.currentTimeMillis())
                    .apply()
                uiHandler.post { onAvailable(latest, false) }
            } else {
                // Nessuna release più nuova della installata: l'utente ha
                // aggiornato (o release ritirata) → pulizia proposta e chip
                // via (no-op se il chip non era comparso in questo avvio).
                prefs.edit().remove("available_tag").remove("available_at").apply()
                uiHandler.post { removeChip(activity) }
            }
        }
    }

    /** Confronto semantico dei tag "vMAJ.MIN.PATCH" (estratto da MainActivity;
     *  tollerante anche ai tag di canale "phone-vX.Y.Z" / "tv-vX.Y.Z"). */
    private fun isVersionNewer(latest: String, current: String): Boolean {
        return runCatching { numsOf(latest) > numsOf(current) }.getOrDefault(false)
    }

    /** Peso numerico di un tag di versione: la parte numerica è quella dopo
     *  l'ultimo "v" del tag ("v1.10.0" → "1.10.0", "phone-v1.10.0" →
     *  "1.10.0"); suffissi extra sono ignorati nel confronto. */
    private fun numsOf(v: String): Long =
        v.substringAfterLast("v").split('.').let {
            (it.getOrNull(0)?.filter { c -> c.isDigit() }?.toLongOrNull() ?: 0L) * 1_000_000L +
            (it.getOrNull(1)?.filter { c -> c.isDigit() }?.toLongOrNull() ?: 0L) * 1_000L +
            (it.getOrNull(2)?.filter { c -> c.isDigit() }?.toLongOrNull() ?: 0L)
        }

    /** Canali di rilascio (prefisso dei tag su GitHub). */
    private const val CHANNEL_PHONE = "phone"
    private const val CHANNEL_TV = "tv"

    /** GET /releases?per_page=30 e filtro sul CANALE [channel]: ritorna il
     *  TAG COMPLETO ("phone-vX.Y.Z" / "tv-vX.Y.Z") della release più nuova
     *  di quella installata [current] ("vX.Y.Z"), null se nessuna lo è o la
     *  fetch fallisce. Le due liste di canali sono indipendenti: un canale
     *  non vede MAI i tag dell'altro (né i legacy senza prefisso, v1.3–v1.9).
     *  Il download usa il tag completo: releases/download/<tag>/app-release.apk. */
    private fun fetchNewestChannelRelease(channel: String, current: String): String? {
        return runCatching {
            val client = okhttp3.OkHttpClient.Builder()
                .callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
            client.newCall(
                okhttp3.Request.Builder()
                    .url("https://api.github.com/repos/hackyday/SCViewer/releases?per_page=30")
                    .header("Accept", "application/vnd.github+json")
                    .build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string() ?: return@use null
                val arr = org.json.JSONArray(body)
                val prefix = "$channel-"
                var best: String? = null
                for (i in 0 until arr.length()) {
                    val tag = arr.optJSONObject(i)?.optString("tag_name") ?: continue
                    if (!tag.startsWith(prefix)) continue
                    val version = tag.substringAfter(prefix)
                    if (!isVersionNewer(version, current)) continue
                    if (best == null || numsOf(version) > numsOf(best.substringAfter(prefix))) best = tag
                }
                best
            }
        }.getOrNull()
    }

    // -- Chip nativo ------------------------------------------------------------

    /**
     * Chip "Aggiornamento disponibile · <tag>" sopra il content dell'activity:
     * stesso stile del chip di MainActivity (bg_sctv_chip, testo 14f bold,
     * elevazione 10dp, NON focusabile — solo cursore/click). In SCtv viene
     * usato come banner in basso a sinistra ([bottomBanner] = true) per non
     * coprire header e logo dell'interfaccia; in modalità sito resta in alto.
     */
    fun showChip(activity: Activity, tag: String, bottomBanner: Boolean = false) {
        val content = activity.findViewById<FrameLayout>(android.R.id.content)
        // Rimuovi un chip precedente (stessa activity o precedente): uno solo.
        chipView?.let { (it.parent as? FrameLayout)?.removeView(it) }
        val density = activity.resources.displayMetrics.density
        updateTag = tag
        chipView = TextView(activity).apply {
            text = activity.getString(R.string.update_chip, tag)
            setTextColor(0xFFF5F7FA.toInt())
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setBackgroundResource(R.drawable.bg_sctv_chip)
            val padH = (16 * density).toInt()
            val padV = (9 * density).toInt()
            setPadding(padH, padV, padH, padV)
            elevation = 10f * density
            isClickable = true
            // Coerenza B16: mai focusabile — si usa solo col cursore.
            isFocusable = false
            setOnClickListener { startAutoUpdate(activity, updateTag ?: "") }
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            if (bottomBanner) Gravity.BOTTOM or Gravity.START
            else Gravity.TOP or Gravity.START
        ).apply {
            if (bottomBanner) {
                // SCtv: banner in basso, oltre la sidebar fissa (80dp) e
                // libero dal toast dell'interfaccia (centrato in basso).
                bottomMargin = (24 * density).toInt()
                marginStart = (96 * density).toInt()
            } else {
                // Stessa posizione del chip di MainActivity.
                topMargin = (20 * density).toInt()
                marginStart = (88 * density).toInt()
            }
        }
        content.addView(chipView, params)
    }

    /** Rimozione del chip (chiamata a distruzione dell'activity host per non
     *  tenere riferimenti a view di activity morte). */
    fun removeChip(activity: Activity) {
        val chip = chipView ?: return
        val content = activity.findViewById<FrameLayout>(android.R.id.content)
        if (chip.parent === content) content.removeView(chip)
        chipView = null
    }

    // -- Download + installer ---------------------------------------------------

    /**
     * Download dell'APK della release [tag] in cacheDir e avvio del package
     * installer. Click ripetuti sul chip durante il download sono no-op.
     */
    fun startAutoUpdate(activity: Activity, tag: String) {
        if (tag.isBlank()) return
        updateTag = tag
        if (autoUpdateRunning) return
        autoUpdateRunning = true
        val url = "https://github.com/hackyday/SCViewer/releases/download/$tag/app-release.apk"
        toast(activity.getString(R.string.update_downloading, tag))
        ioScope.launch {
            val ok = runCatching {
                val client = okhttp3.OkHttpClient.Builder()
                    .callTimeout(90, java.util.concurrent.TimeUnit.SECONDS).build()
                client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val dst = java.io.File(activity.applicationContext.cacheDir, "update-$tag.apk")
                    dst.parentFile?.mkdirs()
                    dst.outputStream().use { out -> resp.body!!.byteStream().copyTo(out) }
                    // Sanity check: dimensione plausibile + magic ZIP ("PK").
                    dst.length() > 100_000L &&
                        dst.inputStream().use { ins -> ins.read() == 'P'.code && ins.read() == 'K'.code }
                }
            }.getOrDefault(false)
            autoUpdateRunning = false
            if (!ok) {
                toast(activity.getString(R.string.update_download_failed))
                return@launch
            }
            uiHandler.post {
                toast(activity.getString(R.string.update_ready))
                launchInstaller(activity, tag)
            }
        }
    }

    /** Install via package installer di sistema sull'APK scaricato
     *  (FileProvider "$packageName.fileprovider", cache-path già configurato). */
    private fun launchInstaller(activity: Activity, tag: String) {
        runCatching {
            val apk = java.io.File(activity.applicationContext.cacheDir, "update-$tag.apk")
            val uri = androidx.core.content.FileProvider.getUriForFile(
                activity, activity.packageName + ".fileprovider", apk
            )
            activity.startActivity(
                android.content.Intent(android.content.Intent.ACTION_INSTALL_PACKAGE).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                }
            )
        }.onFailure { Log.w(TAG, "installer failed", it) }
    }

    /** Toast su thread UI con applicationContext (robusto anche se l'activity
     *  host è già distrutta quando arriva la fine del download). */
    private fun toast(message: String) {
        val app = App.instance.applicationContext
        uiHandler.post {
            Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
        }
    }
}
