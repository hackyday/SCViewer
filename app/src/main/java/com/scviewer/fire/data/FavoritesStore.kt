package com.scviewer.fire.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * SharedPreferences-backed favourite list.
 *
 * Stored as a JSON array under PREFS_KEY. Insertion is O(n) because the
 * list is small (user-curated) — a LinkedHashMap would be overkill.
 *
 * Come per la cronologia: URL salvati RELATIVI e rimontati sul dominio
 * corrente alla lettura ([SiteUrl]); entry legacy assolute migrate on-read.
 */
class FavoritesStore private constructor(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun all(): List<Favorite> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val parsed = runCatching {
            json.decodeFromString(ListSerializer(Favorite.serializer()), raw)
        }.getOrDefault(emptyList())
        // Migrazione one-shot: URL assoluti legacy -> path relativo.
        if (parsed.any { it.url.startsWith("http") }) {
            val migrated = parsed.mapNotNull { f ->
                SiteUrl.relativeOf(f.url)?.let { f.copy(url = it) }
            }
            persist(migrated)
            return migrated
        }
        return parsed
    }

    fun add(url: String, title: String) {
        // Salva SEMPRE relativo (solo path+query).
        val rel = SiteUrl.relativeOf(url) ?: return
        val list = all().toMutableList()
        // De-dupe per PATH: ri-aggiungere sul nuovo dominio sposta in cima.
        list.removeAll { it.url == rel }
        list.add(0, Favorite(url = rel, title = title, addedAt = System.currentTimeMillis()))
        // Cap at 50 entries — beyond that the overlay becomes unscrollable.
        persist(list.take(50))
    }

    fun remove(url: String) {
        val rel = SiteUrl.relativeOf(url) ?: url
        val list = all().filter { it.url != rel && it.url != url }
        persist(list)
    }

    fun contains(url: String): Boolean {
        val rel = SiteUrl.relativeOf(url) ?: return false
        return all().any { it.url == rel }
    }

    private fun persist(list: List<Favorite>) {
        prefs.edit { putString(KEY, json.encodeToString(ListSerializer(Favorite.serializer()), list)) }
    }

    companion object {
        private const val PREFS = "scviewer_favorites"
        private const val KEY = "favorites"

        @Volatile private var INSTANCE: FavoritesStore? = null
        fun get(context: Context): FavoritesStore =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: FavoritesStore(context.applicationContext).also { INSTANCE = it }
            }
    }
}
