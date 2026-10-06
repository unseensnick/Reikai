package reikai.domain.entry

import dev.zacsweers.metro.Inject
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.cache.CoverCache
import reikai.domain.novel.interactor.UpdateNovel

/**
 * Clears an entry's custom cover, for the cover viewer's Delete and edit info's Reset all. The new cover
 * key on the row is what makes the source cover show at once, since a cover still in memory is keyed by
 * the old one. Taking an entry out of the library is a different rule (it drops the cached cover too).
 */
@Inject
class ClearCustomCover(
    private val coverCache: CoverCache,
    private val updateManga: UpdateManga,
    private val updateNovel: UpdateNovel,
) {

    suspend fun await(id: EntryId) {
        coverCache.deleteCustomCover(id)
        when (id) {
            is EntryId.Manga -> updateManga.awaitUpdateCoverLastModified(id.rawId)
            is EntryId.Novel -> updateNovel.awaitUpdateCoverLastModified(id.rawId)
        }
    }
}
