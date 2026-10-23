package com.scviewer.fire.data

import android.content.Context
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
 *      If missing, copy the bundled asset domains.json (which holds the
 *      last-known-good URL https://streamingcommunityz.build/).
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
            saveToDisk(fetched)
            _current.value = fetched
            fetched.primary() ?: FALLBACK_DOMAIN
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
        private const val FALLBACK_DOMAIN = "https://streamingcommunityz.build/"

        @Volatile private var INSTANCE: DomainRepository? = null

        fun get(context: Context): DomainRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: DomainRepository(context.applicationContext).also { INSTANCE = it }
            }
    }
}
