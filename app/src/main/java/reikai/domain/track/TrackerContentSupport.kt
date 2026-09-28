package reikai.domain.track

import eu.kanade.tachiyomi.data.track.Tracker

/**
 * The trackers offerable for one content type: a service is listed only where its catalogue holds
 * that type. The tracking sheet, both details screens' Tracking button and count, and the novel
 * add-time bind call this, so the rule exists once.
 *
 * Binding across the two is worse than an empty search, because the entry binds to a different work
 * and that work's chapter count then drives progress sync.
 */
fun List<Tracker>.supportingContent(isNovel: Boolean): List<Tracker> = filter { it.supportsContent(isNovel) }

/** Whether this tracker's catalogue holds the content type; the per-tracker form of [supportingContent]. */
fun Tracker.supportsContent(isNovel: Boolean): Boolean = if (isNovel) supportsNovels else supportsManga
