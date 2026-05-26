package yokai.presentation.library.novels

import eu.kanade.tachiyomi.data.database.models.NovelCategory
import eu.kanade.tachiyomi.ui.library.models.LibraryItem
import yokai.data.novel.NovelStatusCode
import yokai.domain.novel.models.Novel
import yokai.domain.novel.models.NovelTrack

/**
 * Novel-side parallel of [yokai.presentation.library.manga.MangaLibraryFilter]. Same include /
 * exclude semantics, same int sentinels (0 = ignore, 1 = include, 2 = exclude; `unread` also
 * uses 3 / 4 for the read-progress refinement).
 *
 * Diverges from the manga helper in three places:
 *
 * - **Drops `mangaType` and `contentType` (NSFW)** per Phase 7 Decision #3 (no content-type /
 *   mature filter row on the novel filter sheet). `detectMangaTypes` is gone with them.
 * - **Drops the `SourceManager` parameter** — it was only needed for series-type detection.
 * - **Drops the local-source short-circuit** in `downloaded` — novels have no local source yet.
 *   Once novel downloads / local source ship, restore an `isLocal(novel)` callback parameter.
 *
 * Pure functions, no Injekt. Downstream dependencies (download count, track lookup,
 * logged-services map) are passed in so this file is unit-testable in isolation.
 */
object NovelLibraryFilter {

    const val STATE_IGNORE = 0
    const val STATE_INCLUDE = 1
    const val STATE_EXCLUDE = 2

    data class NovelFilterState(
        val downloaded: Int = STATE_IGNORE,
        val unread: Int = STATE_IGNORE,
        val completed: Int = STATE_IGNORE,
        val tracked: Int = STATE_IGNORE,
        val bookmarked: Int = STATE_IGNORE,
        val tracker: String = "",
    ) {
        val isAnyActive: Boolean
            get() = downloaded != STATE_IGNORE ||
                unread != STATE_IGNORE ||
                completed != STATE_IGNORE ||
                tracked != STATE_IGNORE ||
                bookmarked != STATE_IGNORE
    }

    suspend fun filter(
        library: Map<NovelCategory, List<LibraryItem.Novel>>,
        state: NovelFilterState,
        loggedServiceNames: Map<Long, String>,
        getDownloadCount: (Novel) -> Int,
        getTracks: suspend (Long) -> List<NovelTrack>,
        /**
         * When true, categories whose items were entirely filtered out are kept as empty entries.
         * Wired to `showAllCategories`-style prefs in the future screen model.
         */
        keepEmptyCategories: Boolean = false,
    ): Map<NovelCategory, List<LibraryItem.Novel>> {
        if (!state.isAnyActive) return library
        val filtered = library.mapValues { (_, items) ->
            items.filter {
                matches(it, state, loggedServiceNames, getDownloadCount, getTracks)
            }
        }
        return if (keepEmptyCategories) filtered else filtered.filterValues { it.isNotEmpty() }
    }

    private suspend fun matches(
        item: LibraryItem.Novel,
        state: NovelFilterState,
        loggedServiceNames: Map<Long, String>,
        getDownloadCount: (Novel) -> Int,
        getTracks: suspend (Long) -> List<NovelTrack>,
    ): Boolean {
        val libraryNovel = item.libraryNovel
        val novel = libraryNovel.novel

        if (state.unread == STATE_INCLUDE && libraryNovel.unread == 0) return false
        if (state.unread == STATE_EXCLUDE && libraryNovel.unread > 0) return false
        if (state.unread == 3 && !(libraryNovel.unread > 0 && !libraryNovel.hasRead)) return false
        if (state.unread == 4 && !(libraryNovel.unread > 0 && libraryNovel.hasRead)) return false

        if (state.bookmarked == STATE_INCLUDE && libraryNovel.bookmarkCount == 0) return false
        if (state.bookmarked == STATE_EXCLUDE && libraryNovel.bookmarkCount > 0) return false

        if (state.completed == STATE_INCLUDE && novel.status != NovelStatusCode.COMPLETED) return false
        if (state.completed == STATE_EXCLUDE && novel.status == NovelStatusCode.COMPLETED) return false

        if (!matchesTracking(item, state.tracked, state.tracker, loggedServiceNames, getTracks)) return false

        if (state.downloaded != STATE_IGNORE) {
            val isDownloaded = if (item.downloadCount != -1L) {
                item.downloadCount > 0
            } else {
                getDownloadCount(novel) > 0
            }
            return if (state.downloaded == STATE_INCLUDE) isDownloaded else !isDownloaded
        }

        return true
    }

    private suspend fun matchesTracking(
        item: LibraryItem.Novel,
        filterTracked: Int,
        filterTracker: String,
        loggedServiceNames: Map<Long, String>,
        getTracks: suspend (Long) -> List<NovelTrack>,
    ): Boolean {
        if (filterTracked == STATE_IGNORE) return true
        val novelId = item.libraryNovel.novel.id ?: return true
        val tracks = getTracks(novelId)
        val hasTrack = loggedServiceNames.keys.any { svcId -> tracks.any { it.syncId == svcId } }
        val matchedServiceId = filterTracker
            .takeIf { it.isNotEmpty() }
            ?.let { name -> loggedServiceNames.entries.firstOrNull { it.value == name }?.key }

        if (filterTracked == STATE_INCLUDE) {
            if (!hasTrack) return false
            if (matchedServiceId != null) {
                val hasServiceTrack = tracks.any { it.syncId == matchedServiceId }
                if (!hasServiceTrack) return false
            }
        } else if (filterTracked == STATE_EXCLUDE) {
            if (hasTrack && filterTracker.isEmpty()) return false
            if (matchedServiceId != null) {
                val hasServiceTrack = tracks.any { it.syncId == matchedServiceId }
                if (hasServiceTrack) return false
            }
        }
        return true
    }
}
