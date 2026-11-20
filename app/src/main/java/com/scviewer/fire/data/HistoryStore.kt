package com.scviewer.fire.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Watch history — last 20 visited pages. Most-recent-first.
 *
 * Used by the bottom menu's "Cronologia" entry.
 *
 * CON SCOPO PROFILI: sotto PREFS_KEY c'è un JSON oggetto
 * `{"<profileId>": [HistoryEntry, ...], ...}` — ogni profilo SCtv ha la sua
 * cronologia (limite [MAX] PER BUCKET). I dati LEGACY (array piatto
 * pre-profili) vengono migrati alla prima lettura dentro il bucket
 * "default" (zero perdita).
 *
 * Gli URL sono salvati RELATIVI (solo path+query, vedi [SiteUrl]): così un
 * cambio di dominio del sito non invalida la cronologia — alla riapertura
 * [SiteUrl.resolve] rimonta il path sul dominio corrente. Le entry legacy
 * (URL assoluto, versioni <= 1.2.2) vengono migrate alla prima lettura.
 */
class HistoryStore private constructor(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listSerializer = ListSerializer(HistoryEntry.serializer())
    private val bucketSerializer = MapSerializer(String.serializer(), listSerializer)

    /** Legge TUTTI i bucket; al primo incontro con dati legacy (array piatto
     *  senza bucket) li raccoglie dentro "default" e li riscrive su disco. */
    private fun loadBuckets(): MutableMap<String, MutableList<HistoryEntry>> {
        val raw = prefs.getString(KEY, null) ?: return mutableMapOf()
        val bucketed = runCatching {
            json.decodeFromString(bucketSerializer, raw)
                .mapValues { it.value.toMutableList() }
                .toMutableMap()
        }.getOrNull()
        if (bucketed != null) return bucketed
        // Dati legacy (pre-profili): array piatto → bucket "default".
        val legacy = runCatching {
            json.decodeFromString(listSerializer, raw).toMutableList()
        }.getOrDefault(mutableListOf())
        val buckets = mutableMapOf(DEFAULT_PROFILE_ID to legacy)
        prefs.edit { putString(KEY, json.encodeToString(bucketSerializer, buckets)) }
        return buckets
    }

    private fun persistBuckets(buckets: Map<String, List<HistoryEntry>>) {
        prefs.edit { putString(KEY, json.encodeToString(bucketSerializer, buckets)) }
    }

    fun all(profileId: String = DEFAULT_PROFILE_ID): List<HistoryEntry> {
        val key = sanitizeProfileId(profileId)
        val buckets = loadBuckets()
        val list = buckets.getOrPut(key) { mutableListOf() }
        // Migrazione one-shot URL assoluti legacy -> path relativo (per bucket).
        if (list.any { it.url.startsWith("http") }) {
            val migrated = list.mapNotNull { e ->
                SiteUrl.relativeOf(e.url)?.let { e.copy(url = it) }
            }.toMutableList()
            buckets[key] = migrated
            persistBuckets(buckets)
            return migrated
        }
        return list
    }

    fun add(
        url: String,
        title: String,
        poster: String? = null,
        profileId: String = DEFAULT_PROFILE_ID
    ) {
        // Salva SEMPRE relativo: il dominio corrente non deve restare congelato nel link.
        val rel = SiteUrl.relativeOf(url) ?: return
        val key = sanitizeProfileId(profileId)
        val buckets = loadBuckets()
        val list = buckets.getOrPut(key) { mutableListOf() }
        // De-dupe per PATH (non per URL assoluto): rivisitare sul nuovo dominio
        // sposta in cima la stessa voce invece di duplicarla.
        list.removeAll { it.url == rel }
        list.add(0, HistoryEntry(url = rel, title = title, poster = poster, visitedAt = System.currentTimeMillis()))
        buckets[key] = list.take(MAX).toMutableList()
        persistBuckets(buckets)
    }

    /** Svuota SOLO il bucket del profilo (le cronologie degli altri profili
     *  restano al loro posto). */
    fun clear(profileId: String = DEFAULT_PROFILE_ID) {
        val key = sanitizeProfileId(profileId)
        val buckets = loadBuckets()
        if (buckets.remove(key) != null) persistBuckets(buckets)
    }

    companion object {
        private const val PREFS = "scviewer_history"
        private const val KEY = "history"
        private const val MAX = 20

        @Volatile private var INSTANCE: HistoryStore? = null
        fun get(context: Context): HistoryStore =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: HistoryStore(context.applicationContext).also { INSTANCE = it }
            }
    }
}
