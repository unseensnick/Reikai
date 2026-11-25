package reikai.domain.novel.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.cache.CoverCache
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.library.EntryLibraryRemoval
import reikai.domain.local.LocalVolumeHandBack
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.NovelUpdate
import reikai.domain.track.source.SourceTrackerDispatcher
import reikai.novel.local.LocalNovelSource
import kotlin.time.Instant

/** [EntryLibraryRemoval] over the novels table. Writes only the library date. */
@Inject
class RemoveNovelsFromLibrary(
    mergeManager: NovelMergeManager,
    sourceTracker: SourceTrackerDispatcher,
    private val novelRepository: NovelRepository,
    private val coverCache: CoverCache,
    localVolumes: () -> LocalVolumeHandBack,
) : EntryLibraryRemoval(mergeManager, sourceTracker, localVolumes) {

    override val contentType = ContentType.NOVELS

    override suspend fun favoriteAt(id: Long): Instant? = novelRepository.getById(id)?.favoriteAt

    override suspend fun writeFavoriteAt(favoriteAt: Map<Long, Instant?>): Boolean =
        novelRepository.updateAll(favoriteAt.map { (id, at) -> NovelUpdate(id) { this.favoriteAt = at } })

    override suspend fun deleteCovers(id: Long) {
        val novel = novelRepository.getById(id)
        // A local series keeps its covers, as manga's removeCovers keeps them: one set on a bare EPUB lives
        // in the custom-cover cache, with no folder of its own to hold it.
        if (novel?.source == LocalNovelSource.ID) return
        coverCache.deleteFromCache(EntryId.Novel(id), novel?.thumbnailUrl, deleteCustomCover = true)
    }
}
