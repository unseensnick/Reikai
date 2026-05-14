package yokai.domain.track.interactor

import yokai.domain.track.TrackRepository

class DeleteTrack(
    private val trackRepository: TrackRepository,
) {
    suspend fun awaitForManga(mangaId: Long, syncId: Long) = trackRepository.deleteForManga(mangaId, syncId)
    suspend fun awaitForMangaAll(mangaId: Long) = trackRepository.deleteAllForManga(mangaId)
}
