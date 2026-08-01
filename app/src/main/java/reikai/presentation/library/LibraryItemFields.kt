package reikai.presentation.library

import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.library.LibraryItem
import reikai.domain.library.LibrarySortFields
import reikai.domain.novel.model.CustomNovelInfo
import reikai.util.isLewd
import tachiyomi.domain.manga.model.CustomMangaInfo

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
 * The search twin of [libraryItemFilterFields], binding the shared query kernel onto the library row.
 * Most fields read the row directly, including `sourceName` and `sourceLanguage`, which both content
 * types now populate when they build it.
 *
 * Five seams: [sourceKey] is a numeric source id for manga and a plugin slug for novels, so it is a
 * String on both sides; [fetchInterval] and [nextUpdate] are null for novels, which have neither concept,
 * and a null makes the term false before negation so an inapplicable comparison never pulls a novel in
 * from either direction; [chapterMatches] is the per-term id set each side resolved once for this query,
 * since the two content types keep separate chapter tables; and [overlay] supplies each row's custom-info
 * overrides, keyed by the row's own id, so search matches the values shown on the card.
 *
 * The overlay is a plain map lookup per field rather than a copied row, because the rows themselves stay
 * override-free on purpose: filter, sort and grouping all read the source values.
 */
fun libraryItemQueryFields(
    sourceKey: (LibraryItem) -> String,
    fetchInterval: (LibraryItem) -> Int?,
    nextUpdate: (LibraryItem) -> Long?,
    chapterMatches: Map<String, Set<Long>> = emptyMap(),
    overlay: Map<Long, LibraryQueryOverlay> = emptyMap(),
) = LibraryQueryFields<LibraryItem>(
    id = { it.id },
    title = { overlay[it.id]?.title ?: it.libraryManga.manga.title },
    author = { overlay[it.id]?.author ?: it.libraryManga.manga.author },
    artist = { overlay[it.id]?.artist ?: it.libraryManga.manga.artist },
    description = { overlay[it.id]?.description ?: it.libraryManga.manga.description },
    notes = { it.libraryManga.manga.notes },
    genre = { overlay[it.id]?.genre ?: it.libraryManga.manga.genre },
    sourceName = { it.sourceName },
    sourceKey = sourceKey,
    sourceLanguage = { it.sourceLanguage },
    isLocal = { it.isLocal },
    // The deduplicated group counts, matching what the badges and the sort read.
    unreadCount = { it.unreadCount },
    readCount = { it.libraryManga.readCount },
    totalChapters = { it.libraryManga.totalChapters },
    dateAdded = { it.libraryManga.manga.dateAdded },
    fetchInterval = fetchInterval,
    nextUpdate = nextUpdate,
    // Keyed by the row's own raw id: each side resolved the set from its own chapter table, so the two
    // id spaces never meet here.
    matchesChapter = { item, term -> chapterMatches[term]?.contains(item.id) },
)

// The two custom-info rows differ only in their id field's name, so each maps onto the neutral overlay
// here rather than either content type learning about the query kernel.

fun CustomMangaInfo.toQueryOverlay() = LibraryQueryOverlay(title, author, artist, description, genre)

fun CustomNovelInfo.toQueryOverlay() = LibraryQueryOverlay(title, author, artist, description, genre)

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
