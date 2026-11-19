package reikai.data.novel

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import reikai.domain.novel.model.CustomNovelInfo
import reikai.domain.novel.repository.CustomNovelInfoRepository
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.data.subscribeToOneOrNull

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class CustomNovelInfoRepositoryImpl(
    private val database: Database,
) : CustomNovelInfoRepository {

    override fun getByNovelIdAsFlow(novelId: Long): Flow<CustomNovelInfo?> {
        return database.custom_novel_infoQueries
            .getByNovelId(novelId, ::mapCustomNovelInfo)
            .subscribeToOneOrNull()
    }

    override fun getAllAsFlow(): Flow<List<CustomNovelInfo>> {
        return database.custom_novel_infoQueries.getAll(::mapCustomNovelInfo).subscribeToList()
    }

    override suspend fun getByNovelId(novelId: Long): CustomNovelInfo? =
        database.custom_novel_infoQueries.getByNovelId(novelId, ::mapCustomNovelInfo).awaitAsOneOrNull()

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
            // Stored as null when empty, so a row without alternative titles reads as one from before the column.
            altTitles = info.altTitles.takeIf { it.isNotEmpty() },
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
        altTitles: List<String>?,
    ): CustomNovelInfo = CustomNovelInfo(
        novelId = novelId,
        title = title,
        author = author,
        artist = artist,
        description = description,
        genre = genre,
        status = status,
        thumbnailUrl = thumbnailUrl,
        altTitles = altTitles.orEmpty(),
    )
}
