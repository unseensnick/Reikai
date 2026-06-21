package reikai.presentation.browse

import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.util.removeCovers
import kotlinx.coroutines.flow.firstOrNull
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.model.toMangaUpdate
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant

/**
 * Shared long-press "add to library" orchestration for any manga browse surface (the per-source
 * Browse screen and cross-source global search). Extracted from `BrowseSourceScreenModel` so both
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
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    private val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val addTracks: AddTracks = Injekt.get(),
) {

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
        val categories = getUserCategories()
        val defaultCategoryId = libraryPreferences.defaultCategory.get()
        val defaultCategory = categories.find { it.id == defaultCategoryId.toLong() }

        return when {
            defaultCategory != null -> {
                moveToCategories(manga, listOf(defaultCategory.id))
                changeFavorite(manga)
                AddFavoriteResult.Added
            }
            defaultCategoryId == 0 || categories.isEmpty() -> {
                moveToCategories(manga, emptyList())
                changeFavorite(manga)
                AddFavoriteResult.Added
            }
            else -> {
                val preselectedIds = getCategories.await(manga.id).map { it.id }
                AddFavoriteResult.NeedsCategoryChoice(
                    categories.mapAsCheckboxState { it.id in preselectedIds },
                )
            }
        }
    }

    /** User categories, excluding the system default. */
    private suspend fun getUserCategories(): List<Category> =
        getCategories.subscribe().firstOrNull()?.filterNot { it.isSystemCategory }.orEmpty()
}

/** Outcome of [MangaLibraryAdder.resolveAddFavorite]: added outright, or awaiting a category choice. */
sealed interface AddFavoriteResult {
    data object Added : AddFavoriteResult
    data class NeedsCategoryChoice(
        val initialSelection: List<CheckboxState.State<Category>>,
    ) : AddFavoriteResult
}
