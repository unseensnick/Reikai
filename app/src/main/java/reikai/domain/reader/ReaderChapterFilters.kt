package reikai.domain.reader

import reikai.domain.merge.GroupChapterFlags
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapterFlags
import reikai.domain.novel.model.effectiveBookmarkedFilter
import reikai.domain.novel.model.effectiveDownloadedFilter
import reikai.domain.novel.model.effectiveReadFilter
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.applyFilter

/** An entry's chapter-list filters, each [TriState.ENABLED_IS] to keep only chapters that are unread,
 *  bookmarked or downloaded, and [TriState.ENABLED_NOT] to keep only those that are not. */
data class ChapterListFilters(val unread: TriState, val bookmarked: TriState, val downloaded: TriState) {
    val isActive: Boolean
        get() = unread != TriState.DISABLED || bookmarked != TriState.DISABLED || downloaded != TriState.DISABLED
}

/** The manga's own filters, read raw as the reader always has: the global downloaded-only switch is
 *  applied to the reader's list separately. */
fun Manga.readerChapterFilters() = ChapterListFilters(
    unread = unreadFilter,
    bookmarked = bookmarkedFilter,
    downloaded = when (downloadedFilterRaw) {
        Manga.CHAPTER_SHOW_DOWNLOADED -> TriState.ENABLED_IS
        Manga.CHAPTER_SHOW_NOT_DOWNLOADED -> TriState.ENABLED_NOT
        else -> TriState.DISABLED
    },
)

fun Novel.readerChapterFilters(prefs: NovelPreferences, downloadedOnly: Boolean) = novelChapterListFilters(
    read = effectiveReadFilter(prefs),
    bookmarked = effectiveBookmarkedFilter(prefs),
    downloaded = effectiveDownloadedFilter(prefs),
    downloadedOnly = downloadedOnly,
)

/**
 * A novel's chapter-list filters from its flag values. The global Downloaded only switch forces the
 * downloaded filter on, as manga's `Manga.downloadedFilter` does; the forced value is never saved, so the
 * novel's own setting comes back when the switch goes off.
 */
fun novelChapterListFilters(read: Long, bookmarked: Long, downloaded: Long, downloadedOnly: Boolean) =
    ChapterListFilters(
        unread = read.toTriState(NovelChapterFlags.SHOW_UNREAD, NovelChapterFlags.SHOW_READ),
        bookmarked = bookmarked.toTriState(NovelChapterFlags.SHOW_BOOKMARKED, NovelChapterFlags.SHOW_NOT_BOOKMARKED),
        downloaded = if (downloadedOnly) {
            TriState.ENABLED_IS
        } else {
            downloaded.toTriState(NovelChapterFlags.SHOW_DOWNLOADED, NovelChapterFlags.SHOW_NOT_DOWNLOADED)
        },
    )

private fun Long.toTriState(whenIs: Long, whenNot: Long) = when (this) {
    whenIs -> TriState.ENABLED_IS
    whenNot -> TriState.ENABLED_NOT
    else -> TriState.DISABLED
}

/** The inverse of the mapping [novelChapterListFilters] reads, for writing a filter page's pick back. */
fun TriState.toFlag(whenIs: Long, whenNot: Long): Long = when (this) {
    TriState.DISABLED -> 0L
    TriState.ENABLED_IS -> whenIs
    TriState.ENABLED_NOT -> whenNot
}

/**
 * Whether a reader's forward step may stop on [chapter]. "Skip read" passes a read one; "skip
 * filtered" passes one the entry's own chapter-list [filters] hide, which is what makes the setting
 * mean the same as on the details list. Every flag is the group's answer, as that list gives it. The
 * chapter being opened stays reachable whatever this says, which each reader decides for itself.
 */
fun <T> GroupChapterFlags<T>.isForwardEligible(
    chapter: T,
    skipRead: Boolean,
    skipFiltered: Boolean,
    filters: ChapterListFilters,
): Boolean = when {
    skipRead && isRead(chapter) -> false
    !skipFiltered -> true
    else -> applyFilter(filters.unread) { !isRead(chapter) } &&
        applyFilter(filters.bookmarked) { isBookmarked(chapter) } &&
        applyFilter(filters.downloaded) { isDownloaded(chapter) }
}
