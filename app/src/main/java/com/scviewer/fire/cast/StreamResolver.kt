package com.scviewer.fire.cast

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.core.content.edit
import com.scviewer.fire.data.SiteCatalog
import com.scviewer.fire.web.SCWebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * StreamResolver (C2 — percorso 1, Default Media Receiver): risolve l'URL
 * MASTER m3u8 riproducibile dal Chromecast a partire da titleId/episodeId.
 *
 * CATENA STREAM (piano §3.3, verificata):
 *  1. GET {domain}/it/watch/<titleId>?e=<episodeId> (fetchProps con i cookie
 *     della sessione sito + UA desktop) → props.embedUrl = wrapper
 *     /it/iframe/<id>?episode_id=...&next_episode=1;
 *  2. GET wrapper (Referer OBBLIGATORIO) → src dell'iframe vixcloud con
 *     TOKEN MONOUSO (~10s): va consumato SUBITO, nessun delay intermedio;
 *  3. WebView headless (64dp, invisibile, host dedicato in activity_sctv.xml)
 *     carica l'embed vixcloud con header Referer: il bundle JS del player,
 *     mentre configura la riproduzione, imposta `window.masterPlaylist`
 *     con l'URL master + il token a 60 GIORNI (verificato con curl
 *     2026-11-21: masterPlaylist è server-rendered inline nell'HTML embed);
 *  4. master URL = url + ?token=<t>&expires=<e> + [&h=1 se il probe passa]
 *     + &lang=<lingua corrente>.
 *
 * h=1 (FHD) — COMPORTAMENTO VERIFICATO (curl, 2026-11-21): la master CON
 * h=1 risponde 403 da qualunque client non-abilitato (l'embed renderizza
 * `window.canPlayFHD = false` e il bundle del sito aggiunge h=1 SOLO se la
 * query dell'embed la porta). SENZA h=1 la master risponde 200 e contiene
 * 480p+720p (1080p assente). Perciò qui h=1 NON viene appeso alla cieca:
 * [probeH1] lo TENTA e lo tiene SOLO se la master+h=1 risponde 200 con
 * varianti (#EXT-X-STREAM-INF); altrimenti fallback automatico alla master
 * senza h (sempre riproducibile, cap 720p). Il risultato del probe viene
 * cachato insieme al token. [TRY_H1] disattiva del tutto il tentativo.
 *
 * Il token master (60gg) è CACHEABILE: cache persistente (SharedPreferences)
 * per scwsId con TTL, indice titleId:episodeId → scwsId. [invalidate]
 * rimuove la voce quando il receiver rifiuta il load (token revocato).
 */
object StreamResolver {

    private const val TAG = "StreamResolver"

    /** true = tenta &h=1 con probe (fallback automatico senza h se 403). */
    const val APPEND_H1 = true

    /** TTL max della cache (il token master dura 60gg; sotto a 30gg si
     *  ri-risolve comunque per sicurezza). */
    private const val CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000

    /** Margine di sicurezza sull'expiry del token (10 min). */
    private const val EXPIRY_MARGIN_MS = 600_000L

    /** Finestra totale di polling della WebView headless (~12s, piano §3.3). */
    private const val WEBVIEW_POLL_TIMEOUT_MS = 12_000L
    private const val WEBVIEW_POLL_INTERVAL_MS = 300L

    private const val PREFS = "sc_cast_stream_cache"
    private const val KEY_ENTRIES = "entries"   // scwsId -> {url, expires, savedAt}
    private const val KEY_INDEX = "index"       // "w<titleId>:e<episodeId>" -> scwsId

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Client HTTP per watch/wrapper: timeout stretti, il token monouso del
     *  wrapper scade in ~10s. */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    // -- Cache -----------------------------------------------------------------

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readEntries(context: Context): MutableMap<String, JSONObject> {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return mutableMapOf()
        val out = runCatching { JSONObject(raw) }.getOrNull() ?: return mutableMapOf()
        val map = mutableMapOf<String, JSONObject>()
        for (k in out.keys()) map[k] = out.optJSONObject(k) ?: continue
        return map
    }

    private fun readIndex(context: Context): MutableMap<String, String> {
        val raw = prefs(context).getString(KEY_INDEX, null) ?: return mutableMapOf()
        val out = runCatching { JSONObject(raw) }.getOrNull() ?: return mutableMapOf()
        val map = mutableMapOf<String, String>()
        for (k in out.keys()) map[k] = out.optString(k)
        return map
    }

    private fun persist(context: Context, entries: Map<String, JSONObject>, index: Map<String, String>) {
        prefs(context).edit {
            putString(KEY_ENTRIES, JSONObject(entries).toString())
            putString(KEY_INDEX, JSONObject(index).toString())
        }
    }

    /** Cache valida? (TTL + expiry token). */
    private fun isFresh(entry: JSONObject, now: Long): Boolean {
        val savedAt = entry.optLong("savedAt", 0L)
        val expiresSec = entry.optLong("expires", 0L)
        val byTtl = savedAt > 0 && now - savedAt < CACHE_TTL_MS
        val byExpiry = expiresSec <= 0 || expiresSec * 1000 - EXPIRY_MARGIN_MS > now
        return byTtl && byExpiry
    }

    /** Invalida la cache di una puntata (loadItem fallito → token morto). */
    fun invalidate(context: Context, titleId: Int, episodeId: Int) {
        val entries = readEntries(context)
        val index = readIndex(context)
        val idxKey = "w$titleId:e$episodeId"
        val scws = index.remove(idxKey)
        if (scws != null) entries.remove(scws)
        persist(context, entries, index)
        Log.i(TAG, "cache invalidata per w$titleId:e$episodeId (scws=$scws)")
    }

    /**
     * PROBE h=1 (verifica 2026-11-21): tenta la master CON h=1 e la tiene
     * solo se risponde 200 con varianti HLS. La CDN rifiuta h=1 con 403
     * per i client non abilitati a FHD (window.canPlayFHD=false nell'embed):
     * senza questo probe il cast partirebbe con un master morto.
     * Fetch leggera (pochi KB) — una sola volta per resolve (cache miss).
     */
    private fun probeH1(base: String): Boolean {
        val probeUrl = base + (if (base.contains('?')) '&' else '?') + "h=1"
        val ok = runCatching {
            client.newCall(
                Request.Builder().url(probeUrl)
                    .header("User-Agent", SCWebViewClient.DESKTOP_UA)
                    .header("Accept", "*/*")
                    .build()
            ).execute().use { resp ->
                resp.isSuccessful &&
                    resp.body?.string()?.contains("#EXT-X-STREAM-INF") == true
            }
        }.getOrDefault(false)
        Log.i(TAG, "probe h=1 → ${if (ok) "OK (FHD nella master)" else "403/negato: master senza h=1 (cap 720p)"}")
        return ok
    }

    // -- Risoluzione --------------------------------------------------------------

    /**
     * Risolve l'URL master finale (token + h=1 + lang). Suspend: IO per le
     * fetch, Main per la WebView headless. Null = catena fallita (errore
     * segnalato al chiamante, niente fallback silenziosi).
     */
    suspend fun resolveMasterUrl(
        context: Context,
        host: ViewGroup?,
        domain: String,
        titleId: Int,
        episodeId: Int,
        lang: String,
        forceRefresh: Boolean
    ): String? {
        val idxKey = "w$titleId:e$episodeId"
        val now = System.currentTimeMillis()

        // 0. Cache calda (token 60gg): nessuna fetch, niente WebView.
        if (!forceRefresh) {
            val scws = readIndex(context)[idxKey]
            val cached = scws?.let { readEntries(context)[it] }
            if (cached != null && isFresh(cached, now)) {
                // h1ok già validato al resolve originale: niente probe.
                val url = decorate(cached.optString("url"), lang, cached.optBoolean("h1ok"))
                Log.i(TAG, "cache hit w$titleId:e$episodeId → ${url.substringBefore('&')}")
                return url
            }
        }

        val dom = domain.trimEnd('/')

        // 1. Watch page → props.embedUrl (wrapper /it/iframe). Cookie della
        //    WebView: senza la sessione del sito la watch può rimandare alla
        //    pagina titolo (stesso schema di PlayerActivity).
        val cookie = withContext(Dispatchers.IO) {
            runCatching { CookieManager.getInstance().getCookie(dom) }.getOrNull()
        }
        val watchUrl = buildString {
            append(dom).append("/it/watch/").append(titleId)
            if (episodeId > 0) append("?e=").append(episodeId)
        }
        val wrapperUrl = withContext(Dispatchers.IO) {
            val props = runCatching {
                SiteCatalog.fetchProps(watchUrl, dom, cookie)
            }.getOrNull()
            props?.optString("embedUrl")?.takeIf { it.isNotBlank() }
                ?.let { if (it.startsWith("http")) it else dom + it }
        } ?: run {
            Log.w(TAG, "resolve $watchUrl: props.embedUrl assente (watch rimandata?)")
            return null
        }

        // 2. Wrapper → src vixcloud (token MONOUSO ~10s: fetch immediata,
        //    Referer obbligatorio — 403 senza).
        val embedSrc = withContext(Dispatchers.IO) { fetchWrapperVixSrc(wrapperUrl, dom, cookie) }
            ?: run {
                Log.w(TAG, "resolve: src vixcloud non trovata nel wrapper $wrapperUrl")
                return null
            }
        Log.i(TAG, "embedSrc: ${embedSrc.substringBefore('&')}...")

        // 3. WebView headless → window.masterPlaylist (token 60gg).
        val master = extractMasterViaWebView(host, embedSrc, dom + "/")
            ?: run {
                Log.w(TAG, "resolve: window.masterPlaylist non comparsa in ${WEBVIEW_POLL_TIMEOUT_MS}ms")
                return null
            }
        val base = buildMasterBase(master)
            ?: run {
                Log.w(TAG, "resolve: masterPlaylist senza url/token: $master")
                return null
            }

        // 4. PROBE h=1 + cache per scwsId (fallback "w:id:e" se il bundle
        //    non lo espone).
        val h1ok = APPEND_H1 && probeH1(base)
        val scwsId = Regex("(?:scws_?id|scws)=([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE)
            .find(embedSrc)?.groupValues?.get(1)
            ?: Regex("(?:scws_?id|scws)=([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE)
                .find(base)?.groupValues?.get(1)
            ?: idxKey
        val entries = readEntries(context)
        val index = readIndex(context)
        entries[scwsId] = JSONObject()
            .put("url", base)
            .put("expires", master.optJSONObject("params")?.let { p ->
                (p.optString("expires").toLongOrNull() ?: p.optLong("expires", 0L))
            } ?: 0L)
            .put("h1ok", h1ok)
            .put("savedAt", System.currentTimeMillis())
        index[idxKey] = scwsId
        persist(context, entries, index)

        val finalUrl = decorate(base, lang, h1ok)
        Log.i(TAG, "master risolta per w$titleId:e$episodeId (scws=$scwsId, h1=$h1ok)")
        return finalUrl
    }

    /** GET del wrapper /it/iframe con Referer+UA desktop+cookie, estrazione
     *  dell'src dell'iframe vixcloud (stesso pattern di PlayerActivity). */
    private fun fetchWrapperVixSrc(wrapperUrl: String, domain: String, cookie: String?): String? {
        return runCatching {
            val b = Request.Builder().url(wrapperUrl)
                .header("User-Agent", SCWebViewClient.DESKTOP_UA)
                .header("Accept", "text/html")
                .header("Referer", domain.trimEnd('/') + "/")
            cookie?.takeIf { it.isNotBlank() }?.let { b.header("Cookie", it) }
            client.newCall(b.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.i(TAG, "wrapper fetch HTTP ${resp.code} ($wrapperUrl)")
                    return@use null
                }
                val html = resp.body?.string() ?: return@use null
                Regex("src=\"(https://vixcloud[^\"]+)\"").find(html)
                    ?.groupValues?.get(1)
                    ?.replace("&amp;", "&")
            }
        }.onFailure { Log.w(TAG, "wrapper fetch failed", it) }.getOrNull()
    }

    /**
     * WebView headless (64dp, invisibile): carica l'embed vixcloud con il
     * Referer del sito e fa polling di window.masterPlaylist. La WebView è
     * creata dentro [host] (container dedicato del layout, INVISIBLE — il
     * layout/timer JS deve girare) e distrutta appena estratto il valore o
     * scaduto il timeout. SOLO thread Main.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractMasterViaWebView(
        host: ViewGroup?,
        embedSrc: String,
        referer: String
    ): JSONObject? = withContext(Dispatchers.Main) {
        if (host == null) {
            Log.w(TAG, "WebView headless: host assente (activity staccata)")
            return@withContext null
        }

        val startedAt = System.currentTimeMillis()
        var web: WebView? = null
        try {
            web = WebView(host.context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.useWideViewPort = false
                // UA desktop: parte del fingerprint della sessione sito —
                // l'embed vixcloud si comporta come nel player web.
                settings.userAgentString = SCWebViewClient.DESKTOP_UA
            }
            host.addView(
                web,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            // Referer OBBLIGATORIO (403 senza) — stesso schema PlayerActivity.
            web.loadUrl(embedSrc, mapOf("Referer" to referer))

            // Polling: window.masterPlaylist ? stringify : null, ogni 300ms.
            while (System.currentTimeMillis() - startedAt < WEBVIEW_POLL_TIMEOUT_MS) {
                val raw = evalJson(web, "window.masterPlaylist?JSON.stringify(window.masterPlaylist):null")
                if (raw != null) {
                    val parsed = runCatching { JSONObject(raw) }.getOrNull()
                    if (parsed != null && parsed.optString("url").isNotBlank()) return@withContext parsed
                }
                delay(WEBVIEW_POLL_INTERVAL_MS)
            }
            null
        } finally {
            // Smontaggio pulito: about:blank ferma media/JS pendenti, poi
            // destroy (la WebView tiene il context dell'activity — mai leak).
            web?.let {
                runCatching { it.loadUrl("about:blank") }
                runCatching { (it.parent as? ViewGroup)?.removeView(it) }
                runCatching { it.destroy() }
            }
        }
    }

    /** evaluateJavascript → stringa decodificata (null se null/errore). */
    private suspend fun evalJson(web: WebView, expr: String): String? =
        suspendCancellableCoroutine { cont ->
            web.evaluateJavascript(expr) { raw ->
                val decoded = if (raw.isNullOrBlank() || raw == "null") null
                else runCatching { json.decodeFromString<String>(raw.trim()) }.getOrNull()
                cont.resume(decoded)
            }
        }

    /** Da window.masterPlaylist all'URL "base": url + token + expires. Se il
     *  bundle espone già il token dentro l'URL, l'URL torna invariato. */
    private fun buildMasterBase(mp: JSONObject): String? {
        val url = mp.optString("url").takeIf { it.isNotBlank() } ?: return null
        val params = mp.optJSONObject("params")
        val token = params?.optString("token")?.takeIf { it.isNotBlank() }
        val expires = params?.optString("expires")?.takeIf { it.isNotBlank() }
        if (url.contains("token=") || token == null) return url
        val sep = if (url.contains('?')) '&' else '?'
        return buildString {
            append(url).append(sep).append("token=").append(token)
            if (!expires.isNullOrBlank()) append("&expires=").append(expires)
        }
    }

    /** Appende i parametri di riproduzione del receiver: h=1 SOLO se il
     *  probe l'ha validato (FHD dove permesso) e lang (lingua audio). */
    fun decorate(base: String, lang: String, useH1: Boolean): String {
        var out = base
        if (useH1 && !out.contains("h=1")) {
            out += (if (out.contains('?')) '&' else '?') + "h=1"
        }
        if (lang.isNotBlank() && !out.contains("lang=")) {
            out += (if (out.contains('?')) '&' else '?') + "lang=" + lang
        }
        return out
    }
}
