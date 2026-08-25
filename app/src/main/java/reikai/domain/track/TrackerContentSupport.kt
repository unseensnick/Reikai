package reikai.domain.track

import eu.kanade.tachiyomi.data.track.Tracker

/**
 * The trackers offerable for one content type: a service is listed only where its catalogue holds
 * that type. Both tracking sheets and the manga details count call this, so the rule exists once.
 *
 * Binding across the two is worse than an empty search, because the entry binds to a different work
 * and that work's chapter count then drives progress sync.
 */
fun List<Tracker>.supportingContent(isNovel: Boolean): List<Tracker> =
    filter { if (isNovel) it.supportsNovels else it.supportsManga }
