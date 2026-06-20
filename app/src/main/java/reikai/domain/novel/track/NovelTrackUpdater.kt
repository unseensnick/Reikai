package reikai.domain.novel.track

import android.app.Application
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.util.system.toast
import logcat.LogPriority
import reikai.domain.novel.interactor.InsertNovelTrack
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import eu.kanade.tachiyomi.data.database.models.Track as DbTrack

/**
 * Novel twin of [eu.kanade.tachiyomi.data.track.BaseTracker]'s `setRemoteX` + `updateRemote`: pushes a
 * field change to the remote tracker and persists the result to `novel_tracks` (never `manga_sync`).
 * The status/score transitions are ported verbatim so a novel behaves identically to a manga.
 */
class NovelTrackUpdater(
    private val insertNovelTrack: InsertNovelTrack,
) {

    suspend fun setRemoteStatus(tracker: Tracker, track: DbTrack, status: Long) {
        track.status = status
        if (track.status == tracker.getCompletionStatus() && track.total_chapters != 0L) {
            track.last_chapter_read = track.total_chapters.toDouble()
        }
        updateRemote(tracker, track)
    }

    suspend fun setRemoteLastChapterRead(tracker: Tracker, track: DbTrack, chapterNumber: Int) {
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
        updateRemote(tracker, track)
    }

    suspend fun setRemoteScore(tracker: Tracker, track: DbTrack, scoreString: String) {
        track.score = tracker.indexToScore(tracker.getScoreList().indexOf(scoreString))
        updateRemote(tracker, track)
    }

    suspend fun setRemoteStartDate(tracker: Tracker, track: DbTrack, epochMillis: Long) {
        track.started_reading_date = epochMillis
        updateRemote(tracker, track)
    }

    suspend fun setRemoteFinishDate(tracker: Tracker, track: DbTrack, epochMillis: Long) {
        track.finished_reading_date = epochMillis
        updateRemote(tracker, track)
    }

    private suspend fun updateRemote(tracker: Tracker, track: DbTrack): Unit = withIOContext {
        try {
            tracker.update(track)
            track.toNovelTrack(idRequired = false)?.let {
                insertNovelTrack.await(it)
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to update remote novel track id=${tracker.id}" }
            withUIContext { Injekt.get<Application>().toast(e.message) }
        }
    }
}
