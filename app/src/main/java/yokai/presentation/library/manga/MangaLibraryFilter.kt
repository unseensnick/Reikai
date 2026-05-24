package yokai.presentation.library.manga

import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.database.models.seriesType
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.library.models.LibraryItem
import eu.kanade.tachiyomi.util.isLocal
import yokai.util.isLewd

/**
 * Phase 3 filter helper. Faithful behavioral port of `LibraryPresenter.matchesFilters` and
 * `matchesFilterTracking` so the Compose path applies the same include / exclude semantics as the
 * legacy library, against the same preference int values (0 = ignore, 1 = include, 2 = exclude;
 * unread additionally uses 3 / 4 for read-progress states).
 *
 * Pure functions, no Injekt. Downstream dependencies (download manager, track interactor, source
 * manager, logged-services map) are passed in so this file is unit-testable in isolation.
 */
object MangaLibraryFilter {

    const val STATE_IGNORE = 0
    const val STATE_INCLUDE = 1
    const val STATE_EXCLUDE = 2

    data class MangaFilterState(
        val downloaded: Int = STATE_IGNORE,
        val unread: Int = STATE_IGNORE,
        val completed: Int = STATE_IGNORE,
        val tracked: Int = STATE_IGNORE,
        val mangaType: Int = 0,
        val contentType: Int = STATE_IGNORE,
        val bookmarked: Int = STATE_IGNORE,
        val tracker: String = "",
    ) {
        val isAnyActive: Boolean
            get() = downloaded != STATE_IGNORE ||
                unread != STATE_IGNORE ||
                completed != STATE_IGNORE ||
                tracked != STATE_IGNORE ||
                mangaType != 0 ||
                contentType != STATE_IGNORE ||
                bookmarked != STATE_IGNORE
    }

    suspend fun filter(
        library: Map<Category, List<LibraryItem.Manga>>,
        state: MangaFilterState,
        sourceManager: SourceManager,
        loggedServiceNames: Map<Long, String>,
        getDownloadCount: (Manga) -> Int,
        getTracks: suspend (Long) -> List<Track>,
        isLewd: (Manga) -> Boolean = { it.isLewd() },
        /**
         * When true, categories whose items were entirely filtered out are kept as empty entries
         * rather than dropped. Wired to the `showAllCategories` preference, which asks for every
         * category header to remain visible regardless of filter state. Distinct from the screen-
         * level `showEmptyCategoriesWhileFiltering` toggle, which re-introduces empty entries
         * only when the user is actively narrowing the library.
         */
        keepEmptyCategories: Boolean = false,
    ): Map<Category, List<LibraryItem.Manga>> {
        if (!state.isAnyActive) return library
        val filtered = library.mapValues { (_, items) ->
            items.filter {
                matches(it, state, sourceManager, loggedServiceNames, getDownloadCount, getTracks, isLewd)
            }
        }
        return if (keepEmptyCategories) filtered else filtered.filterValues { it.isNotEmpty() }
    }

    /**
     * Walk the library to find which non-MANGA series types are present, so the filter UI can
     * conditionally render manga-type chips only for types the user actually has. Mirrors the
     * legacy `FilterBottomSheet.checkForManhwa`: TYPE_WEBTOON folds into TYPE_MANHWA, stops after
     * collecting all three non-MANGA buckets.
     */
    fun detectMangaTypes(
        library: Map<Category, List<LibraryItem.Manga>>,
        sourceManager: SourceManager,
    ): Set<Int> {
        val out = LinkedHashSet<Int>(3)
        for (items in library.values) {
            for (item in items) {
                when (item.libraryManga.manga.seriesType(sourceManager = sourceManager)) {
                    Manga.TYPE_MANHWA, Manga.TYPE_WEBTOON -> out.add(Manga.TYPE_MANHWA)
                    Manga.TYPE_MANHUA -> out.add(Manga.TYPE_MANHUA)
                    Manga.TYPE_COMIC -> out.add(Manga.TYPE_COMIC)
                }
                if (out.size == 3) return out
            }
        }
        return out
    }

    private suspend fun matches(
        item: LibraryItem.Manga,
        state: MangaFilterState,
        sourceManager: SourceManager,
        loggedServiceNames: Map<Long, String>,
        getDownloadCount: (Manga) -> Int,
        getTracks: suspend (Long) -> List<Track>,
        isLewd: (Manga) -> Boolean,
    ): Boolean {
        val libraryManga = item.libraryManga
        val manga = libraryManga.manga

        if (state.unread == STATE_INCLUDE && libraryManga.unread == 0) return false
        if (state.unread == STATE_EXCLUDE && libraryManga.unread > 0) return false
        // 3 / 4 are the read-progress refinement encoded into the same preference.
        if (state.unread == 3 && !(libraryManga.unread > 0 && !libraryManga.hasRead)) return false
        if (state.unread == 4 && !(libraryManga.unread > 0 && libraryManga.hasRead)) return false

        if (state.bookmarked == STATE_INCLUDE && libraryManga.bookmarkCount == 0) return false
        if (state.bookmarked == STATE_EXCLUDE && libraryManga.bookmarkCount > 0) return false

        if (state.mangaType > 0) {
            val seriesType = manga.seriesType(sourceManager = sourceManager)
            val mismatch = if (state.mangaType == Manga.TYPE_MANHWA) {
                // TYPE_WEBTOON is folded into TYPE_MANHWA for the chip selection.
                seriesType !in arrayOf(Manga.TYPE_MANHWA, Manga.TYPE_WEBTOON)
            } else {
                seriesType != state.mangaType
            }
            if (mismatch) return false
        }

        if (state.completed == STATE_INCLUDE && manga.status != SManga.COMPLETED) return false
        if (state.completed == STATE_EXCLUDE && manga.status == SManga.COMPLETED) return false

        if (!matchesTracking(item, state.tracked, state.tracker, loggedServiceNames, getTracks)) return false

        if (state.downloaded != STATE_IGNORE) {
            val isDownloaded = when {
                manga.isLocal() -> true
                item.downloadCount != -1L -> item.downloadCount > 0
                else -> getDownloadCount(manga) > 0
            }
            return if (state.downloaded == STATE_INCLUDE) isDownloaded else !isDownloaded
        }

        if (state.contentType == STATE_INCLUDE) return !isLewd(manga)
        if (state.contentType == STATE_EXCLUDE) return isLewd(manga)
        return true
    }

    private suspend fun matchesTracking(
        item: LibraryItem.Manga,
        filterTracked: Int,
        filterTracker: String,
        loggedServiceNames: Map<Long, String>,
        getTracks: suspend (Long) -> List<Track>,
    ): Boolean {
        if (filterTracked == STATE_IGNORE) return true
        val mangaId = item.libraryManga.manga.id ?: return true
        val tracks = getTracks(mangaId)
        val hasTrack = loggedServiceNames.keys.any { svcId -> tracks.any { it.sync_id == svcId } }
        val matchedServiceId = filterTracker
            .takeIf { it.isNotEmpty() }
            ?.let { name -> loggedServiceNames.entries.firstOrNull { it.value == name }?.key }

        if (filterTracked == STATE_INCLUDE) {
            if (!hasTrack) return false
            if (matchedServiceId != null) {
                val hasServiceTrack = tracks.any { it.sync_id == matchedServiceId }
                if (!hasServiceTrack) return false
            }
        } else if (filterTracked == STATE_EXCLUDE) {
            if (hasTrack && filterTracker.isEmpty()) return false
            if (matchedServiceId != null) {
                val hasServiceTrack = tracks.any { it.sync_id == matchedServiceId }
                if (hasServiceTrack) return false
            }
        }
        return true
    }
}
