package reikai.domain.novel.interactor

import dev.zacsweers.metro.Inject
import reikai.domain.novel.NovelTrackRepository
import reikai.domain.novel.model.NovelTrack

// The repository catches and logs a failed write itself, so a cancellation is the only throw left to pass on.
@Inject
class UpsertNovelTrack(
    private val repository: NovelTrackRepository,
) {

    /** False when the write failed. */
    suspend fun await(track: NovelTrack): Boolean = repository.upsert(track)

    /** False when the write failed. */
    suspend fun awaitAll(tracks: List<NovelTrack>): Boolean = repository.upsertAll(tracks)
}
