package reikai.domain.track

import eu.kanade.tachiyomi.data.track.Tracker
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import eu.kanade.tachiyomi.data.database.models.Track as DbTrack

/**
 * The trackers one chapter push failed at. Both chapter interactors return it, so a caller that tells
 * the user can name the trackers that refused the push; one that landed needs no word.
 */
data class ChapterPushOutcome(val failed: List<Pair<Tracker, Throwable>>) {

    companion object {
        /** Folds each tracker's result; failures are logged here, as both interactors did before. */
        fun of(results: List<Pair<Tracker, Result<*>>>): ChapterPushOutcome {
            val failed = results.mapNotNull { (tracker, result) -> result.exceptionOrNull()?.let { tracker to it } }
            failed.forEach { (_, error) -> logcat(LogPriority.WARN, error) }
            return ChapterPushOutcome(failed)
        }
    }
}

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
