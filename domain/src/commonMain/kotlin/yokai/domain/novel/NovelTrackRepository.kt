package yokai.domain.novel

import kotlinx.coroutines.flow.Flow
import yokai.domain.novel.models.NovelTrack

interface NovelTrackRepository {
    suspend fun getByNovelId(novelId: Long): List<NovelTrack>
    fun observeByNovelId(novelId: Long): Flow<List<NovelTrack>>
    suspend fun upsert(track: NovelTrack): Long?
    suspend fun delete(novelId: Long, syncId: Long)
    suspend fun deleteAllForNovel(novelId: Long)
}
