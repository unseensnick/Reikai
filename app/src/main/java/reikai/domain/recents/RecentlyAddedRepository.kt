package reikai.domain.recents

import kotlinx.coroutines.flow.Flow

/**
 * The recents surface's newly-added lane, which has no upstream twin on either content type, so it is
 * Reikai-owned end to end rather than a patch on Mihon's manga repository. Both feeds carry the same
 * category filter as the other lanes; empty id lists mean no constraint. [after] bounds the lane the
 * way the updated lane is bounded, and [limit] caps it, because nothing bounds a library naturally.
 */
interface RecentlyAddedRepository {
    fun subscribeManga(
        after: Long,
        limit: Long,
        includedCategories: List<Long> = emptyList(),
        excludedCategories: List<Long> = emptyList(),
    ): Flow<List<RecentlyAddedManga>>

    fun subscribeNovels(
        after: Long,
        limit: Long,
        includedCategories: List<Long> = emptyList(),
        excludedCategories: List<Long> = emptyList(),
    ): Flow<List<RecentlyAddedNovel>>
}
