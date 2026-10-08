package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import reikai.domain.merge.MergedGroupCounts
import tachiyomi.domain.library.model.LibraryManga

/** One member of a merge group as its library row reads it; [S] is the content type's source key. */
data class MergedRowMember<S>(
    val id: Long,
    val source: S,
    val lastRead: Long,
    val downloadCount: Int,
    val genre: List<String>?,
)

/**
 * Stamps a merge group onto its leading row, the one rule both libraries build a merged row by. The
 * counts are the stored stitch's ([counts], [mergedDownloads]), one per chapter the group covers:
 * summing the members would count every chapter two of them share twice. An unstitched group keeps the
 * leading row's own unread and sums the members' downloads. Each distinct source is searched and
 * badged once, and every badge stays gated by [badgePrefs].
 */
suspend fun <S> LibraryItem.stampMergedGroup(
    members: List<MergedRowMember<S>>,
    counts: MergedGroupCounts?,
    mergedDownloads: Int?,
    badgePrefs: LibraryBadgePrefs,
    showSourceIcons: Boolean,
    querySource: suspend (S) -> LibraryQuerySource,
    sourceBadge: suspend (S) -> SourceBadge,
): LibraryItem {
    val unread = counts?.unread ?: unreadCount
    val downloads = mergedDownloads ?: members.sumOf { it.downloadCount }
    val sources = members.map { it.source }.distinct()
    return copy(
        downloadCount = downloads,
        unreadCount = unread,
        // LastRead sorts by the most recent read across the group, so reading any source bubbles it up.
        libraryManga = libraryManga.copy(lastRead = members.maxOf { it.lastRead }).withGroupCounts(counts),
        relatedMangaIds = members.map { it.id },
        memberSources = sources.map { querySource(it) },
        memberGenres = members.flatMap { it.genre.orEmpty() }.distinct(),
        badges = badges.copy(
            downloadCount = badgePrefs.downloadBadge(downloads),
            unreadCount = badgePrefs.unreadBadge(unread),
            mergedSources = if (showSourceIcons) sources.map { sourceBadge(it) } else emptyList(),
        ),
    )
}

// All three from one query, so the unread they imply matches the badge. Started, Bookmarked, the Total
// chapters sort and the read/total search terms read them.
private fun LibraryManga.withGroupCounts(counts: MergedGroupCounts?): LibraryManga =
    counts?.let { copy(totalChapters = it.total, readCount = it.read, bookmarkCount = it.bookmarked) } ?: this
