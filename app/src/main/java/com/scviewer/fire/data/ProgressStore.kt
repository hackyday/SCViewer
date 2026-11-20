package com.scviewer.fire.data

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * "Riprendi" store — per-show playback position (and season/episode for
 * series), keyed by the site's numeric title id. Backed by SharedPreferences
 * as a JSON map; entries are capped by recency.
 *
 * CON SCOPO PROFILI: sotto PREFS_KEY c'è un JSON oggetto
 * `{"<profileId>": {"<titleId>": WatchProgress, ...}, ...}` — ogni profilo
 * SCtv ha il suo "Continua a guardare". I dati LEGACY (mappa piatta
 * pre-profili) vengono migrati alla prima lettura dentro il bucket
 * "default" (zero perdita: la modalità sito e gli utenti attuali
 * continuano a vedere i loro dati).
 */
class ProgressStore private constructor(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mapSerializer =
        MapSerializer(Int.serializer(), WatchProgress.serializer())
    private val bucketSerializer =
        MapSerializer(String.serializer(), mapSerializer)

    /** Legge TUTTI i bucket; al primo incontro con dati legacy (mappa piatta
     *  senza bucket) li raccoglie dentro "default" e li riscrive su disco. */
    private fun loadBuckets(): MutableMap<String, MutableMap<Int, WatchProgress>> {
        val raw = prefs.getString(KEY, null) ?: return mutableMapOf()
        val bucketed = runCatching {
            json.decodeFromString(bucketSerializer, raw)
                .mapValues { it.value.toMutableMap() }
                .toMutableMap()
        }.getOrNull()
        if (bucketed != null) return bucketed
        // Dati legacy (pre-profili): mappa piatta <titleId, WatchProgress> →
        // bucket "default".
        val legacy = runCatching {
            json.decodeFromString(mapSerializer, raw).toMutableMap()
        }.getOrDefault(mutableMapOf())
        val buckets = mutableMapOf(DEFAULT_PROFILE_ID to legacy)
        runCatching {
            prefs.edit { putString(KEY, json.encodeToString(bucketSerializer, buckets)) }
        }.onFailure { Log.w(TAG, "progress legacy migration failed", it) }
        return buckets
    }

    private fun persistBuckets(buckets: Map<String, Map<Int, WatchProgress>>) {
        runCatching {
            prefs.edit { putString(KEY, json.encodeToString(bucketSerializer, buckets)) }
        }.onFailure { Log.w(TAG, "progress save failed", it) }
    }

    fun all(profileId: String = DEFAULT_PROFILE_ID): Map<Int, WatchProgress> =
        runCatching {
            loadBuckets()[sanitizeProfileId(profileId)] ?: emptyMap()
        }.getOrDefault(emptyMap())

    fun get(titleId: Int, profileId: String = DEFAULT_PROFILE_ID): WatchProgress? =
        all(profileId)[titleId]

    /** Insert or update, then trim to the most recent [MAX] entries
     *  (limite PER BUCKET, invariato). */
    fun save(p: WatchProgress, profileId: String = DEFAULT_PROFILE_ID) {
        val key = sanitizeProfileId(profileId)
        val buckets = loadBuckets()
        val map = buckets.getOrPut(key) { mutableMapOf() }
        val merged = map[p.titleId]
        // A stale report (older snapshot than what we have) must not regress.
        if (merged != null && merged.updatedAt > p.updatedAt) return
        map[p.titleId] = p
        buckets[key] = map.entries
            .sortedByDescending { it.value.updatedAt }
            .take(MAX)
            .associate { it.toPair() }
            .toMutableMap()
        persistBuckets(buckets)
    }

    fun remove(titleId: Int, profileId: String = DEFAULT_PROFILE_ID) {
        val key = sanitizeProfileId(profileId)
        val buckets = loadBuckets()
        val map = buckets[key] ?: return
        if (map.remove(titleId) != null) persistBuckets(buckets)
    }

    /** Entries worth offering in the "Riprendi" list, most recent first. */
    fun resumeable(profileId: String = DEFAULT_PROFILE_ID): List<WatchProgress> =
        all(profileId).values.filter { it.isResumeable() }.sortedByDescending { it.updatedAt }

    companion object {
        private const val TAG = "ProgressStore"
        private const val PREFS = "scviewer_progress"
        private const val KEY = "progress"
        private const val MAX = 50

        @Volatile private var INSTANCE: ProgressStore? = null
        fun get(context: Context): ProgressStore =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ProgressStore(context.applicationContext).also { INSTANCE = it }
            }
    }
}
