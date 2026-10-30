package com.scviewer.fire.network

import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * Forza la riproduzione in 1080p (FHD) sul player vixcloud.
 *
 * COME FUNZIONA (verificato su stream reale, vedi test nel README):
 *  - Il player chiede la master playlist (`/playlist/<id>?token=…&expires=…`).
 *    Senza il parametro `h=1` la CDN non include la variante 1920x1080:
 *    "Default" si ferma a 720p.
 *  - Con `h=1` la master playlist contiene 480p + 720p + **1080p (4.5 Mbps)**.
 *  - Questo interceptor:
 *      1. aggiunge `h=1` alla richiesta master (token invariato — è il
 *         comportamento del player quando `canPlayFHD=true`, qui forzato);
 *      2. riscrive la master tenendo SOLO la variante migliore fino al 1080p
 *         (esatta 1920x1080 se c'è, altrimenti la più alta disponibile — mai
 *         sopra il 1080p per non rompere i vecchi stick);
 *      3. il player, avendo una sola variante, DEVE riprodurre quella:
 *         niente più "Default" che scende a 720p.
 *  - Le media playlist e i segmenti passano INVARIATI (return null): il
 *    player li carica direttamente dalla CDN, chiavi AES incluse.
 *
 * SICUREZZA DI FUNZIONAMENTO: qualunque errore (timeout, 4xx/5xx, formato
 * inatteso) => return null e la WebView carica l'URL originale senza
 * modifiche. Il worst case è la riproduzione com'era prima, mai bloccata.
 */
object HlsRewriter {

    private const val TAG = "HlsRewriter"
    private const val MAX_HEIGHT = 1080

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** Cache brevissima dei manifest riscritti: hls.js richiede la master
     *  più volte di seguito; evitiamo rifetch inutili. url -> (body, ts). */
    private val cache = ConcurrentHashMap<String, Pair<String, Long>>()
    private const val CACHE_TTL_MS = 60_000L

    /** True per le richieste di playlist HLS (master o media). */
    fun isPlaylistUrl(url: String): Boolean {
        val u = url.substringBefore('#').lowercase()
        return u.contains(".m3u8") || u.contains("/playlist/")
    }

    /** Le media playlist vixcloud portano `type=video|audio|subtitle`: non si
     *  toccano (e non si scaricano nemmeno per verificarlo — possono pesare
     *  centinaia di KB). Solo la MASTER viene intercettata. */
    fun isMediaPlaylistUrl(url: String): Boolean {
        val u = url.substringBefore('#').lowercase()
        return u.contains("type=video") || u.contains("type=audio") || u.contains("type=subtitle")
    }

    /**
     * Punto d'ingresso da WebViewClient.shouldInterceptRequest.
     * Restituisce la risposta riscritta, oppure null per "lascia passare".
     */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        if (!isPlaylistUrl(url)) return null
        if (isMediaPlaylistUrl(url)) return null   // segmenti/media: mai toccati
        return try {
            val body = rewrittenMaster(url, request) ?: return null
            val resp = WebResourceResponse(
                "application/vnd.apple.mpegurl", "utf-8", body.byteInputStream()
            )
            // CORS: l'iframe del player può fare fetch/XHR verso la playlist;
            // quando serviamo noi il body siamo noi a dover autorizzare.
            resp.responseHeaders = mapOf(
                "Access-Control-Allow-Origin" to
                        (request.requestHeaders["Origin"]?.takeIf { it.isNotBlank() } ?: "*"),
                "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
                "Access-Control-Allow-Headers" to "*",
                "Cache-Control" to "no-store"
            )
            resp
        } catch (t: Throwable) {
            Log.w(TAG, "rewrite skipped for $url: ${t.message}")
            null
        }
    }

    /** Fattoriale puro (anche per test): scarica, sblocca h=1, scegli variante. */
    fun rewrittenMaster(url: String, request: WebResourceRequest?): String? {
        val now = System.currentTimeMillis()
        cache.entries.removeAll { now - it.value.second > CACHE_TTL_MS }
        val cached = cache[url]
        if (cached != null && now - cached.second <= CACHE_TTL_MS) return cached.first

        val original = fetch(url, request) ?: return null
        if (!original.contains("#EXT-X-STREAM-INF")) return null   // media playlist: passa oltre

        val unlocked = unlockedVariantUrl(url)?.let { fetch(it, request) }
        val source = if (unlocked != null && hasStreamInf(unlocked)) unlocked else original

        val rewritten = keepBestVariant(source, maxHeight = MAX_HEIGHT) ?: return null
        cache[url] = rewritten to now
        Log.i(TAG, "master playlist riscritta: 1 variante (<= ${MAX_HEIGHT}p) su ${url.substringBefore('?')}")
        return rewritten
    }

    /** URL "sbloccato": aggiunge h=1 (e b=1 mai rimosso) se c'è un token e
     *  h non è già presente. Senza token non si tocca nulla. */
    private fun unlockedVariantUrl(url: String): String? {
        if (!url.contains("token=")) return null
        if (url.contains("h=1")) return null
        val sep = if (url.contains('?')) '&' else '?'
        return "$url${sep}h=1"
    }

    private fun hasStreamInf(text: String) = text.contains("#EXT-X-STREAM-INF")

    private data class Variant(val height: Int, val attrLine: String, val uri: String)

    /**
     * Tengo SOLO la variante con l'altezza massima <= [maxHeight]; se nessuna
     * rispetta il vincolo (solo 4K? unlikely) tengo la più bassa per sicurezza.
     * Mantiene intatti gli #EXT-X-MEDIA (audio/sottotitoli referenziati).
     */
    fun keepBestVariant(manifest: String, maxHeight: Int): String? {
        val lines = manifest.lines()

        val variants = ArrayList<Variant>()
        val header = StringBuilder()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> {
                    // la URI è la riga successiva non-commento
                    var j = i + 1
                    while (j < lines.size && (lines[j].isBlank() || lines[j].startsWith("#"))) j++
                    if (j < lines.size) {
                        val h = Regex("RESOLUTION=(\\d+)x(\\d+)").find(line)
                            ?.groupValues?.get(2)?.toIntOrNull() ?: 0
                        variants.add(Variant(h, line, lines[j].trim()))
                        i = j   // salta la URI (ri-aggiunta sotto solo per la best)
                    }
                }
                line.startsWith("#EXT-X-I-FRAME-STREAM-INF") ||
                        line.startsWith("#EXT-X-SESSION") -> { /* scartate */ }
                line.startsWith("#") -> header.append(line).append('\n')   // #EXTM3U, #EXT-X-MEDIA, …
                // righe bare (URI) fuori posto: scartate
            }
            i++
        }
        if (variants.isEmpty()) return null

        val eligible = variants.filter { it.height in 1..maxHeight }
        val best = eligible.maxByOrNull { it.height }
            ?: variants.minByOrNull { it.height }   // solo varianti > maxHeight: la più leggera
            ?: return null

        return header.append(best.attrLine).append('\n').append(best.uri).append('\n').toString()
    }

    /** GET con gli header giusti (UA desktop, Referer/Origin, cookie WebView). */
    private fun fetch(url: String, request: WebResourceRequest?): String? {
        val b = Request.Builder().url(url)
            .header("Accept", "*/*")
            .header("Accept-Language", "it-IT,it;q=0.9,en;q=0.8")

        val reqHeaders = request?.requestHeaders ?: emptyMap()
        reqHeaders["User-Agent"]?.let { b.header("User-Agent", it) }
        reqHeaders["Referer"]?.let { b.header("Referer", it) }
        reqHeaders["Origin"]?.let { b.header("Origin", it) }
        // Cookie della WebView per questo dominio (alcuni edge li vogliono)
        runCatching {
            CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let {
                b.header("Cookie", it)
            }
        }

        client.newCall(b.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.i(TAG, "playlist fetch HTTP ${resp.code}")
                return null
            }
            return resp.body?.string()
        }
    }
}
