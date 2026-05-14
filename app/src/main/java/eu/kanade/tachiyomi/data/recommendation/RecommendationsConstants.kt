package eu.kanade.tachiyomi.data.recommendation

/**
 * Sentinel source id assigned to related-manga carousel entries that came from a tracker
 * recommendation rather than the user's current source. Mirrors Komikku's `RECOMMENDS_SOURCE`.
 *
 * Cards tagged with this id route through Global Search on click (because the tracker URL
 * doesn't resolve to any installed extension), so the user can pick a source to read on.
 */
const val RECOMMENDS_SOURCE: Long = -1L
