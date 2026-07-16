package tachiyomi.data.manga

import app.cash.sqldelight.async.coroutines.awaitAsList
import kotlinx.coroutines.flow.Flow
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.data.subscribeToOneOrNull
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.repository.CustomMangaInfoRepository

class CustomMangaInfoRepositoryImpl(
    private val database: Database,
) : CustomMangaInfoRepository {

    override suspend fun getAll(): List<CustomMangaInfo> {
        return database.custom_manga_infoQueries.getAll(::mapCustomMangaInfo).awaitAsList()
    }

    override fun getByMangaIdAsFlow(mangaId: Long): Flow<CustomMangaInfo?> {
        return database.custom_manga_infoQueries
            .getByMangaId(mangaId, ::mapCustomMangaInfo)
            .subscribeToOneOrNull()
    }

    override fun getAllAsFlow(): Flow<List<CustomMangaInfo>> {
        return database.custom_manga_infoQueries.getAll(::mapCustomMangaInfo).subscribeToList()
    }

    override suspend fun set(info: CustomMangaInfo) {
        database.custom_manga_infoQueries.insert(
            mangaId = info.mangaId,
            title = info.title,
            author = info.author,
            artist = info.artist,
            description = info.description,
            genre = info.genre,
            status = info.status,
            thumbnailUrl = info.thumbnailUrl,
        )
    }

    override suspend fun delete(mangaId: Long) {
        database.custom_manga_infoQueries.delete(mangaId)
    }

    private fun mapCustomMangaInfo(
        mangaId: Long,
        title: String?,
        author: String?,
        artist: String?,
        description: String?,
        genre: List<String>?,
        status: Long?,
        thumbnailUrl: String?,
    ): CustomMangaInfo = CustomMangaInfo(
        mangaId = mangaId,
        title = title,
        author = author,
        artist = artist,
        description = description,
        genre = genre,
        status = status,
        thumbnailUrl = thumbnailUrl,
    )
}
