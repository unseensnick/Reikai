package reikai.domain.track

import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.database.models.Track as DbTrack

/**
 * Push read progress to a tracker and return the row to persist.
 *
 * `update` is what flips the status to reading and stamps the start date, so writing back the row
 * that went in loses both. Most trackers mutate and return that same instance; the server-backed
 * ones re-fetch and return a `TrackSearch` with no local id, so a foreign answer is folded into the
 * caller's row rather than replacing it. Shared by both chapter interactors, so the rule lives once.
 */
suspend fun Tracker.pushChapterProgress(track: DbTrack): DbTrack {
    val returned = update(track, didReadChapter = true)
    if (returned !== track) {
        track.copyPersonalFrom(returned)
    }
    return track
}
