package reikai.presentation.library

import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.library.LibraryItem
import reikai.domain.library.LibrarySortFields
import reikai.util.isLewd

/**
 * The one binding of the shared filter and sort kernels onto the library's row type, used by both
 * content types, so a filter or sort behaviour change is written once and reaches manga and novels.
 *
 * Every axis that reads the row itself is shared verbatim. That works because a novel already renders as
 * a manga-shaped [LibraryItem] carrying its own status, genre, categories, counts and dates, and because
 * the novel status codes line up 1:1 with [SManga]'s. Only axes needing something the row cannot carry
 * are seams, and both are here rather than in either content type's model.
 */
fun libraryItemFilterFields(
    /**
     * The lewd heuristic's source-name half is manga-only (a novel source carries no adult flag and its
     * name is not in the hentai-source list), so novels pass null and fall through to the genre half,
     * which is their whole check.
     */
    lewdSourceName: (LibraryItem) -> String?,
    /** The two content types keep separate track tables, so each resolves its own, already unioned. */
    trackerIds: (LibraryItem) -> List<Long>,
) = LibraryFilterFields<LibraryItem>(
    // A novel row is never local, so the isLocal disjunct is inert for novels rather than manga-only.
    isDownloaded = { it.isLocal || it.downloadCount > 0 },
    // LibraryItem.unreadCount is the deduplicated group count, not the LibraryManga's own.
    isUnread = { it.unreadCount > 0 },
    hasStarted = { it.libraryManga.hasStarted },
    hasBookmarks = { it.libraryManga.hasBookmarks },
    isCompleted = { it.libraryManga.manga.status.toInt() == SManga.COMPLETED },
    // Novels have no fetch interval, and their synthetic row carries the factory default 0, so this
    // reads false for them without needing a per-type branch.
    matchesIntervalCustom = { it.libraryManga.manga.fetchInterval < 0 },
    isLewd = { it.libraryManga.manga.isLewd(lewdSourceName(it)) },
    trackerIds = trackerIds,
    categoryIds = { it.libraryManga.categories },
)

/**
 * The sort twin of [libraryItemFilterFields]. Every key reads the row, so the only seam is the tracker
 * mean, which each content type precomputes over its own track table (deduped per tracker, unrated
 * scores dropped) and hands in keyed by the row's own id.
 */
fun libraryItemSortFields(
    trackerMean: (LibraryItem) -> Double,
) = LibrarySortFields<LibraryItem>(
    id = { it.id },
    title = { it.libraryManga.manga.title },
    lastRead = { it.libraryManga.lastRead },
    lastUpdate = { it.libraryManga.manga.lastUpdate },
    unreadCount = { it.unreadCount },
    totalChapters = { it.libraryManga.totalChapters },
    latestUpload = { it.libraryManga.latestUpload },
    chapterFetchedAt = { it.libraryManga.chapterFetchedAt },
    dateAdded = { it.libraryManga.manga.dateAdded },
    downloadCount = { it.downloadCount.toLong() },
    trackerMean = trackerMean,
)
