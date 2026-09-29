package reikai.presentation.browse

import dev.zacsweers.metro.Inject
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.util.removeCovers
import kotlinx.coroutines.flow.firstOrNull
import reikai.domain.category.resolveDefaultCategoryIds
import reikai.domain.db.Transactions
import reikai.domain.entry.EntryId
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.manga.MangaMergeManager
import reikai.domain.track.autobind.AutoBindOnAdd
import reikai.domain.track.source.SourceTrackerDispatcher
import reikai.presentation.browse.components.EntrySourceLabel
import reikai.presentation.library.reikaiSortCategories
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager

/**
 * Shared long-press "add to library" orchestration for any manga browse surface (the per-source
 * Browse screen and cross-source global search). Extracted from `BrowseSourceViewModel` so both
 * reuse one implementation. Returns plain results ([AddFavoriteResult]) rather than a screen's Dialog
 * type, so each caller maps to its own dialog. The source is resolved per-manga
 * ([SourceManager.getOrStub] on `manga.source`) so it works in global search, where results span
 * sources (Browse's single source equals each result's source, so behaviour there is unchanged).
 */
@Inject
class MangaLibraryAdder(
    private val sourceManager: SourceManager,
    private val coverCache: CoverCache,
    private val libraryPreferences: LibraryPreferences,
    private val getCategories: GetCategories,
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga,
    private val getManga: GetManga,
    private val setMangaCategories: SetMangaCategories,
    private val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags,
    private val updateManga: UpdateManga,
    private val autoBindOnAdd: AutoBindOnAdd,
    // Add-time grouping (the suggestion gate + the merge into the duplicate's group).
    private val mergeManager: MangaMergeManager,
    private val transactions: Transactions,
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences,
    private val sourceTracker: SourceTrackerDispatcher,
) {

    /** Whether to offer add-time grouping in the duplicate dialog (see [MangaMergeManager]). */
    val suggestGrouping: Boolean get() = mergeManager.suggestGroupingOnAdd

    /** Group ids for the duplicate dialog, which collapses same-group duplicates into one card. */
    suspend fun getDuplicateGroupIds(duplicates: List<MangaWithChapterCount>): Map<Long, Long> =
        mergeManager.groupIdsFor(duplicates.map { it.manga.id })

    /**
     * Where an entry joining [selectedIds]'s group lands: the categories that group already uses, so a
     * new source sits with the rest of the series, else the default, else null to ask. Reads only.
     */
    suspend fun groupOrDefaultCategories(selectedIds: List<Long>): List<Long>? =
        selectedIds.flatMap { getCategories.await(it) }
            .map { it.id }
            .filter { it != Category.UNCATEGORIZED_ID }
            .distinct()
            .ifEmpty { null }
            ?: resolveDefaultCategories()

    /**
     * Add [manga] to the library in the group of the user's picked duplicates, through the shared
     * sequence: decide the categories, then write, so a picker it has to raise writes nothing until
     * its confirm reaches [confirmGroupCategories]. Only the picks: the duplicate list is fuzzy, and
     * one member is enough since the merge absorbs that member's whole group.
     */
    suspend fun addToExistingGroup(manga: Manga, selectedIds: List<Long>): AddFavoriteResult = addEntryOrPrompt(
        resolveCategories = { groupOrDefaultCategories(selectedIds) },
        favorite = { joinGroupForAdd(manga, selectedIds) },
        fileCategories = { _, categoryIds -> moveToCategories(manga, categoryIds) },
        categoryPicker = { categoryPickerSelection(manga.id) },
    )

    /** The writes a group add's picker confirm owes, so backing out of the picker adds nothing. */
    suspend fun confirmGroupCategories(manga: Manga, selectedIds: List<Long>, categoryIds: List<Long>): AddOutcome =
        finishAdd(
            categoryIds = categoryIds,
            favorite = { joinGroupForAdd(manga, selectedIds) },
            fileCategories = { _, ids -> moveToCategories(manga, ids) },
        )

    /**
     * A browse picker's confirm: neither add wrote anything before asking, so this owes the favorite,
     * joining [joinGroup]'s group as one unit when the add came from the duplicate dialog's grouping.
     */
    suspend fun confirmPicker(manga: Manga, categoryIds: List<Long>, joinGroup: List<Long>): AddOutcome =
        if (joinGroup.isNotEmpty()) {
            confirmGroupCategories(manga, joinGroup, categoryIds)
        } else {
            confirmAddCategories(manga.id, categoryIds)
        }

    private suspend fun joinGroupForAdd(manga: Manga, selectedIds: List<Long>): Long? =
        joinGroup(manga, selectedIds)?.also { setMangaDefaultChapterFlags.await(manga) }

    /**
     * Favorite [manga] and merge it into [selectedIds]'s group as ONE unit, answering its id, or null
     * when the row is gone or the write failed. Atomic because membership is not favorite-filtered: a
     * merged copy that never got favorited feeds the group while invisible in the library, with nothing
     * able to unmerge it. The row is re-read rather than trusted from a snapshot, which would skip the
     * write and still merge. Trackers bind once it is in. Twin of `NovelLibraryAdder.joinGroup`, pinned by
     * `AddToGroupConformanceTest`.
     */
    suspend fun joinGroup(manga: Manga, selectedIds: List<Long>): Long? {
        val stored = getManga.await(manga.id) ?: return null
        val favorited = transactions.run {
            val ok = stored.favorite || updateManga.awaitUpdateFavorite(manga.id, true)
            if (ok) mergeManager.merge(listOf(manga.id) + selectedIds)
            ok
        }
        if (!favorited) return null
        autoBindOnAdd.manga(manga, sourceManager.getOrStub(manga.source))
        return manga.id
    }

    /**
     * Take [manga] out of the library, dropping its cached covers, answering whether the write landed.
     * Only the library state and the cover stamp are written, never the manga as read here, so nothing
     * read before the write is put back over a newer value (mihon f8fff318b).
     */
    suspend fun removeFromLibrary(manga: Manga): Boolean {
        // Hand this entry its own copy of the group's shared tracker before it leaves; the hand-out
        // skips non-favorites, so after the write it would miss exactly this entry.
        mergeManager.handOutTrackersBeforeRemoval(listOf(manga.id))
        val coverLastModified = manga.removeCovers(coverCache).coverLastModified
        val update = MangaUpdate(manga.id) {
            favoriteAt = null
            if (coverLastModified != manga.coverLastModified) this.coverLastModified = coverLastModified
        }
        // Written here rather than through awaitUpdateFavorite, which cannot carry the cover stamp, so the
        // source's own tracker is told here.
        return updateManga.await(update).also { updated ->
            if (updated) sourceTracker.favoriteChanged(EntryId.Manga(manga.id), favorite = false)
        }
    }

    suspend fun getDuplicates(manga: Manga): List<MangaWithChapterCount> =
        getDuplicateLibraryManga.invoke(manga)

    /**
     * Each duplicate's source, resolved here so no dialog host needs a [SourceManager] of its own.
     * A stub source means the extension is not installed, which the duplicate card warns about.
     */
    suspend fun duplicateSourceLabels(duplicates: List<MangaWithChapterCount>): Map<Long, EntrySourceLabel> =
        duplicates.associate { duplicate ->
            val source = sourceManager.getOrStub(duplicate.manga.source)
            duplicate.manga.source to when (source) {
                is StubSource -> EntrySourceLabel.Missing(source.name)
                else -> EntrySourceLabel.Installed(source.name)
            }
        }

    suspend fun moveToCategories(manga: Manga, categoryIds: List<Long>) {
        setMangaCategories.await(manga.id, categoryIds.filter { it != Category.UNCATEGORIZED_ID })
    }

    /**
     * Add to library through the shared sequence ([addEntry]): decide, favorite, then file. With no
     * usable default the caller shows its own picker, whose confirm owes both writes, so backing out of
     * it adds nothing.
     */
    suspend fun resolveAddFavorite(manga: Manga): AddFavoriteResult = addEntryOrPrompt(
        resolveCategories = { resolveDefaultCategories() },
        favorite = { favoriteFromBrowse(manga.id) },
        fileCategories = { _, categoryIds -> moveToCategories(manga, categoryIds) },
        categoryPicker = { categoryPickerSelection(manga.id) },
    )

    /**
     * The writes a picker's confirm owes for a stored row, in the shared order, so backing out of
     * the picker adds nothing and the row is favorited only when the user confirms. Twin of
     * `NovelLibraryAdder.confirmAddCategories`, pinned by `AddToGroupConformanceTest`'s confirm cases.
     */
    suspend fun confirmAddCategories(mangaId: Long, categoryIds: List<Long>): AddOutcome = finishAdd(
        categoryIds = categoryIds,
        favorite = { favoriteFromBrowse(mangaId) },
        fileCategories = { id, ids -> setMangaCategories.await(id, ids.filter { it != Category.UNCATEGORIZED_ID }) },
    )

    /**
     * The favorite step of every add outside the details page: a newly added row takes the default
     * chapter settings, which the details page stamped already when it opened.
     */
    suspend fun favoriteFromBrowse(mangaId: Long): Long? =
        favoriteForAdd(mangaId) { setMangaDefaultChapterFlags.await(it) }

    /**
     * Add [mangaId] where there is no screen to ask on (a batch add, a shared link, a follows sync): the
     * default category when one is set, uncategorized under "always ask". A row already in the library
     * keeps its categories and date added.
     */
    suspend fun addWithoutAsking(mangaId: Long): Long? = favoriteForAdd(mangaId) { added ->
        setMangaDefaultChapterFlags.await(added)
        resolveDefaultCategories()?.let { moveToCategories(added, it) }
    }

    /** Whether [mangaId] is in the library now, rather than when a list drew it. */
    suspend fun isInLibrary(mangaId: Long): Boolean = getManga.await(mangaId)?.favorite == true

    /**
     * Favorite [mangaId] for an add, answering its id, or null when the row is gone or the write
     * failed. The row is re-read rather than trusted from a snapshot, which can say favorited for an
     * entry unfavorited since and would file categories against a row outside the library. An already
     * favorited row is not re-written: that would reset dateAdded. [onAdded] runs only for a row this
     * call added. Twin of `NovelLibraryAdder.favoriteForAdd`, pinned by `AddToGroupConformanceTest`.
     */
    suspend fun favoriteForAdd(mangaId: Long, onAdded: suspend (Manga) -> Unit = {}): Long? {
        val stored = getManga.await(mangaId) ?: return null
        if (stored.favorite) return mangaId
        if (!updateManga.awaitUpdateFavorite(mangaId, true)) return null
        onAdded(stored)
        autoBindOnAdd.manga(stored, sourceManager.getOrStub(stored.source))
        return mangaId
    }

    /**
     * Where a new favorite should land, or null when the user has to be asked. Reads only, so a
     * caller can favorite between this and [moveToCategories]. Twin of `NovelLibraryAdder.resolveDefaultCategories`;
     * both call the `resolveDefaultCategoryIds` kernel, pinned by `AddDecisionConformanceTest`.
     */
    suspend fun resolveDefaultCategories(): List<Long>? =
        resolveDefaultCategoryIds(getUserCategories(), libraryPreferences.defaultCategory.get())

    /** The picker's initial state for [mangaId], its current categories checked. Reads only. */
    suspend fun categoryPickerSelection(mangaId: Long): List<CheckboxState.State<Category>> {
        val preselectedIds = getCategories.await(mangaId).map { it.id }
        return getUserCategories().mapAsCheckboxState { it.id in preselectedIds }
    }

    /**
     * User categories, excluding the system default, ordered by the category sort-order preference
     * so every picker lists them the way the library and the details picker do, not in table order.
     */
    suspend fun getUserCategories(): List<Category> = reikaiSortCategories(
        categories = getCategories.subscribe().firstOrNull()?.filterNot { it.isSystemCategory }.orEmpty(),
        sortOrder = reikaiLibraryPreferences.categorySortOrder.get(),
    )
}
