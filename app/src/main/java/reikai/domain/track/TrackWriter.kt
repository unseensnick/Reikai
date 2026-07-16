package reikai.domain.track

import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.Tracker
import reikai.domain.novel.track.NovelTrackUpdater
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * The tracker-write surface the unified track dialog ([reikai.presentation.track.EntryTrackInfoDialogHomeScreen])
 * writes through, so manga and novel share one dialog stack. Both content types already push the same mutable
 * [Track] (DbTrack); only the persistence sink differs, so a manga write delegates straight to the tracker
 * while a novel write routes through [NovelTrackUpdater] into `novel_tracks`.
 */
interface TrackWriter {
    suspend fun setRemoteStatus(tracker: Tracker, track: Track, status: Long)
    suspend fun setRemoteLastChapterRead(tracker: Tracker, track: Track, chapterNumber: Int)
    suspend fun setRemoteScore(tracker: Tracker, track: Track, scoreString: String)
    suspend fun setRemoteStartDate(tracker: Tracker, track: Track, epochMillis: Long)
    suspend fun setRemoteFinishDate(tracker: Tracker, track: Track, epochMillis: Long)
    suspend fun setRemotePrivate(tracker: Tracker, track: Track, private: Boolean)
}

/** Manga writes call the tracker's own `setRemoteX` (BaseTracker persists to `manga_sync`). Stateless. */
object MangaTrackWriter : TrackWriter {
    override suspend fun setRemoteStatus(tracker: Tracker, track: Track, status: Long) {
        tracker.setRemoteStatus(track, status)
    }

    override suspend fun setRemoteLastChapterRead(tracker: Tracker, track: Track, chapterNumber: Int) {
        tracker.setRemoteLastChapterRead(track, chapterNumber)
    }

    override suspend fun setRemoteScore(tracker: Tracker, track: Track, scoreString: String) {
        tracker.setRemoteScore(track, scoreString)
    }

    override suspend fun setRemoteStartDate(tracker: Tracker, track: Track, epochMillis: Long) {
        tracker.setRemoteStartDate(track, epochMillis)
    }

    override suspend fun setRemoteFinishDate(tracker: Tracker, track: Track, epochMillis: Long) {
        tracker.setRemoteFinishDate(track, epochMillis)
    }

    override suspend fun setRemotePrivate(tracker: Tracker, track: Track, private: Boolean) {
        tracker.setRemotePrivate(track, private)
    }
}

/** The novel writer is the injected [NovelTrackUpdater]; the manga writer is the stateless [MangaTrackWriter]. */
fun trackWriterFor(isNovel: Boolean): TrackWriter =
    if (isNovel) Injekt.get<NovelTrackUpdater>() else MangaTrackWriter
