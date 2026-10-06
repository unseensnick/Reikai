package reikai.domain.manga

import dev.zacsweers.metro.Inject
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.util.removeCovers
import reikai.domain.entry.EntryId
import reikai.domain.library.EntryLibraryRemoval
import reikai.domain.track.source.SourceTrackerDispatcher
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.MangaUpdate

/** [EntryLibraryRemoval] over the manga table. Writes only the library date and the cover stamp. */
@Inject
class RemoveMangaFromLibrary(
    mergeManager: MangaMergeManager,
    sourceTracker: SourceTrackerDispatcher,
    private val getManga: GetManga,
    private val updateManga: UpdateManga,
    private val coverCache: CoverCache,
) : EntryLibraryRemoval(mergeManager, sourceTracker) {

    override fun entryId(id: Long): EntryId = EntryId.Manga(id)

    override suspend fun favoriteAt(id: Long): Long? = getManga.await(id)?.favoriteAt

    override suspend fun writeFavoriteAt(favoriteAt: Map<Long, Long?>): Boolean =
        updateManga.awaitAll(favoriteAt.map { (id, at) -> MangaUpdate(id) { this.favoriteAt = at } })

    override suspend fun deleteCovers(id: Long): Boolean =
        getManga.await(id)?.let { it.removeCovers(coverCache) != it } == true

    override suspend fun stampCover(id: Long) {
        updateManga.awaitUpdateCoverLastModified(id)
    }
}
