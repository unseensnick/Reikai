package tachiyomi.domain.manga.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.manga.model.CustomMangaInfo

interface CustomMangaInfoRepository {

    fun getByMangaIdAsFlow(mangaId: Long): Flow<CustomMangaInfo?>

    fun getAllAsFlow(): Flow<List<CustomMangaInfo>>

    suspend fun set(info: CustomMangaInfo)

    suspend fun delete(mangaId: Long)
}
