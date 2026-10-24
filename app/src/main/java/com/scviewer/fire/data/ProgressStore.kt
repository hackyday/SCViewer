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
 */
class ProgressStore private constructor(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mapSerializer =
        MapSerializer(Int.serializer(), WatchProgress.serializer())

    fun all(): Map<Int, WatchProgress> = runCatching {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        json.decodeFromString(mapSerializer, raw)
    }.getOrDefault(emptyMap())

    fun get(titleId: Int): WatchProgress? = all()[titleId]

    /** Insert or update, then trim to the most recent [MAX] entries. */
    fun save(p: WatchProgress) {
        val map = all().toMutableMap()
        val merged = map[p.titleId]
        // A stale report (older snapshot than what we have) must not regress.
        if (merged != null && merged.updatedAt > p.updatedAt) return
        map[p.titleId] = p
        val trimmed = map.entries
            .sortedByDescending { it.value.updatedAt }
            .take(MAX)
            .associate { it.toPair() }
        runCatching {
            prefs.edit { putString(KEY, json.encodeToString(mapSerializer, trimmed)) }
        }.onFailure { Log.w(TAG, "progress save failed", it) }
    }

    fun remove(titleId: Int) {
        val map = all().toMutableMap()
        map.remove(titleId)
        runCatching {
            prefs.edit { putString(KEY, json.encodeToString(mapSerializer, map)) }
        }
    }

    /** Entries worth offering in the "Riprendi" list, most recent first. */
    fun resumeable(): List<WatchProgress> =
        all().values.filter { it.isResumeable() }.sortedByDescending { it.updatedAt }

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
