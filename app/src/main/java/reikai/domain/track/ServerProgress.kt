package reikai.domain.track

import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.Tracker

/**
 * Whether an update carrying [lastChapterRead] goes to [tracker]. A manga server marks every chapter up
 * to the progress it is sent read, and 0 means nothing read on both sides, so a server is never sent a 0:
 * it would mark a "Chapter 0" read on a series nobody started.
 */
fun sendsProgressTo(tracker: Tracker, lastChapterRead: Double): Boolean =
    tracker !is EnhancedTracker || lastChapterRead > 0
