package com.scviewer.fire.data

import android.content.Context
import android.util.Log
import com.scviewer.fire.network.DomainFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Owns the "current streamingcommunity domain" used by the WebView.
 *
 * VibraVid-style behaviour:
 *   1. On startup, try to load cached domains.json from internal storage.
 *      If missing, copy the bundled asset domains.json (last-known-good URL,
 *      aggiornato a ogni release sull'upstream AstraeLabs).
 *   2. Schedule [DomainFetcher.fetchOnline] to run at startup and every
 *      30 minutes (see [com.scviewer.fire.work.DomainRefreshScheduler]).
 *   3. Result is written to domains.json on disk and exposed via [current].
 *   4. If GitHub is unreachable, the local cached copy is used as fallback.
 *
 * The class is intentionally a small singleton — there is one process and one
 * WebView, so a shared in-memory cache is sufficient.
 */
class DomainRepository private constructor(
    private val appContext: Context
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val cacheFile: File = File(appContext.filesDir, "domains.json")

    private val _current = MutableStateFlow<DomainsConfig?>(null)
    val current: StateFlow<DomainsConfig?> = _current.asStateFlow()

    init {
        _current.value = loadFromDisk() ?: loadFromAsset()
    }

    fun currentDomain(): String {
        return _current.value?.primary()
            ?: FALLBACK_DOMAIN
    }

    // -- Mirror / fallback ----------------------------------------------------
    //
    // Quando Cloudflare blocca il primario (403/503/429, o pagina di blocco
    // anti-bot) serve un altro dominio su cui provare, NON lo stesso URL.
    // I mirror noti sono in ordine dal più recente al più storico; il set
    // "failed" è volutamente in memoria (un processo nuovo riprova tutto).

    /** Mirror noti del sito, dal più recente al più vecchio. */
    private val KNOWN_MIRRORS = listOf(
        "https://streamingcommunityz.nexus/",
        "https://streamingcommunityz.boo/",
        "https://streamingcommunityz.day/",
        "https://streamingcommunityz.build/"
    )

    private val failedDomains = LinkedHashSet<String>()

    /** Tutti i candidati: primario (upstream/cache) + mirror noti, senza duplicati. */
    private fun candidates(): List<String> {
        val out = ArrayList<String>()
        currentDomain().let { if (it.isNotBlank()) out.add(it) }
        for (m in KNOWN_MIRRORS) if (out.none { it.equals(m, ignoreCase = true) }) out.add(m)
        return out
    }

    /** Segna un dominio come fallito: il prossimo load passerà al mirror dopo. */
    fun markDomainFailed(url: String?) {
        val host = url?.let { runCatching { java.net.URI(it).host }.getOrNull() } ?: return
        for (c in candidates()) {
            if (runCatching { java.net.URI(c).host }.getOrNull() == host) {
                failedDomains.add(c)
                Log.w("DomainRepository", "domain failed -> $c (${failedDomains.size} in fail set)")
            }
        }
    }

    /** Il miglior URL ancora da provare: primo candidato non segnato fallito,
     *  oppure — se TUTTI sono falliti — comunque il primario (meglio di niente). */
    fun loadCandidate(): String =
        candidates().firstOrNull { it !in failedDomains } ?: currentDomain()

    /**
     * Triggers an online fetch. Succeeds only when a fresh config was really
     * pulled (and persisted); when GitHub is unreachable the result is a
     * Failure and the cached value stays in effect. The caller decides how
     * to react — the UI shows "aggiornamento fallito" and keeps the cache.
     */
    suspend fun refreshOnline(): Result<String> = withContext(Dispatchers.IO) {
        val fetched = DomainFetcher.fetchOnline()
            ?: return@withContext Result.failure(IllegalStateException("domain fetch unavailable"))
        runCatching {
            val freshPrimary = fetched.primary() ?: FALLBACK_DOMAIN
            // Dominio NUOVO dall'upstream: i fallimenti precedenti si riferivano
            // ad altri host — ripulisci il fail set e riparti dal primario.
            if (freshPrimary != currentDomain()) failedDomains.clear()
            saveToDisk(fetched)
            _current.value = fetched
            freshPrimary
        }
    }

    // -- persistence ------------------------------------------------------------

    private fun loadFromDisk(): DomainsConfig? = runCatching {
        if (!cacheFile.exists()) return null
        val text = cacheFile.readText()
        if (text.isBlank()) return null
        json.decodeFromString<DomainsConfig>(text)
    }.getOrNull()

    private fun loadFromAsset(): DomainsConfig? = runCatching {
        val text = appContext.assets.open("domains.json").bufferedReader().use { it.readText() }
        json.decodeFromString<DomainsConfig>(text)
    }.getOrNull()

    private fun saveToDisk(cfg: DomainsConfig) {
        cacheFile.parentFile?.mkdirs()
        cacheFile.writeText(json.encodeToString(DomainsConfig.serializer(), cfg))
    }

    companion object {
        private const val FALLBACK_DOMAIN = "https://streamingcommunityz.nexus/"

        @Volatile private var INSTANCE: DomainRepository? = null

        fun get(context: Context): DomainRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: DomainRepository(context.applicationContext).also { INSTANCE = it }
            }
    }
}
