package com.scviewer.fire.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Watch history — last 20 visited pages. Most-recent-first.
 *
 * Used by the bottom menu's "Cronologia" entry.
 */
class HistoryStore private constructor(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun all(): List<HistoryEntry> = runCatching {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        json.decodeFromString(ListSerializer(HistoryEntry.serializer()), raw)
    }.getOrDefault(emptyList())

    fun add(url: String, title: String) {
        val list = all().toMutableList()
        // De-dupe by URL — re-visiting bubbles to top.
        list.removeAll { it.url == url }
        list.add(0, HistoryEntry(url = url, title = title, visitedAt = System.currentTimeMillis()))
        val capped = list.take(MAX)
        prefs.edit { putString(KEY, json.encodeToString(ListSerializer(HistoryEntry.serializer()), capped)) }
    }

    fun clear() {
        prefs.edit { remove(KEY) }
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
