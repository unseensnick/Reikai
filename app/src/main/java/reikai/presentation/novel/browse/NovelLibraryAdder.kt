package reikai.presentation.novel.browse

import dev.zacsweers.metro.Inject
import reikai.domain.category.GetNovelCategories
import reikai.domain.category.resolveDefaultCategoryIds
import reikai.domain.db.Transactions
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.SetNovelCategories
import reikai.domain.novel.interactor.UpdateNovel
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelWithChapterCount
import reikai.novel.host.NovelItem
import reikai.novel.source.NovelSourceManager
import reikai.presentation.browse.AddDecision
import reikai.presentation.browse.AddFavoriteResult
import reikai.presentation.browse.AddOutcome
import reikai.presentation.browse.addEntry
import reikai.presentation.browse.addEntryOrPrompt
import reikai.presentation.browse.components.EntrySourceLabel
import reikai.presentation.browse.decideAdd
import reikai.presentation.browse.finishAdd
import reikai.presentation.library.reikaiSortCategories
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.domain.category.model.Category

/**
 * Shared long-press "add to library" flow for any novel browse surface (per-source browse and
 * cross-source global search). Stateless: each method returns the next [NovelBrowseDialog] to show
 * (or null to dismiss) so each caller keeps ownership of its own dialog state. The source id is passed
 * per call because per-source browse has a fixed source while global search has one per result.
 */
@Inject
class NovelLibraryAdder(
    private val novelRepository: NovelRepository,
    private val manager: NovelSourceManager,
    private val getNovelCategories: GetNovelCategories,
    private val setNovelCategories: SetNovelCategories,
    private val updateNovel: UpdateNovel,
    private val novelPreferences: NovelPreferences,
    private val mergeManager: NovelMergeManager,
    private val transactions: Transactions,
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences,
) {

    /** Decide the long-press outcome: remove (already saved), confirm a possible duplicate, or add. */
    suspend fun onLongClick(
        item: NovelItem,
        sourceId: String,
        favoritedKeys: Set<Pair<String, String>>,
    ): NovelBrowseDialog? {
        val decision = decideAdd(
            inLibrary = (sourceId to item.path) in favoritedKeys,
            // -1: the item isn't favorited yet, so there's no library row to exclude (a non-favorite
            // shadow row is excluded by the query's favorite=1 filter anyway).
            findDuplicates = { findDuplicates(-1L, item.name) },
        )
        return when (decision) {
            AddDecision.Remove -> NovelBrowseDialog.RemoveNovel(item, sourceId)
            is AddDecision.ConfirmDuplicate -> NovelBrowseDialog.AddDuplicate(
                item = item,
                sourceId = sourceId,
                duplicates = decision.duplicates.duplicates,
                sourceLabels = decision.duplicates.sourceLabels,
                sourceSites = decision.duplicates.sourceSites,
                suggestGroup = suggestGrouping,
                groupIdByNovelId = getDuplicateGroupIds(decision.duplicates.duplicates),
            )
            AddDecision.Add -> addToLibrary(item, sourceId)
        }
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
        // Resolve names + sites here so each dialog host stays DI-free. A source the manager cannot
        // answer for is not installed, so only its stored key is known and the card warns about it.
        val resolved = duplicates.associate { it.novel.source to manager.get(it.novel.source) }
        return NovelDuplicateInfo(
            duplicates = duplicates,
            sourceLabels = resolved.mapValues { (key, src) ->
                src?.let { EntrySourceLabel.Installed(it.name) } ?: EntrySourceLabel.Missing(key)
            },
            sourceSites = resolved.mapValues { (_, src) -> src?.site },
        )
    }

    /**
     * Add the browsed item through the shared sequence ([addEntry]): decide, favorite, then file. The
     * row is only created by the favorite step, so a picker returned here is still pending: nothing is
     * written until its confirm reaches [confirmCategories].
     */
    suspend fun addToLibrary(item: NovelItem, sourceId: String): NovelBrowseDialog? {
        val outcome = addEntry(
            resolveCategories = { resolveDefaultCategories() },
            favorite = { favoriteReturningId(item, sourceId) },
            fileCategories = { id, categoryIds -> applyCategories(id, categoryIds) },
        )
        if (outcome != AddOutcome.NeedsCategoryChoice) return null
        return NovelBrowseDialog.ChangeCategory(
            NovelCategoryTarget.Pending(item, sourceId),
            categoryPickerPrompt(item, sourceId),
        )
    }

    /**
     * The writes a browse picker's confirm owes. A [NovelCategoryTarget.Pending] add has written
     * nothing yet, so this creates the row, favorites it and files it; add-time grouping favorited up
     * front and passes [NovelCategoryTarget.Stored], which only files.
     */
    suspend fun confirmCategories(target: NovelCategoryTarget, categoryIds: List<Long>): AddOutcome =
        finishAdd(
            categoryIds = categoryIds,
            favorite = {
                when (target) {
                    is NovelCategoryTarget.Stored -> target.novelId
                    is NovelCategoryTarget.Pending -> favoriteReturningId(target.item, target.sourceId)
                }
            },
            fileCategories = { id, ids -> applyCategories(id, ids) },
        )

    /**
     * The writes a picker's confirm owes for a stored row, in the shared order, so backing out adds
     * nothing and the row is favorited only when the user confirms. Distinct from [confirmCategories]
     * above, whose `Stored` case deliberately does not favorite because add-time grouping already did.
     * Twin of `MangaLibraryAdder.confirmAddCategories`, pinned by `AddToGroupConformanceTest`.
     */
    suspend fun confirmAddCategories(novelId: Long, categoryIds: List<Long>): AddOutcome = finishAdd(
        categoryIds = categoryIds,
        favorite = { favoriteForAdd(novelId) },
        fileCategories = { id, ids -> applyCategories(id, ids) },
    )

    /**
     * Favorite [novelId] for an add, answering its id, or null when the row is gone or the write
     * failed. An already-favorited row is not re-written: that would reset dateAdded, moving the entry
     * in a date-added sort for something the user did not do. Twin of `MangaLibraryAdder.favoriteForAdd`,
     * pinned by `AddToGroupConformanceTest`'s confirm cases.
     */
    suspend fun favoriteForAdd(novelId: Long): Long? {
        val novel = novelRepository.getById(novelId) ?: return null
        if (novel.favorite) return novelId
        return novelId.takeIf { updateNovel.awaitUpdateFavorite(novelId, favorite = true) }
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
            .filter { it != Category.UNCATEGORIZED_ID }
            .distinct()
        if (categoryIds.isEmpty()) return false
        setNovelCategories.await(novelId, categoryIds)
        return true
    }

    /**
     * Add the item and merge it into the group of the duplicates the user picked. Only the picks, since
     * the duplicate list is fuzzy, and one member is enough because the merge absorbs that member's
     * whole group. The new source joins the group's own categories when it has any. Favorites first,
     * unlike the manga twin: a browse item has no library row until [favoriteReturningId] inserts one,
     * and both the merge and the category seeding need its id.
     */
    suspend fun addToExistingGroup(item: NovelItem, sourceId: String, selectedIds: List<Long>): NovelBrowseDialog? {
        // Inserted first, unfavorited: a browse item has no library row to favorite yet, and an
        // insert on its own leaves nothing a user can see. The favorite is part of the pair below.
        val storedId = materialize(item, sourceId)?.id ?: return null
        val seeded = addToGroup(storedId, selectedIds) ?: return null
        if (seeded) return null
        return applyDefaultCategoryOrPrompt(storedId)?.let { selection ->
            NovelBrowseDialog.ChangeCategory(NovelCategoryTarget.Stored(storedId), selection)
        }
    }

    /**
     * Add a novel that already has a library row, through the shared sequence. Twin of
     * `MangaLibraryAdder.resolveAddFavorite`, both over the `addEntryOrPrompt` kernel, for the stored-row
     * case its browse twin above cannot serve: nothing is inserted here, only favorited and filed.
     */
    suspend fun addStoredToLibrary(novelId: Long): AddFavoriteResult = addEntryOrPrompt(
        resolveCategories = { resolveDefaultCategories() },
        favorite = { favoriteForAdd(novelId) },
        fileCategories = { id, categoryIds -> applyCategories(id, categoryIds) },
        categoryPicker = { categoryPickerPrompt(novelId) },
    )

    /**
     * The stored-row twin of the browse [addToExistingGroup] above, answering the shared result type
     * instead of a browse dialog. The group's own categories win; only an uncategorized group falls
     * back to the default or the picker.
     */
    suspend fun addToExistingGroup(novelId: Long, selectedIds: List<Long>): AddFavoriteResult {
        val seeded = addToGroup(novelId, selectedIds) ?: return AddFavoriteResult.Failed
        if (seeded) return AddFavoriteResult.Added
        return applyDefaultCategoryOrPrompt(novelId)
            ?.let { AddFavoriteResult.NeedsCategoryChoice(it) }
            ?: AddFavoriteResult.Added
    }

    /**
     * Favorite the novel and merge it into [selectedIds]'s group as ONE unit, then file it into that
     * group's categories. Returns whether any were seeded, null when the row is gone or the write
     * failed. Twin of `MangaLibraryAdder.addToGroup`, which carries the why; pinned by `AddToGroupConformanceTest`.
     *
     * An already-favorited row is not re-written: that would reset dateAdded, moving the entry in a
     * date-added sort for what the user did as a grouping change.
     */
    suspend fun addToGroup(novelId: Long, selectedIds: List<Long>): Boolean? {
        val novel = novelRepository.getById(novelId) ?: return null
        val favorited = transactions.run {
            val ok = novel.favorite || updateNovel.awaitUpdateFavorite(novelId, favorite = true)
            if (ok) mergeManager.merge(listOf(novelId) + selectedIds)
            ok
        }
        if (!favorited) return null
        return seedCategoriesFromGroup(novelId, selectedIds)
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

    /** Insert-or-get the browsed [item] as a library row and return it, without favoriting, for the
     *  migrate-from-duplicate flow (the migrate use case favorites + chapter-syncs the target itself). */
    suspend fun materialize(item: NovelItem, sourceId: String): Novel? {
        val base = Novel.create().copy(
            source = sourceId,
            url = item.path,
            title = item.name,
            thumbnailUrl = item.cover,
        )
        return novelRepository.insertOrGet(base)
    }

    /**
     * Shared "land a freshly favorited novel in the right category" step, the novel twin of the manga
     * default-category branch (MangaLibraryAdder / MangaViewModel.toggleFavorite). Applies the
     * configured [NovelPreferences.defaultNovelCategory] (or uncategorized) and returns null; when
     * there's no usable default but the user has categories, returns the picker data for the caller to
     * render its own dialog. Reused by the History add-to-library button.
     */
    suspend fun applyDefaultCategoryOrPrompt(novelId: Long): List<CheckboxState.State<Category>>? {
        val directIds = resolveDefaultCategories()
        return if (directIds != null) {
            setNovelCategories.await(novelId, directIds)
            null
        } else {
            categoryPickerPrompt(novelId)
        }
    }

    /**
     * Where a new favorite should land, or null when the user has to be asked. Reads only, so a caller
     * can favorite between this and [applyCategories]; the two cannot be split apart once
     * [applyDefaultCategoryOrPrompt] has joined them. Twin of `MangaLibraryAdder.resolveDefaultCategories`;
     * both call the `resolveDefaultCategoryIds` kernel, pinned by `AddDecisionConformanceTest`.
     */
    suspend fun resolveDefaultCategories(): List<Long>? =
        resolveDefaultCategoryIds(userCategories(), novelPreferences.defaultNovelCategory().get())

    /**
     * The picker's data for a browse item with no library row yet. A row can still exist unfavorited
     * from an earlier add, so its categories are preselected rather than assumed empty. Reads only.
     */
    suspend fun categoryPickerPrompt(item: NovelItem, sourceId: String): List<CheckboxState.State<Category>> {
        val existingId = novelRepository.getByUrlAndSource(item.path, sourceId)?.id
        return existingId?.let { categoryPickerPrompt(it) } ?: userCategories().mapAsCheckboxState { false }
    }

    /** The picker's initial state for [novelId], its current categories checked. Reads only. */
    suspend fun categoryPickerPrompt(novelId: Long): List<CheckboxState.State<Category>> {
        val current = getNovelCategories.awaitByNovelId(novelId).map { it.id }.toSet()
        return userCategories().mapAsCheckboxState { it.id in current }
    }

    /**
     * The pickable categories: the system default (id 0) is not one a user can file into. Ordered by
     * the category sort-order preference, so every picker lists them the way the library and the
     * details picker do rather than in table order.
     */
    suspend fun userCategories(): List<Category> = reikaiSortCategories(
        categories = getNovelCategories.await().filterNot { it.isSystemCategory },
        sortOrder = reikaiLibraryPreferences.categorySortOrder.get(),
    )

    /** The system category is not one a user can file into, so it never reaches a write. */
    suspend fun applyCategories(novelId: Long, categoryIds: List<Long>) {
        setNovelCategories.await(novelId, categoryIds.filter { it != Category.UNCATEGORIZED_ID })
    }

    /** Remove a favorited result from the library (keeps the row + read state, like the manga side). */
    suspend fun confirmRemove(item: NovelItem, sourceId: String) {
        novelRepository.getByUrlAndSource(item.path, sourceId)?.let {
            // Its own copy of the group's shared tracker, before it leaves: the hand-out skips
            // non-favorites, so after the write it would miss exactly this entry.
            mergeManager.handOutTrackersBeforeRemoval(listOf(it.id))
            updateNovel.awaitUpdateFavorite(it.id, favorite = false)
        }
    }
}

/** The possible-duplicate data [NovelLibraryAdder.findDuplicates] returns; each add-path wraps it in
 *  its own dialog type to feed the shared `EntryDuplicateDialog`. */
data class NovelDuplicateInfo(
    val duplicates: List<NovelWithChapterCount>,
    val sourceLabels: Map<String, EntrySourceLabel>,
    val sourceSites: Map<String, String?>,
)
