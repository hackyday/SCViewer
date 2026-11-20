package com.scviewer.fire.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * SharedPreferences-backed favourite list, CON SCOPO PROFILI.
 *
 * Storage a BUCKET: sotto PREFS_KEY c'è un JSON oggetto
 * `{"<profileId>": [Favorite, ...], ...}` — ogni profilo SCtv ha la sua
 * lista. I dati LEGACY (array piatto pre-profili) vengono migrati alla
 * prima lettura dentro il bucket "default" (zero perdita: la modalità sito
 * e gli utenti attuali continuano a vedere i loro dati).
 *
 * Insertion is O(n) because the list is small (user-curated) — a
 * LinkedHashMap would be overkill.
 *
 * Come per la cronologia: URL salvati RELATIVI e rimontati sul dominio
 * corrente alla lettura ([SiteUrl]); entry legacy assolute migrate on-read.
 */
class FavoritesStore private constructor(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listSerializer = ListSerializer(Favorite.serializer())
    private val bucketSerializer = MapSerializer(String.serializer(), listSerializer)

    /** Legge TUTTI i bucket; al primo incontro con dati legacy (array piatto
     *  senza bucket) li raccoglie dentro "default" e li riscrive su disco. */
    private fun loadBuckets(): MutableMap<String, MutableList<Favorite>> {
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

    private fun persistBuckets(buckets: Map<String, List<Favorite>>) {
        prefs.edit { putString(KEY, json.encodeToString(bucketSerializer, buckets)) }
    }

    fun all(profileId: String = DEFAULT_PROFILE_ID): List<Favorite> {
        val key = sanitizeProfileId(profileId)
        val buckets = loadBuckets()
        val list = buckets.getOrPut(key) { mutableListOf() }
        // Migrazione one-shot URL assoluti legacy -> path relativo (per bucket).
        if (list.any { it.url.startsWith("http") }) {
            val migrated = list.mapNotNull { f ->
                SiteUrl.relativeOf(f.url)?.let { f.copy(url = it) }
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
        // Salva SEMPRE relativo (solo path+query).
        val rel = SiteUrl.relativeOf(url) ?: return
        val key = sanitizeProfileId(profileId)
        val buckets = loadBuckets()
        val list = buckets.getOrPut(key) { mutableListOf() }
        // De-dupe per PATH: ri-aggiungere sul nuovo dominio sposta in cima.
        list.removeAll { it.url == rel }
        list.add(0, Favorite(url = rel, title = title, poster = poster, addedAt = System.currentTimeMillis()))
        // Cap at 50 entries per bucket — beyond that the overlay becomes
        // unscrollable.
        buckets[key] = list.take(MAX).toMutableList()
        persistBuckets(buckets)
    }

    fun remove(url: String, profileId: String = DEFAULT_PROFILE_ID) {
        val rel = SiteUrl.relativeOf(url) ?: url
        val key = sanitizeProfileId(profileId)
        val buckets = loadBuckets()
        val list = buckets.getOrPut(key) { mutableListOf() }
        val before = list.size
        list.removeAll { it.url == rel || it.url == url }
        if (list.size != before) persistBuckets(buckets)
    }

    fun contains(url: String, profileId: String = DEFAULT_PROFILE_ID): Boolean {
        val rel = SiteUrl.relativeOf(url) ?: return false
        return all(profileId).any { it.url == rel }
    }

    companion object {
        private const val PREFS = "scviewer_favorites"
        private const val KEY = "favorites"
        private const val MAX = 50

        @Volatile private var INSTANCE: FavoritesStore? = null
        fun get(context: Context): FavoritesStore =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: FavoritesStore(context.applicationContext).also { INSTANCE = it }
            }
    }
}
