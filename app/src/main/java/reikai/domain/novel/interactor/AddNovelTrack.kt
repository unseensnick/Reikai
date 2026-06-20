package reikai.domain.novel.interactor

import eu.kanade.tachiyomi.data.track.Tracker
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.track.NovelTrackUpdater
import reikai.domain.novel.track.toDbTrack
import reikai.domain.novel.track.toNovelTrack
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withNonCancellableContext
import eu.kanade.tachiyomi.data.database.models.Track as DbTrack

/**
 * Novel twin of [eu.kanade.domain.track.interactor.AddTracks.bind]: registers a freshly picked tracker
 * entry remotely, persists it to `novel_tracks`, then pushes the local read progress so a novel the
 * user has already read is reflected on the tracker. Skips the EnhancedTracker chapter-sync (a no-op
 * for the four light-novel trackers). The start-date backfill is deferred (the date stays settable in
 * the sheet); only the last-chapter push is carried, which is the high-value parity behaviour.
 */
class AddNovelTrack(
    private val insertNovelTrack: InsertNovelTrack,
    private val novelTrackUpdater: NovelTrackUpdater,
    private val novelChapterRepository: NovelChapterRepository,
) {

    suspend fun bind(tracker: Tracker, item: DbTrack, novelId: Long) = withNonCancellableContext {
        withIOContext {
            val allChapters = novelChapterRepository.getByNovelId(novelId)
            val hasReadChapters = allChapters.any { it.read }
            tracker.bind(item, hasReadChapters)

            item.manga_id = novelId
            var track = item.toNovelTrack(idRequired = false) ?: return@withIOContext
            insertNovelTrack.await(track)

            if (hasReadChapters) {
                val latestLocalReadChapterNumber = allChapters
                    .sortedBy { it.chapterNumber }
                    .takeWhile { it.read }
                    .lastOrNull()
                    ?.chapterNumber ?: -1.0

                if (latestLocalReadChapterNumber > track.lastChapterRead) {
                    track = track.copy(lastChapterRead = latestLocalReadChapterNumber)
                    novelTrackUpdater.setRemoteLastChapterRead(
                        tracker,
                        track.toDbTrack(),
                        latestLocalReadChapterNumber.toInt(),
                    )
                }
            }
        }
    }
}
