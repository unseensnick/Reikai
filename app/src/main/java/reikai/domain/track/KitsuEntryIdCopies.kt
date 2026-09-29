package reikai.domain.track

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.first
import reikai.domain.novel.NovelTrackRepository
import reikai.domain.novel.interactor.UpsertNovelTrack
import tachiyomi.domain.track.interactor.UpsertTrack
import tachiyomi.domain.track.repository.TrackRepository

/**
 * Writes a healed Kitsu entry id to every stored copy of the bad record, manga and novel rows alike, since
 * merge-group propagation copied it onto each member. A copy is a row of that tracker carrying the entry id
 * as its remote id and no library id; a row with a library id is a proper binding and is left alone.
 */
@Inject
class KitsuEntryIdCopies(
    private val trackRepository: TrackRepository,
    private val upsertTrack: UpsertTrack,
    private val novelTrackRepository: NovelTrackRepository,
    private val upsertNovelTrack: UpsertNovelTrack,
) {

    suspend fun heal(trackerId: Long, entryId: Long, mangaId: Long) {
        fun isCopy(tracker: Long, remoteId: Long, libraryId: Long?) =
            tracker == trackerId && remoteId == entryId && (libraryId ?: 0L) == 0L

        trackRepository.getTracksAsFlow().first()
            .filter { isCopy(it.trackerId, it.remoteId, it.libraryId) }
            .map { it.copy(remoteId = mangaId, libraryId = entryId) }
            .takeIf { it.isNotEmpty() }
            ?.let { upsertTrack.awaitAll(it) }

        novelTrackRepository.getTracksAsFlow().first()
            .filter { isCopy(it.trackerId, it.remoteId, it.libraryId) }
            .map { it.copy(remoteId = mangaId, libraryId = entryId) }
            .takeIf { it.isNotEmpty() }
            ?.let { upsertNovelTrack.awaitAll(it) }
    }
}
