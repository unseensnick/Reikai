package yokai.domain.track.interactor

import eu.kanade.tachiyomi.data.database.models.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import yokai.domain.track.TrackRepository

class GetTrack(
    private val trackRepository: TrackRepository,
) {
    suspend fun awaitAllByMangaId(mangaId: Long?) = mangaId?.let { trackRepository.getAllByMangaId(it) } ?: listOf()

    fun subscribe(mangaId: Long?): Flow<List<Track>> =
        mangaId?.let { trackRepository.subscribeAllByMangaId(it) } ?: flowOf(emptyList())
}
