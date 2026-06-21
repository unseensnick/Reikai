package reikai.presentation.novel.browse

import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.GetNovelCategories
import reikai.domain.novel.interactor.SetNovelCategories
import reikai.domain.novel.model.Novel
import reikai.novel.host.NovelItem
import reikai.novel.source.NovelSourceManager

/**
 * Shared long-press "add to library" flow for any novel browse surface (per-source browse and
 * cross-source global search). Stateless: each method returns the next [NovelBrowseDialog] to show
 * (or null to dismiss) so each caller keeps ownership of its own dialog state. The source id is passed
 * per call because per-source browse has a fixed source while global search has one per result.
 */
class NovelLibraryAdder(
    private val novelRepository: NovelRepository,
    private val manager: NovelSourceManager,
    private val getNovelCategories: GetNovelCategories,
    private val setNovelCategories: SetNovelCategories,
) {

    /** Decide the long-press outcome: remove (already saved), confirm a possible duplicate, or add. */
    suspend fun onLongClick(
        item: NovelItem,
        sourceId: String,
        favoritedKeys: Set<Pair<String, String>>,
    ): NovelBrowseDialog? {
        if ((sourceId to item.path) in favoritedKeys) {
            return NovelBrowseDialog.RemoveNovel(item, sourceId)
        }
        // -1: the item isn't favorited yet, so there's no library row to exclude (a non-favorite
        // shadow row is excluded by the query's favorite=1 filter anyway).
        val duplicates = novelRepository.getDuplicateLibraryNovel(-1L, item.name)
        if (duplicates.isNotEmpty()) {
            // Resolve names + sites here so the dialog stays DI-free.
            val resolved = duplicates.associate { it.novel.source to manager.get(it.novel.source) }
            return NovelBrowseDialog.AddDuplicate(
                item = item,
                sourceId = sourceId,
                duplicates = duplicates,
                sourceNames = resolved.mapValues { (id, src) -> src?.name ?: id },
                sourceSites = resolved.mapValues { (_, src) -> src?.site },
            )
        }
        return addToLibrary(item, sourceId)
    }

    /** Favorite the item (a minimal row, full metadata fills on first details open); returns the
     *  category picker dialog when the user has categories, else null. insertOrGet may return a
     *  non-favorite shadow row from a prior details open, so favorite is applied as a follow-up. */
    suspend fun addToLibrary(item: NovelItem, sourceId: String): NovelBrowseDialog? {
        val base = Novel.create().copy(
            source = sourceId,
            url = item.path,
            title = item.name,
            thumbnailUrl = item.cover,
        )
        val stored = novelRepository.insertOrGet(base) ?: return null
        if (!stored.favorite) {
            novelRepository.update(stored.copy(favorite = true, dateAdded = System.currentTimeMillis()))
        }
        val categories = getNovelCategories.await().filter { it.id > 0L }
        return if (categories.isNotEmpty()) {
            val current = getNovelCategories.awaitByNovelId(stored.id).map { it.id }.toSet()
            NovelBrowseDialog.ChangeCategory(stored.id, categories, current)
        } else {
            null
        }
    }

    suspend fun applyCategories(novelId: Long, categoryIds: List<Long>) {
        setNovelCategories.await(novelId, categoryIds)
    }

    /** Remove a favorited result from the library (keeps the row + read state, like the manga side). */
    suspend fun confirmRemove(item: NovelItem, sourceId: String) {
        novelRepository.getByUrlAndSource(item.path, sourceId)?.let {
            novelRepository.update(it.copy(favorite = false))
        }
    }
}
