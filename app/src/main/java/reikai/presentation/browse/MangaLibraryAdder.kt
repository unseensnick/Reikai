package reikai.presentation.browse

import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.util.removeCovers
import kotlinx.coroutines.flow.firstOrNull
import reikai.domain.category.resolveDefaultCategoryIds
import reikai.domain.db.Transactions
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.manga.MangaMergeManager
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
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.model.toMangaUpdate
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Clock

/**
 * Shared long-press "add to library" orchestration for any manga browse surface (the per-source
 * Browse screen and cross-source global search). Extracted from `BrowseSourceViewModel` so both
 * reuse one implementation. Returns plain results ([AddFavoriteResult]) rather than a screen's Dialog
 * type, so each caller maps to its own dialog. The source is resolved per-manga
 * ([SourceManager.getOrStub] on `manga.source`) so it works in global search, where results span
 * sources (Browse's single source equals each result's source, so behaviour there is unchanged).
 */
class MangaLibraryAdder(
    private val sourceManager: SourceManager = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    private val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val addTracks: AddTracks = Injekt.get(),
    // RK: add-time grouping (the suggestion gate + the merge into the duplicate's group).
    private val mergeManager: MangaMergeManager = Injekt.get(),
    private val transactions: Transactions = Injekt.get(),
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences = Injekt.get(),
) {

    /** RK: whether to offer add-time grouping in the duplicate dialog (see [MangaMergeManager]). */
    val suggestGrouping: Boolean get() = mergeManager.suggestGroupingOnAdd

    /** RK: group ids for the duplicate dialog, which collapses same-group duplicates into one card. */
    suspend fun getDuplicateGroupIds(duplicates: List<MangaWithChapterCount>): Map<Long, Long> =
        mergeManager.groupIdsFor(duplicates.map { it.manga.id })

    /**
     * RK: file [mangaId] into the categories its new group already uses, so a new source lands where the
     * rest of the series lives. Returns whether it filed any (false when the group is uncategorized).
     */
    suspend fun seedCategoriesFromGroup(mangaId: Long, memberIds: List<Long>): Boolean {
        val categoryIds = memberIds.flatMap { getCategories.await(it) }.map { it.id }.filter { it != 0L }.distinct()
        if (categoryIds.isEmpty()) return false
        setMangaCategories.await(mangaId, categoryIds)
        return true
    }

    /**
     * RK: merge [manga] with the user's picked duplicates, then favorite it. Only the picks: the duplicate
     * list is fuzzy, and one member is enough since the merge absorbs that member's whole group.
     * Favorites up front (before any category choice) so an abandoned choice can't leave a merged-but-
     * unfavorited copy feeding chapters into the group while invisible in the library. The new source
     * joins the group's own categories when it has any; only an uncategorized group falls back to the
     * default (or the picker, shown with `alreadyFavorited` so its confirm doesn't re-toggle the favorite).
     */
    suspend fun addToExistingGroup(manga: Manga, selectedIds: List<Long>): AddFavoriteResult {
        // Null means the favorite write failed, so nothing was written at all, not even the merge.
        // No category prompt then: there is no library entry to file.
        val seeded = addToGroup(manga, selectedIds) ?: return AddFavoriteResult.Added
        setMangaDefaultChapterFlags.await(manga)
        addTracks.bindEnhancedTrackers(manga, sourceManager.getOrStub(manga.source))
        if (seeded) return AddFavoriteResult.Added
        return applyDefaultCategoryOrPrompt(manga)
    }

    /**
     * RK: favorite [manga] and merge it into [selectedIds]'s group as ONE unit, then file it into
     * that group's categories. Null when the row is gone or the write failed. Atomic because
     * membership is not favorite-filtered: a merged copy that never got favorited feeds the group
     * while invisible in the library, with nothing able to unmerge it. An already favorited row is
     * not re-written (that would reset dateAdded), and the row is re-read because a stale snapshot
     * would skip the write and still merge. Twin of `NovelLibraryAdder.addToGroup`.
     */
    suspend fun addToGroup(manga: Manga, selectedIds: List<Long>): Boolean? {
        val stored = getManga.await(manga.id) ?: return null
        val favorited = transactions.run {
            val ok = stored.favorite || updateManga.awaitUpdateFavorite(manga.id, true)
            if (ok) mergeManager.merge(listOf(manga.id) + selectedIds)
            ok
        }
        if (!favorited) return null
        return seedCategoriesFromGroup(manga.id, selectedIds)
    }

    /**
     * Toggle a manga's favorite state, answering whether the write landed. On favorite: apply default
     * chapter flags + bind enhanced trackers; on unfavorite: drop cached covers. The add sequence
     * abandons the add when this answers false, so nothing is filed against a row outside the library.
     */
    suspend fun changeFavorite(manga: Manga): Boolean {
        var new = manga.copy(
            favorite = !manga.favorite,
            dateAdded = if (manga.favorite) 0 else Clock.System.now().toEpochMilliseconds(),
        )
        if (!new.favorite) {
            // RK: its own copy of the group's shared tracker, before it leaves; the hand-out skips
            //     non-favorites, so after the write it would miss exactly this entry.
            mergeManager.handOutTrackersBeforeRemoval(listOf(manga.id))
            new = new.removeCovers(coverCache)
        } else {
            setMangaDefaultChapterFlags.await(manga)
            addTracks.bindEnhancedTrackers(manga, sourceManager.getOrStub(manga.source))
        }
        return updateManga.await(new.toMangaUpdate())
    }

    suspend fun getDuplicates(manga: Manga): List<MangaWithChapterCount> =
        getDuplicateLibraryManga.invoke(manga)

    /**
     * RK: each duplicate's source, resolved here so no dialog host needs a [SourceManager] of its own.
     * A stub source means the extension is not installed, which the duplicate card warns about.
     */
    fun duplicateSourceLabels(duplicates: List<MangaWithChapterCount>): Map<Long, EntrySourceLabel> =
        duplicates.associate { duplicate ->
            val source = sourceManager.getOrStub(duplicate.manga.source)
            duplicate.manga.source to when (source) {
                is StubSource -> EntrySourceLabel.Missing(source.name)
                else -> EntrySourceLabel.Installed(source.name)
            }
        }

    suspend fun moveToCategories(manga: Manga, categoryIds: List<Long>) {
        setMangaCategories.await(manga.id, categoryIds.filter { it != 0L })
    }

    /**
     * RK: add to library through the shared sequence ([addEntry]): decide, favorite, then file. With no
     * usable default the caller shows its own picker, whose confirm owes both writes, so backing out of
     * it adds nothing.
     */
    suspend fun resolveAddFavorite(manga: Manga): AddFavoriteResult {
        val outcome = addEntry(
            resolveCategories = { resolveDefaultCategories() },
            favorite = { manga.id.takeIf { changeFavorite(manga) } },
            fileCategories = { _, categoryIds -> moveToCategories(manga, categoryIds) },
        )
        return when (outcome) {
            AddOutcome.Added -> AddFavoriteResult.Added
            AddOutcome.Failed -> AddFavoriteResult.Failed
            AddOutcome.NeedsCategoryChoice ->
                AddFavoriteResult.NeedsCategoryChoice(categoryPickerSelection(manga.id))
        }
    }

    /**
     * RK: where a new favorite should land, or null when the user has to be asked. Reads only, so a
     * caller can favorite between this and [moveToCategories]; the two cannot be split apart once
     * [applyDefaultCategoryOrPrompt] has joined them. Twin of `NovelLibraryAdder.resolveDefaultCategories`.
     */
    suspend fun resolveDefaultCategories(): List<Long>? =
        resolveDefaultCategoryIds(getUserCategories(), libraryPreferences.defaultCategory.get())

    /** RK: the picker's initial state for [mangaId], its current categories checked. Reads only. */
    suspend fun categoryPickerSelection(mangaId: Long): List<CheckboxState.State<Category>> {
        val preselectedIds = getCategories.await(mangaId).map { it.id }
        return getUserCategories().mapAsCheckboxState { it.id in preselectedIds }
    }

    /**
     * RK: file [manga] into its default category (or none), or return the picker data when the user must
     * choose. Never toggles favorite: the two add-paths favorite at different points ([resolveAddFavorite]
     * after, [addToExistingGroup] up front), so favoriting is the caller's job.
     */
    private suspend fun applyDefaultCategoryOrPrompt(manga: Manga): AddFavoriteResult {
        val directIds = resolveDefaultCategories()
        return if (directIds != null) {
            moveToCategories(manga, directIds)
            AddFavoriteResult.Added
        } else {
            AddFavoriteResult.NeedsCategoryChoice(categoryPickerSelection(manga.id))
        }
    }

    /**
     * RK: user categories, excluding the system default, ordered by the category sort-order preference
     * so every picker lists them the way the library and the details picker do, not in table order.
     */
    suspend fun getUserCategories(): List<Category> = reikaiSortCategories(
        categories = getCategories.subscribe().firstOrNull()?.filterNot { it.isSystemCategory }.orEmpty(),
        sortOrder = reikaiLibraryPreferences.categorySortOrder.get(),
    )
}

/**
 * Outcome of [MangaLibraryAdder.resolveAddFavorite]: added outright, awaiting a category choice, or
 * abandoned because the favorite write failed, in which case nothing was written at all.
 */
sealed interface AddFavoriteResult {
    data object Added : AddFavoriteResult
    data object Failed : AddFavoriteResult
    data class NeedsCategoryChoice(
        val initialSelection: List<CheckboxState.State<Category>>,
    ) : AddFavoriteResult
}
