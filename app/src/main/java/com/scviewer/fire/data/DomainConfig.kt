package com.scviewer.fire.data

import kotlinx.serialization.Serializable

/**
 * Mirrors AstraeLabs/Domains/domains_s.json shape:
 * {
 *   "streamingcommunity": "https://streamingcommunityz.build/",
 *   "vibravid":           "https://vibravid.com/",
 *   ...
 * }
 *
 * Also tolerates the extended shape that lists historical / verification URLs.
 */
@Serializable
data class DomainsConfig(
    val streamingcommunity: String? = null,
    val vibravid: String? = null,
    val updatedAt: Long = 0L
) {
    /**
     * The URL we should load when the app starts. Falls back to the
     * bundled asset if AstraeLabs hasn't shipped a value yet.
     */
    fun primary(): String? = streamingcommunity?.takeIf { it.isNotBlank() }
}

/**
 * Favourite record — title + URL + epochMs.
 */
@Serializable
data class Favorite(
    val url: String,
    val title: String,
    val addedAt: Long
)

/**
 * History entry — most-recent-first list, capped at 20 by HistoryStore.
 */
@Serializable
data class HistoryEntry(
    val url: String,
    val title: String,
    val visitedAt: Long
)
