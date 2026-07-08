package reikai.data.novel

import app.cash.sqldelight.async.coroutines.awaitAsList
import kotlinx.coroutines.flow.Flow
import reikai.domain.novel.model.CustomNovelInfo
import reikai.domain.novel.repository.CustomNovelInfoRepository
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.data.subscribeToOneOrNull

class CustomNovelInfoRepositoryImpl(
    private val database: Database,
) : CustomNovelInfoRepository {

    override suspend fun getAll(): List<CustomNovelInfo> {
        return database.custom_novel_infoQueries.getAll(::mapCustomNovelInfo).awaitAsList()
    }

    override fun getByNovelIdAsFlow(novelId: Long): Flow<CustomNovelInfo?> {
        return database.custom_novel_infoQueries
            .getByNovelId(novelId, ::mapCustomNovelInfo)
            .subscribeToOneOrNull()
    }

    override fun getAllAsFlow(): Flow<List<CustomNovelInfo>> {
        return database.custom_novel_infoQueries.getAll(::mapCustomNovelInfo).subscribeToList()
    }

    override suspend fun set(info: CustomNovelInfo) {
        database.custom_novel_infoQueries.insert(
            novelId = info.novelId,
            title = info.title,
            author = info.author,
            artist = info.artist,
            description = info.description,
            genre = info.genre,
            status = info.status,
            thumbnailUrl = info.thumbnailUrl,
        )
    }

    override suspend fun delete(novelId: Long) {
        database.custom_novel_infoQueries.delete(novelId)
    }

    private fun mapCustomNovelInfo(
        novelId: Long,
        title: String?,
        author: String?,
        artist: String?,
        description: String?,
        genre: List<String>?,
        status: Long?,
        thumbnailUrl: String?,
    ): CustomNovelInfo = CustomNovelInfo(
        novelId = novelId,
        title = title,
        author = author,
        artist = artist,
        description = description,
        genre = genre,
        status = status,
        thumbnailUrl = thumbnailUrl,
    )
}
