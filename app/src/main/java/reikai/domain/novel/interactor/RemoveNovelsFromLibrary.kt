package reikai.domain.novel.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.cache.CoverCache
import reikai.domain.entry.EntryId
import reikai.domain.library.EntryLibraryRemoval
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.NovelUpdate
import reikai.domain.track.source.SourceTrackerDispatcher

/** [EntryLibraryRemoval] over the novels table. Writes only the library date and the cover stamp. */
@Inject
class RemoveNovelsFromLibrary(
    mergeManager: NovelMergeManager,
    sourceTracker: SourceTrackerDispatcher,
    private val novelRepository: NovelRepository,
    private val updateNovel: UpdateNovel,
    private val coverCache: CoverCache,
) : EntryLibraryRemoval(mergeManager, sourceTracker) {

    override fun entryId(id: Long): EntryId = EntryId.Novel(id)

    override suspend fun favoriteAt(id: Long): Long? = novelRepository.getById(id)?.favoriteAt

    override suspend fun writeFavoriteAt(favoriteAt: Map<Long, Long?>): Boolean =
        novelRepository.updateAll(favoriteAt.map { (id, at) -> NovelUpdate(id) { this.favoriteAt = at } })

    override suspend fun deleteCovers(id: Long): Boolean = coverCache.deleteFromCache(
        EntryId.Novel(id),
        novelRepository.getById(id)?.thumbnailUrl,
        deleteCustomCover = true,
    ) > 0

    override suspend fun stampCover(id: Long) {
        updateNovel.awaitUpdateCoverLastModified(id)
    }
}
