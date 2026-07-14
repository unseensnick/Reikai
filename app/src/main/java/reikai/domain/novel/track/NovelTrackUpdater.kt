package reikai.domain.novel.track

import android.app.Application
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.util.system.toast
import logcat.LogPriority
import reikai.domain.novel.interactor.InsertNovelTrack
import reikai.domain.track.TrackFieldMutations
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import eu.kanade.tachiyomi.data.database.models.Track as DbTrack

/**
 * Novel twin of [eu.kanade.tachiyomi.data.track.BaseTracker]'s `setRemoteX` + `updateRemote`: pushes a
 * field change to the remote tracker and persists the result to `novel_tracks` (never `manga_sync`).
 * The status/chapter/score transitions come from the shared [reikai.domain.track.TrackFieldMutations],
 * the same source [eu.kanade.tachiyomi.data.track.BaseTracker] uses, so a novel behaves identically to
 * a manga and inherits any upstream change instead of drifting from a hand-copy.
 */
class NovelTrackUpdater(
    private val insertNovelTrack: InsertNovelTrack,
) {

    suspend fun setRemoteStatus(tracker: Tracker, track: DbTrack, status: Long) {
        TrackFieldMutations.applyStatus(tracker, track, status)
        updateRemote(tracker, track)
    }

    suspend fun setRemoteLastChapterRead(tracker: Tracker, track: DbTrack, chapterNumber: Int) {
        TrackFieldMutations.applyLastChapterRead(tracker, track, chapterNumber)
        updateRemote(tracker, track)
    }

    suspend fun setRemoteScore(tracker: Tracker, track: DbTrack, scoreString: String) {
        TrackFieldMutations.applyScore(tracker, track, scoreString)
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

    suspend fun setRemotePrivate(tracker: Tracker, track: DbTrack, private: Boolean) {
        track.private = private
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
