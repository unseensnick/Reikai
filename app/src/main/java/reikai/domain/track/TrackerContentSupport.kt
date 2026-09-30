package reikai.domain.track

import eu.kanade.tachiyomi.data.track.Tracker

/**
 * Whether this tracker's catalogue holds the content type, so a service is offered only where it holds
 * that type. The tracking sheet and both details screens' Tracking button reach it through
 * [EntryTrackPort.supports]; the novel add-time bind calls it directly.
 *
 * Binding across the two is worse than an empty search, because the entry binds to a different work
 * and that work's chapter count then drives progress sync.
 */
fun Tracker.supportsContent(isNovel: Boolean): Boolean = if (isNovel) supportsNovels else supportsManga
