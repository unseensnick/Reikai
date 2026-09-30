package reikai.domain.novel.track

import android.content.Context
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.track.Tracker
import reikai.domain.novel.interactor.UpsertNovelTrack
import reikai.domain.track.TrackFieldMutations
import reikai.domain.track.TrackWriter
import reikai.domain.track.pushTrackEdit
import eu.kanade.tachiyomi.data.database.models.Track as DbTrack

/**
 * Novel twin of [eu.kanade.tachiyomi.data.track.BaseTracker]'s `setRemoteX` + `updateRemote`, pinned by
 * [TrackFieldMutations] and [pushTrackEdit], the kernels both call: pushes a field change to the remote
 * tracker and persists the result to `novel_tracks` (never `manga_track`), so a novel behaves identically
 * to a manga and inherits any upstream change instead of drifting from a hand-copy.
 */
@Inject
class NovelTrackUpdater(
    private val upsertNovelTrack: UpsertNovelTrack,
    private val context: Context,
) : TrackWriter {

    override suspend fun setRemoteStatus(tracker: Tracker, track: DbTrack, status: Long) {
        TrackFieldMutations.applyStatus(tracker, track, status)
        updateRemote(tracker, track)
    }

    override suspend fun setRemoteLastChapterRead(tracker: Tracker, track: DbTrack, chapterNumber: Int) {
        TrackFieldMutations.applyLastChapterRead(tracker, track, chapterNumber)
        updateRemote(tracker, track)
    }

    override suspend fun setRemoteScore(tracker: Tracker, track: DbTrack, scoreString: String) {
        TrackFieldMutations.applyScore(tracker, track, scoreString)
        updateRemote(tracker, track)
    }

    override suspend fun setRemoteStartDate(tracker: Tracker, track: DbTrack, epochMillis: Long) {
        track.started_reading_date = epochMillis
        updateRemote(tracker, track)
    }

    override suspend fun setRemoteFinishDate(tracker: Tracker, track: DbTrack, epochMillis: Long) {
        track.finished_reading_date = epochMillis
        updateRemote(tracker, track)
    }

    override suspend fun setRemotePrivate(tracker: Tracker, track: DbTrack, private: Boolean) {
        track.private = private
        updateRemote(tracker, track)
    }

    private suspend fun updateRemote(tracker: Tracker, track: DbTrack) =
        tracker.pushTrackEdit(context, track) { pushed ->
            pushed.toNovelTrack(idRequired = false)?.let { upsertNovelTrack.await(it) }
        }
}
