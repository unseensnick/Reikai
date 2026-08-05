package reikai.domain.track

import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.Tracker

/**
 * Status, chapter and score transitions shared by the manga writer
 * ([eu.kanade.tachiyomi.data.track.BaseTracker]) and the novel writer
 * ([reikai.domain.novel.track.NovelTrackUpdater]), so an upstream tweak reaches novels automatically
 * instead of drifting from a hand-copy. Only the field mutation lives here; each writer keeps its own
 * remote push and persistence sink. The trivial date and private setters stay inline in each writer.
 */
object TrackFieldMutations {

    fun applyStatus(tracker: Tracker, track: Track, status: Long) {
        track.status = status
        if (track.status == tracker.getCompletionStatus() && track.total_chapters != 0L) {
            track.last_chapter_read = track.total_chapters.toDouble()
        }
    }

    fun applyLastChapterRead(tracker: Tracker, track: Track, chapterNumber: Int) {
        if (
            track.last_chapter_read == 0.0 &&
            track.last_chapter_read < chapterNumber &&
            track.status != tracker.getRereadingStatus()
        ) {
            track.status = tracker.getReadingStatus()
        }
        track.last_chapter_read = chapterNumber.toDouble()
        if (track.total_chapters != 0L && track.last_chapter_read.toLong() == track.total_chapters) {
            track.status = tracker.getCompletionStatus()
            track.finished_reading_date = System.currentTimeMillis()
        }
    }

    fun applyScore(tracker: Tracker, track: Track, scoreString: String) {
        track.score = tracker.indexToScore(tracker.getScoreList().indexOf(scoreString))
    }
}
