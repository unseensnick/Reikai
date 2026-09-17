package reikai.domain.novel.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.util.lang.convertEpochMillisZone
import kotlinx.datetime.TimeZone
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelHistoryRepository
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
 * for the four light-novel trackers).
 */
@Inject
class AddNovelTrack(
    private val insertNovelTrack: InsertNovelTrack,
    private val novelTrackUpdater: NovelTrackUpdater,
    private val novelChapterRepository: NovelChapterRepository,
    private val novelHistoryRepository: NovelHistoryRepository,
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

                if (track.startDate <= 0) {
                    novelHistoryRepository.getEarliestReadAt(novelId)?.let { firstReadAt ->
                        val startDate = firstReadAt.convertEpochMillisZone(
                            TimeZone.currentSystemDefault(),
                            TimeZone.UTC,
                        )
                        track = track.copy(startDate = startDate)
                        novelTrackUpdater.setRemoteStartDate(tracker, track.toDbTrack(), startDate)
                    }
                }
            }
        }
    }
}
