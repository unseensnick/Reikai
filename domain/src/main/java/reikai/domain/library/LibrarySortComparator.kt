package reikai.domain.library

import tachiyomi.core.common.util.lang.compareToWithCollator
import kotlin.random.Random

/**
 * Neutral library sort modes shared by the manga and novel libraries. Both decode their persisted
 * [tachiyomi.domain.library.model.LibrarySort] flags into these modes, then feed the one
 * [librarySortComparator], so a sort-behaviour change is written once.
 */
enum class LibrarySortMode {
    Alphabetical,
    LastRead,
    LastUpdate,
    UnreadCount,
    TotalChapters,
    LatestChapter,
    ChapterFetchDate,
    DateAdded,
    TrackerMean,
    Downloaded,
    Random,
}

/**
 * Per-entry field accessors [librarySortComparator] reads, so it never depends on the concrete row type
 * (the manga `LibraryItem` vs the novel `LibraryNovel`). Each library supplies getters over its own row.
 * [trackerMean] is a precomputed mean 0-10 score with the unscored default (-1.0) folded in by the caller,
 * and [unreadCount] already carries each side's merged / deduplicated count, so the comparator stays pure.
 */
class LibrarySortFields<T>(
    val id: (T) -> Long,
    val title: (T) -> String,
    val lastRead: (T) -> Long,
    val lastUpdate: (T) -> Long,
    val unreadCount: (T) -> Long,
    val totalChapters: (T) -> Long,
    val latestUpload: (T) -> Long,
    val chapterFetchedAt: (T) -> Long,
    val dateAdded: (T) -> Long,
    val downloadCount: (T) -> Long,
    val trackerMean: (T) -> Double,
)

/**
 * The one comparator both libraries sort by. Reconciles the two histories to a single behaviour:
 * - Alphabetical uses the locale collator, so novels gain the manga library's locale-aware ordering.
 * - The title tiebreak is appended AFTER the direction reversal, so ties stay A->Z even under a
 *   descending sort.
 * - Zero-unread entries sort last regardless of direction, so a fully-read entry never leads Unread.
 * - Random is a per-id seeded order, stable across re-emits until [randomSeed] changes.
 */
fun <T> librarySortComparator(
    mode: LibrarySortMode,
    isAscending: Boolean,
    randomSeed: Long,
    fields: LibrarySortFields<T>,
): Comparator<T> {
    val tiebreak = Comparator<T> { a, b ->
        fields.title(a).lowercase().compareToWithCollator(fields.title(b).lowercase())
    }

    // UnreadCount and Random fold direction in themselves, so they bypass the generic reversal below.
    when (mode) {
        LibrarySortMode.UnreadCount -> {
            val cmp = Comparator<T> { a, b ->
                val ua = fields.unreadCount(a)
                val ub = fields.unreadCount(b)
                when {
                    ua == ub -> 0
                    ua == 0L -> 1 // a is fully read: always last
                    ub == 0L -> -1 // b is fully read: always last
                    else -> if (isAscending) ua.compareTo(ub) else ub.compareTo(ua)
                }
            }
            return cmp.then(tiebreak)
        }
        LibrarySortMode.Random -> {
            val base = compareBy<T> { Random(randomSeed + fields.id(it)).nextInt() }
            return (if (isAscending) base else base.reversed()).then(tiebreak)
        }
        else -> Unit
    }

    val base: Comparator<T> = when (mode) {
        LibrarySortMode.Alphabetical -> tiebreak
        LibrarySortMode.LastRead -> compareBy { fields.lastRead(it) }
        LibrarySortMode.LastUpdate -> compareBy { fields.lastUpdate(it) }
        LibrarySortMode.TotalChapters -> compareBy { fields.totalChapters(it) }
        LibrarySortMode.LatestChapter -> compareBy { fields.latestUpload(it) }
        LibrarySortMode.ChapterFetchDate -> compareBy { fields.chapterFetchedAt(it) }
        LibrarySortMode.DateAdded -> compareBy { fields.dateAdded(it) }
        LibrarySortMode.TrackerMean -> compareBy { fields.trackerMean(it) }
        LibrarySortMode.Downloaded -> compareBy { fields.downloadCount(it) }
        LibrarySortMode.UnreadCount, LibrarySortMode.Random -> error("handled above")
    }
    return (if (isAscending) base else base.reversed()).then(tiebreak)
}
