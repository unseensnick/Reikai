package reikai.domain.novel

import kotlinx.coroutines.flow.Flow
import reikai.domain.novel.model.NovelTrack

interface NovelTrackRepository {

    suspend fun getTrackById(id: Long): NovelTrack?

    suspend fun getTracksByNovelId(novelId: Long): List<NovelTrack>

    fun getTracksByNovelIdAsFlow(novelId: Long): Flow<List<NovelTrack>>

    fun getTracksAsFlow(): Flow<List<NovelTrack>>

    suspend fun delete(novelId: Long, trackerId: Long)

    /** False when the write failed, which is logged rather than thrown. */
    suspend fun insert(track: NovelTrack): Boolean

    /** Insert every track in one transaction, so a multi-tracker carry cannot half apply. False when it failed. */
    suspend fun insertAll(tracks: List<NovelTrack>): Boolean
}
