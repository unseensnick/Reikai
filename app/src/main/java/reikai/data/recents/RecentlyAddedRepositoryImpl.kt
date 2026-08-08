package reikai.data.recents

import kotlinx.coroutines.flow.Flow
import reikai.data.coil.NovelCover
import reikai.domain.recents.RecentlyAddedManga
import reikai.domain.recents.RecentlyAddedNovel
import reikai.domain.recents.RecentlyAddedRepository
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.manga.model.MangaCover

class RecentlyAddedRepositoryImpl(
    private val database: Database,
) : RecentlyAddedRepository {

    override fun subscribeManga(
        after: Long,
        limit: Long,
        includedCategories: List<Long>,
        excludedCategories: List<Long>,
    ): Flow<List<RecentlyAddedManga>> =
        database.recentlyAddedViewQueries.getRecentlyAddedManga(
            after = after,
            includedEmpty = includedCategories.isEmpty(),
            includedCategories = includedCategories,
            excludedEmpty = excludedCategories.isEmpty(),
            excludedCategories = excludedCategories,
            limit = limit,
            mapper = ::mapRecentlyAddedManga,
        ).subscribeToList()

    override fun subscribeNovels(
        after: Long,
        limit: Long,
        includedCategories: List<Long>,
        excludedCategories: List<Long>,
    ): Flow<List<RecentlyAddedNovel>> =
        database.recentlyAddedViewQueries.getRecentlyAddedNovels(
            after = after,
            includedEmpty = includedCategories.isEmpty(),
            includedCategories = includedCategories,
            excludedEmpty = excludedCategories.isEmpty(),
            excludedCategories = excludedCategories,
            limit = limit,
            mapper = ::mapRecentlyAddedNovel,
        ).subscribeToList()
}

// Both queries select only favorited entries, so the cover's favourite flag is true by construction.
private fun mapRecentlyAddedManga(
    mangaId: Long,
    title: String,
    source: Long,
    thumbnailUrl: String?,
    coverLastModified: Long,
    dateAdded: Long,
): RecentlyAddedManga = RecentlyAddedManga(
    mangaId = mangaId,
    title = title,
    dateAdded = dateAdded,
    coverData = MangaCover(
        mangaId = mangaId,
        sourceId = source,
        isMangaFavorite = true,
        url = thumbnailUrl,
        lastModified = coverLastModified,
    ),
)

private fun mapRecentlyAddedNovel(
    novelId: Long,
    title: String,
    source: String,
    url: String,
    thumbnailUrl: String?,
    coverLastModified: Long,
    dateAdded: Long,
): RecentlyAddedNovel = RecentlyAddedNovel(
    novelId = novelId,
    title = title,
    source = source,
    url = url,
    dateAdded = dateAdded,
    coverData = NovelCover(
        url = thumbnailUrl,
        site = null,
        isNovelFavorite = true,
        lastModified = coverLastModified,
        novelId = novelId,
    ),
)
