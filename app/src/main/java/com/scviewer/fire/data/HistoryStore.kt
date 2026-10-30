package com.scviewer.fire.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Watch history — last 20 visited pages. Most-recent-first.
 *
 * Used by the bottom menu's "Cronologia" entry.
 *
 * Gli URL sono salvati RELATIVI (solo path+query, vedi [SiteUrl]): così un
 * cambio di dominio del sito non invalida la cronologia — alla riapertura
 * [SiteUrl.resolve] rimonta il path sul dominio corrente. Le entry legacy
 * (URL assoluto, versioni <= 1.2.2) vengono migrate alla prima lettura.
 */
class HistoryStore private constructor(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun all(): List<HistoryEntry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val parsed = runCatching {
            json.decodeFromString(ListSerializer(HistoryEntry.serializer()), raw)
        }.getOrDefault(emptyList())
        // Migrazione one-shot: URL assoluti legacy -> path relativo.
        if (parsed.any { it.url.startsWith("http") }) {
            val migrated = parsed.mapNotNull { e ->
                SiteUrl.relativeOf(e.url)?.let { e.copy(url = it) }
            }
            persist(migrated)
            return migrated
        }
        return parsed
    }

    fun add(url: String, title: String) {
        // Salva SEMPRE relativo: il dominio corrente non deve restare congelato nel link.
        val rel = SiteUrl.relativeOf(url) ?: return
        val list = all().toMutableList()
        // De-dupe per PATH (non per URL assoluto): rivisitare sul nuovo dominio
        // sposta in cima la stessa voce invece di duplicarla.
        list.removeAll { it.url == rel }
        list.add(0, HistoryEntry(url = rel, title = title, visitedAt = System.currentTimeMillis()))
        persist(list.take(MAX))
    }

    fun clear() {
        prefs.edit { remove(KEY) }
    }

    private fun persist(list: List<HistoryEntry>) {
        prefs.edit { putString(KEY, json.encodeToString(ListSerializer(HistoryEntry.serializer()), list)) }
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
