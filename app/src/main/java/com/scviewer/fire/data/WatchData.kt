package com.scviewer.fire.data

import kotlinx.serialization.Serializable

/** Compact page state parsed from the site's Inertia [data-page] attribute
 *  (reported by page_inject.js on every navigation). */
@Serializable
data class PageInfo(
    val component: String = "",
    val titleId: Int = 0,
    val name: String = "",
    val slug: String = "",
    val type: String = "",
    val season: Int? = null,
    val episode: Int? = null,
    val episodeId: Int? = null,
    val episodeName: String? = null
) {
    val isTitlePage: Boolean get() = component == "Titles/Title"
    val isWatchPage: Boolean get() = component == "Titles/Watch"
    val isSeries: Boolean get() = type == "tv"

    /** Display label like "MobLand — S1E2 · Jigsaw Puzzle". */
    fun displayLabel(): String = when {
        isSeries && season != null && episode != null ->
            "$name — S${season}E$episode" + (episodeName?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
        else -> name
    }
}

/** One show's "continue watching" state, persisted by [ProgressStore]. */
@Serializable
data class WatchProgress(
    val titleId: Int,
    val name: String,
    val slug: String,
    val type: String,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeName: String? = null,
    val positionSec: Int,
    val durationSec: Int,
    /** The exact watch URL this progress was recorded on (/it/watch/…). */
    val url: String,
    val updatedAt: Long
) {
    /** True when it makes sense to offer "Riprendi": started but not finished. */
    fun isResumeable(): Boolean {
        if (durationSec < 60) return false
        if (positionSec < 30) return false
        return positionSec.toDouble() / durationSec <= 0.97
    }

    /** "S1E2" for series, "" for movies. */
    fun seasonEpisodeTag(): String =
        if (type == "tv" && season != null && episode != null) "S${season}E$episode" else ""

    fun positionLabel(): String {
        val s = positionSec
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec)
        else String.format("%d:%02d", m, sec)
    }
}
