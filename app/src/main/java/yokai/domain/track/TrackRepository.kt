package yokai.domain.track

import eu.kanade.tachiyomi.data.database.models.Track
import kotlinx.coroutines.flow.Flow

interface TrackRepository {
    suspend fun getAllByMangaId(mangaId: Long): List<Track>
    fun subscribeAllByMangaId(mangaId: Long): Flow<List<Track>>
    suspend fun deleteForManga(mangaId: Long, syncId: Long)
    suspend fun deleteAllForManga(mangaId: Long)
    suspend fun insert(track: Track)
    suspend fun insertBulk(tracks: List<Track>)
}
