package reikai.domain.novel.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.cache.CoverCache
import reikai.domain.entry.EntryId
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelRepository

/**
 * Takes novels out of the library, the one step novel details, the library and browse all call. Each
 * keeps its own copy of the group's shared tracker (the hand-out skips non-favourites, so it runs
 * first), and loses its cached library cover and any custom cover, as Mihon's removeCovers does for
 * manga. Returns the ids whose favourite write landed.
 */
@Inject
class RemoveNovelsFromLibrary(
    private val mergeManager: NovelMergeManager,
    private val updateNovel: UpdateNovel,
    private val novelRepository: NovelRepository,
    private val coverCache: CoverCache,
) {

    suspend fun await(novelIds: List<Long>): List<Long> {
        mergeManager.handOutTrackersBeforeRemoval(novelIds)
        return novelIds.filter { id ->
            updateNovel.awaitUpdateFavorite(id, favorite = false).also { removed -> if (removed) removeCovers(id) }
        }
    }

    private suspend fun removeCovers(novelId: Long) {
        val cover = coverCache.getCoverFile(novelRepository.getById(novelId)?.thumbnailUrl)
        val deletedCover = cover?.let { it.exists() && it.delete() } == true
        val deletedCustom = coverCache.deleteCustomCover(EntryId.Novel(novelId))
        // A new key, so a cover still in memory is not served for the entry once it is re-added.
        if (deletedCover || deletedCustom) updateNovel.awaitUpdateCoverLastModified(novelId)
    }
}
