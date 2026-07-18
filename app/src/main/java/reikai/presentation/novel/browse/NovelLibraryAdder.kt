package reikai.presentation.novel.browse

import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.GetNovelCategories
import reikai.domain.novel.interactor.SetNovelCategories
import reikai.domain.novel.interactor.UpdateNovel
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelCategory
import reikai.domain.novel.model.NovelWithChapterCount
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
    private val updateNovel: UpdateNovel,
    private val novelPreferences: NovelPreferences,
    private val mergeManager: NovelMergeManager,
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
        findDuplicates(-1L, item.name)?.let {
            return NovelBrowseDialog.AddDuplicate(
                item = item,
                sourceId = sourceId,
                duplicates = it.duplicates,
                sourceNames = it.sourceNames,
                sourceSites = it.sourceSites,
                suggestGroup = suggestGrouping,
                groupIdByNovelId = getDuplicateGroupIds(it.duplicates),
            )
        }
        return addToLibrary(item, sourceId)
    }

    /**
     * Shared duplicate lookup for every novel add-path (browse long-press, details favorite, history
     * add). Returns the possible library duplicates with their source names + sites resolved, or null
     * when there is none. One source of truth so the three add-paths can't drift. [id] is the row to
     * exclude from its own match (-1 when the item has no library row yet).
     */
    suspend fun findDuplicates(id: Long, title: String): NovelDuplicateInfo? {
        val duplicates = novelRepository.getDuplicateLibraryNovel(id, title)
        if (duplicates.isEmpty()) return null
        // Resolve names + sites here so each dialog host stays DI-free.
        val resolved = duplicates.associate { it.novel.source to manager.get(it.novel.source) }
        return NovelDuplicateInfo(
            duplicates = duplicates,
            sourceNames = resolved.mapValues { (id, src) -> src?.name ?: id },
            sourceSites = resolved.mapValues { (_, src) -> src?.site },
        )
    }

    /** Favorite the item (a minimal row, full metadata fills on first details open); applies the
     *  default category or returns the category picker dialog. */
    suspend fun addToLibrary(item: NovelItem, sourceId: String): NovelBrowseDialog? {
        val storedId = favoriteReturningId(item, sourceId) ?: return null
        return applyDefaultCategoryOrPrompt(storedId)?.let { prompt ->
            NovelBrowseDialog.ChangeCategory(storedId, prompt.categories, prompt.currentIds)
        }
    }

    /** Whether to offer add-time grouping in the duplicate dialog (see [NovelMergeManager]). */
    val suggestGrouping: Boolean get() = mergeManager.suggestGroupingOnAdd

    /** Group ids for the duplicate dialog, which collapses same-group duplicates into one card. */
    suspend fun getDuplicateGroupIds(duplicates: List<NovelWithChapterCount>): Map<Long, Long> =
        mergeManager.groupIdsFor(duplicates.map { it.novel.id })

    /**
     * File [novelId] into the categories its new group already uses, so a new source lands where the rest
     * of the series lives. Returns whether it filed any (false when the group is uncategorized).
     */
    suspend fun seedCategoriesFromGroup(novelId: Long, memberIds: List<Long>): Boolean {
        val categoryIds = memberIds
            .flatMap { getNovelCategories.awaitByNovelId(it) }
            .map { it.id }
            .filter { it > 0L }
            .distinct()
        if (categoryIds.isEmpty()) return false
        setNovelCategories.await(novelId, categoryIds)
        return true
    }

    /**
     * Add the item and merge it into the group of the duplicates the user picked. Only the picks: the
     * duplicate list is fuzzy, and one member is enough since the merge absorbs that member's whole group.
     * The new source joins the group's own categories when it has any; only an uncategorized group falls
     * back to the default (or the picker).
     *
     * Favorites first, unlike the manga twin: a browse item has no library row until [favoriteReturningId]
     * inserts one, and both the merge and the category seeding need its id.
     */
    suspend fun addToExistingGroup(item: NovelItem, sourceId: String, selectedIds: List<Long>): NovelBrowseDialog? {
        val storedId = favoriteReturningId(item, sourceId) ?: return null
        mergeManager.mergeNovels(listOf(storedId) + selectedIds)
        if (seedCategoriesFromGroup(storedId, selectedIds)) return null
        return applyDefaultCategoryOrPrompt(storedId)?.let { prompt ->
            NovelBrowseDialog.ChangeCategory(storedId, prompt.categories, prompt.currentIds)
        }
    }

    /** Insert + favorite the item, returning its stored novel id and skipping the category prompt. The
     *  bulk add path favorites many items this way, then applies one category set to all. insertOrGet may
     *  return a non-favorite shadow row from a prior details open, so favorite is applied as a follow-up. */
    suspend fun favoriteReturningId(item: NovelItem, sourceId: String): Long? {
        val base = Novel.create().copy(
            source = sourceId,
            url = item.path,
            title = item.name,
            thumbnailUrl = item.cover,
        )
        val stored = novelRepository.insertOrGet(base) ?: return null
        if (!stored.favorite) {
            updateNovel.awaitUpdateFavorite(stored.id, favorite = true)
        }
        return stored.id
    }

    /**
     * Shared "land a freshly favorited novel in the right category" step, the novel twin of the manga
     * default-category branch (MangaLibraryAdder / MangaScreenModel.toggleFavorite). Applies the
     * configured [NovelPreferences.defaultNovelCategory] (or uncategorized) and returns null; when
     * there's no usable default but the user has categories, returns the picker data for the caller to
     * render its own dialog. Reused by the History add-to-library button.
     */
    suspend fun applyDefaultCategoryOrPrompt(novelId: Long): CategoryPrompt? {
        val categories = getNovelCategories.await().filter { it.id > 0L }
        val defaultId = novelPreferences.defaultNovelCategory().get()
        val target = categories.find { it.id == defaultId.toLong() }
        return when {
            target != null -> {
                setNovelCategories.await(novelId, listOf(target.id))
                null
            }
            defaultId == 0 || categories.isEmpty() -> {
                setNovelCategories.await(novelId, emptyList())
                null
            }
            else -> {
                val current = getNovelCategories.awaitByNovelId(novelId).map { it.id }.toSet()
                CategoryPrompt(categories, current)
            }
        }
    }

    suspend fun applyCategories(novelId: Long, categoryIds: List<Long>) {
        setNovelCategories.await(novelId, categoryIds)
    }

    /** Remove a favorited result from the library (keeps the row + read state, like the manga side). */
    suspend fun confirmRemove(item: NovelItem, sourceId: String) {
        novelRepository.getByUrlAndSource(item.path, sourceId)?.let {
            updateNovel.awaitUpdateFavorite(it.id, favorite = false)
        }
    }
}

/** The category-picker data [NovelLibraryAdder.applyDefaultCategoryOrPrompt] returns when there is no
 *  default category to apply; each caller wraps it in its own dialog type. */
data class CategoryPrompt(val categories: List<NovelCategory>, val currentIds: Set<Long>)

/** The possible-duplicate data [NovelLibraryAdder.findDuplicates] returns; each add-path wraps it in
 *  its own dialog type to feed the shared [DuplicateNovelDialog]. */
data class NovelDuplicateInfo(
    val duplicates: List<NovelWithChapterCount>,
    val sourceNames: Map<String, String>,
    val sourceSites: Map<String, String?>,
)
