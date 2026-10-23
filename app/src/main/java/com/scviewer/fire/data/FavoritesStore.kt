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
 */
class FavoritesStore private constructor(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun all(): List<Favorite> = runCatching {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        json.decodeFromString(ListSerializer(Favorite.serializer()), raw)
    }.getOrDefault(emptyList())

    fun add(url: String, title: String) {
        val list = all().toMutableList()
        // Remove any duplicate (by URL) so re-adding just bubbles to top.
        list.removeAll { it.url == url }
        list.add(0, Favorite(url = url, title = title, addedAt = System.currentTimeMillis()))
        // Cap at 50 entries — beyond that the overlay becomes unscrollable.
        val capped = list.take(50)
        prefs.edit { putString(KEY, json.encodeToString(ListSerializer(Favorite.serializer()), capped)) }
    }

    fun remove(url: String) {
        val list = all().filter { it.url != url }
        prefs.edit { putString(KEY, json.encodeToString(ListSerializer(Favorite.serializer()), list)) }
    }

    fun contains(url: String): Boolean = all().any { it.url == url }

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
