package reikai.presentation.browse

import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.util.removeCovers
import kotlinx.coroutines.flow.firstOrNull
import reikai.domain.category.resolveDefaultCategoryIds
import reikai.domain.db.Transactions
import reikai.domain.manga.MangaMergeManager
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
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant

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
     * Toggle a manga's favorite state. On favorite: apply default chapter flags + bind enhanced
     * trackers; on unfavorite: drop cached covers.
     */
    suspend fun changeFavorite(manga: Manga) {
        var new = manga.copy(
            favorite = !manga.favorite,
            dateAdded = if (manga.favorite) 0 else Instant.now().toEpochMilli(),
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
        updateManga.await(new.toMangaUpdate())
    }

    suspend fun getDuplicates(manga: Manga): List<MangaWithChapterCount> =
        getDuplicateLibraryManga.invoke(manga)

    suspend fun moveToCategories(manga: Manga, categoryIds: List<Long>) {
        setMangaCategories.await(manga.id, categoryIds.filter { it != 0L })
    }

    /**
     * Add to library: with a default category set or no categories, move + favorite directly and
     * return [AddFavoriteResult.Added]; otherwise return [AddFavoriteResult.NeedsCategoryChoice] so
     * the caller can show its own category picker.
     */
    suspend fun resolveAddFavorite(manga: Manga): AddFavoriteResult {
        val result = applyDefaultCategoryOrPrompt(manga)
        if (result is AddFavoriteResult.Added) changeFavorite(manga)
        return result
    }

    /**
     * RK: file [manga] into its default category (or none), or return the picker data when the user must
     * choose. Never toggles favorite: the two add-paths favorite at different points ([resolveAddFavorite]
     * after, [addToExistingGroup] up front), so favoriting is the caller's job.
     */
    private suspend fun applyDefaultCategoryOrPrompt(manga: Manga): AddFavoriteResult {
        val categories = getUserCategories()
        val directIds = resolveDefaultCategoryIds(categories, libraryPreferences.defaultCategory.get())
        return if (directIds != null) {
            moveToCategories(manga, directIds)
            AddFavoriteResult.Added
        } else {
            val preselectedIds = getCategories.await(manga.id).map { it.id }
            AddFavoriteResult.NeedsCategoryChoice(
                categories.mapAsCheckboxState { it.id in preselectedIds },
            )
        }
    }

    /** User categories, excluding the system default. */
    suspend fun getUserCategories(): List<Category> =
        getCategories.subscribe().firstOrNull()?.filterNot { it.isSystemCategory }.orEmpty()
}

/** Outcome of [MangaLibraryAdder.resolveAddFavorite]: added outright, or awaiting a category choice. */
sealed interface AddFavoriteResult {
    data object Added : AddFavoriteResult
    data class NeedsCategoryChoice(
        val initialSelection: List<CheckboxState.State<Category>>,
    ) : AddFavoriteResult
}
