package reikai.domain.novel.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.track.Tracker
import kotlinx.datetime.TimeZone
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelHistoryRepository
import reikai.domain.novel.track.NovelTrackUpdater
import reikai.domain.novel.track.toDbTrack
import reikai.domain.novel.track.toNovelTrack
import reikai.domain.track.BindChapter
import reikai.domain.track.bindBackfill
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withNonCancellableContext
import eu.kanade.tachiyomi.data.database.models.Track as DbTrack

/**
 * Novel twin of [eu.kanade.domain.track.interactor.AddTracks.bind], pinned by the [bindBackfill] kernel
 * both call: registers a freshly picked tracker entry remotely, persists it to `novel_tracks`, then
 * pushes the local read progress. Skips the EnhancedTracker chapter-sync (a no-op for the four
 * light-novel trackers).
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

            val backfill = bindBackfill(
                allChapters.map { BindChapter(it.chapterNumber, it.read) },
                track.lastChapterRead,
                track.startDate,
                TimeZone.currentSystemDefault(),
            ) { novelHistoryRepository.getEarliestReadAt(novelId) }
            backfill.lastChapterRead?.let {
                track = track.copy(lastChapterRead = it)
                novelTrackUpdater.setRemoteLastChapterRead(tracker, track.toDbTrack(), it.toInt())
            }
            backfill.startDate?.let {
                track = track.copy(startDate = it)
                novelTrackUpdater.setRemoteStartDate(tracker, track.toDbTrack(), it)
            }
        }
    }
}
