package reikai.presentation.library

import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.library.LibraryItem
import reikai.domain.entry.EntryCustomInfo
import reikai.domain.entry.withCustomInfo
import reikai.domain.library.LibrarySortFields
import reikai.domain.library.TagAliases
import reikai.util.isAdultEntry
import tachiyomi.domain.track.model.Track

/**
 * The one binding of the shared filter and sort kernels onto the library's row type, used by both
 * content types, so a filter or sort behaviour change is written once and reaches manga and novels.
 * Every axis that reads the row itself is shared verbatim, because a novel already renders as a
 * manga-shaped [LibraryItem] carrying its own status, genre, categories, counts and dates, and the
 * novel status codes line up 1:1 with [SManga]'s. Only axes needing something the row cannot carry are
 * seams.
 */
fun libraryItemFilterFields(
    /** The row's own source key, as [LibraryQuerySource.key] spells it. */
    sourceKey: (LibraryItem) -> String,
    /** Whether a source key is adult, by AdultContentChecker's library sets over [adultLookupKeys]. */
    adultSource: (String) -> Boolean,
    /** The adult rule's source-name list is manga sites, so novels pass null. */
    lewdSourceName: (LibraryQuerySource) -> String?,
    /** The two content types keep separate track tables, so each resolves its own, already unioned. */
    trackerIds: (LibraryItem) -> List<Long>,
    /** The series' Edit info tags, which the tag filter reads over its sources' own, as search does. */
    customTags: (LibraryItem) -> List<String>? = { null },
) = LibraryFilterFields<LibraryItem>(
    // A local series' files are on the device, so it counts as downloaded, for either type, and so does a
    // merged series with a local member, which the leading row's own flag does not say.
    isDownloaded = { it.isLocal || it.downloadCount > 0 || it.memberSources.any { source -> source.isLocal } },
    // LibraryItem.unreadCount is the deduplicated group count, not the LibraryManga's own.
    isUnread = { it.unreadCount > 0 },
    hasStarted = { it.libraryManga.hasStarted },
    hasBookmarks = { it.libraryManga.hasBookmarks },
    isCompleted = { it.libraryManga.manga.status.toInt() == SManga.COMPLETED },
    matchesIntervalCustom = { it.libraryManga.manga.fetchInterval < 0 },
    // A merged series is adult when any member is: every member's source, against all their tags.
    isLewd = { item ->
        val genres = item.libraryManga.manga.genre.orEmpty() + item.memberGenres
        item.allSources(sourceKey(item)).any { isAdultEntry(adultSource(it.key), lewdSourceName(it), genres) }
    },
    trackerIds = trackerIds,
    categoryIds = { it.libraryManga.categories },
    tags = { it.seriesTags(customTags(it)) },
)

/** Every source key [rows] stand for, members included, which the adult-source lookup must cover. */
fun adultLookupKeys(rows: List<LibraryItem>, sourceKey: (LibraryItem) -> String): Set<String> =
    rows.flatMapTo(mutableSetOf()) { row -> row.allSources(sourceKey(row)).map { it.key } }

/**
 * The search counterpart of [libraryItemFilterFields]; both libraries call this one function. The seams:
 * [sourceKey] is a String on both sides (a numeric id for manga, a plugin slug for novels);
 * [chapterMatches] and [categoryMatches] are the per-term id sets each side resolved once; [overlay]
 * supplies custom-info overrides by row id, since filter, sort and grouping read the source values;
 * [galleryIndex] answers the tag grammar, which novels never have; [tracksById] is each side's own track
 * table, already in the shared [Track], and [trackers] the logged-in ones.
 */
fun libraryItemQueryFields(
    sourceKey: (LibraryItem) -> String,
    chapterMatches: Map<String, Set<Long>> = emptyMap(),
    overlay: Map<Long, LibraryQueryOverlay> = emptyMap(),
    galleryIndex: GallerySearchIndex = GallerySearchIndex(),
    categoryMatches: Map<String, Set<Long>> = emptyMap(),
    tracksById: Map<Long, List<Track>> = emptyMap(),
    trackers: Map<Long, Tracker> = emptyMap(),
    tagAliases: TagAliases = TagAliases.NONE,
) = LibraryQueryFields<LibraryItem>(
    id = { it.id },
    title = { overlay[it.id]?.title ?: it.libraryManga.manga.title },
    author = { overlay[it.id]?.author ?: it.libraryManga.manga.author },
    artist = { overlay[it.id]?.artist ?: it.libraryManga.manga.artist },
    description = { overlay[it.id]?.description ?: it.libraryManga.manga.description },
    // Each source of a merged series keeps its own notes, so the card is found by any of them.
    notes = { it.memberNotes.ifEmpty { listOf(it.libraryManga.manga.notes) }.joinToString("\n") },
    genre = { tagAliases.resolveAll(it.seriesTags(overlay[it.id]?.genre)) },
    sources = { item -> item.allSources(sourceKey(item)) },
    // The deduplicated group counts, matching what the badges and the sort read.
    unreadCount = { it.unreadCount },
    readCount = { it.libraryManga.readCount },
    totalChapters = { it.libraryManga.totalChapters },
    dateAdded = { it.libraryManga.manga.favoriteAt },
    fetchInterval = { it.libraryManga.manga.fetchInterval },
    nextUpdate = { it.libraryManga.manga.nextUpdate },
    // Keyed by the row's own raw id: each side resolved the set from its own chapter table, so the
    // two id spaces never meet here. A collapsed merge group also matches through its members'
    // ids, since their chapters render as the entry's own but their rows are not in the list.
    matchesChapter = { item, term ->
        chapterMatches[term]?.let { ids -> item.memberIds().any { it in ids } }
    },
    matchesTagTerm = galleryIndex::matches,
    status = { it.libraryManga.manga.status },
    trackerNames = { libraryTrackerNames(it.memberIds(), tracksById, trackers) },
    categoryIds = { it.libraryManga.categories },
    matchesCategory = { item, term ->
        categoryMatches[term]?.let { ids -> item.libraryManga.categories.any { it in ids } }
    },
    meanScore = { libraryTrackerMean(it.memberIds(), tracksById, trackers) },
    tagAliases = tagAliases,
)

/**
 * A series' tags as search and Statistics read them: its Edit info tags when set, else every source's
 * tags of a merged series, so a tag row counts exactly the series its `tag:` search finds. Group by tag
 * passes no Edit info, since grouping reads source values.
 */
fun seriesGenres(custom: List<String>?, memberGenres: List<List<String>?>): List<String> =
    custom ?: memberGenres.flatMap { it.orEmpty() }.distinctBy { it.lowercase() }

/** This row's tags as [seriesGenres] reads them, with [custom] its Edit info tags. */
fun LibraryItem.seriesTags(custom: List<String>?): List<String> =
    seriesGenres(custom, listOf(libraryManga.manga.genre, memberGenres))

/** This row's own source as the search terms read it; [key] is per content type, see [LibraryQuerySource.key]. */
fun LibraryItem.querySource(key: String) = LibraryQuerySource(key, sourceName, sourceLanguage, isLocal)

/** Every source this row stands for: a merged series' distinct member sources, else its own under [ownKey]. */
internal fun LibraryItem.allSources(ownKey: String) = memberSources.ifEmpty { listOf(querySource(ownKey)) }

/** Maps onto the neutral overlay here rather than either content type learning about the query kernel. */
fun EntryCustomInfo.toQueryOverlay() = LibraryQueryOverlay(title, author, artist, description, genre)

/** The display read's overlay, which the raw rows that filter, sort and search read never take. */
fun LibraryItem.withCustomInfo(custom: EntryCustomInfo?): LibraryItem {
    if (custom == null) return this
    return copy(libraryManga = libraryManga.copy(manga = libraryManga.manga.withCustomInfo(custom)))
}

/**
 * The sort counterpart of [libraryItemFilterFields]. Every key reads the row, so the only seam is the tracker
 * mean, which each content type precomputes over its own track table (deduped per tracker, unrated
 * scores dropped) and hands in keyed by the row's own id, null for a series with no score.
 */
fun libraryItemSortFields(
    trackerMean: (LibraryItem) -> Double?,
) = LibrarySortFields<LibraryItem>(
    id = { it.id },
    title = { it.libraryManga.manga.title },
    lastRead = { it.libraryManga.lastRead },
    lastUpdate = { it.libraryManga.manga.lastUpdate },
    unreadCount = { it.unreadCount },
    totalChapters = { it.libraryManga.totalChapters },
    latestUpload = { it.libraryManga.latestUpload },
    chapterFetchedAt = { it.libraryManga.chapterFetchedAt },
    dateAdded = { it.libraryManga.manga.favoriteAt },
    downloadCount = { it.downloadCount.toLong() },
    trackerMean = { trackerMean(it) ?: NO_TRACKER_MEAN },
)

// Sorts an unscored series below every score, as Mihon's defaultTrackerScoreSortValue does.
private const val NO_TRACKER_MEAN = -1.0
