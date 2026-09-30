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
 *
 * @param progressBefore the refreshed row's progress, before the caller overwrote it with this push.
 */
suspend fun Tracker.pushChapterProgress(track: DbTrack, progressBefore: Double): DbTrack {
    // Each tracker's update starts the series only when the push is exactly chapter 1, which misses a
    // series begun anywhere else. A push off zero is the start (both callers only push upwards), and a
    // date already there is never replaced.
    val startsSeries = progressBefore <= 0.0
    if (supportsReadingDates && startsSeries && track.started_reading_date == 0L) {
        track.started_reading_date = System.currentTimeMillis()
    }
    val returned = update(track, didReadChapter = true)
    if (returned !== track) {
        track.copyPersonalFrom(returned)
    }
    return track
}
