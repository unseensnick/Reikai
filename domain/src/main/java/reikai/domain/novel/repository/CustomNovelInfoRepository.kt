package reikai.domain.novel.repository

import kotlinx.coroutines.flow.Flow
import reikai.domain.novel.model.CustomNovelInfo

interface CustomNovelInfoRepository {

    fun getByNovelIdAsFlow(novelId: Long): Flow<CustomNovelInfo?>

    fun getAllAsFlow(): Flow<List<CustomNovelInfo>>

    suspend fun set(info: CustomNovelInfo)

    suspend fun delete(novelId: Long)
}
