package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import reikai.domain.merge.MergedGroupCounts
import tachiyomi.domain.library.model.LibraryManga
import kotlin.time.Instant

/**
 * One member of a merge group as its library row reads it; [S] is the content type's source key. [query] is
 * the member's own, not its source's: a local series' language is per series, and two members can share one
 * local source.
 */
data class MergedRowMember<S>(
    val id: Long,
    val source: S,
    val query: LibraryQuerySource,
    val lastRead: Instant?,
    val downloadCount: Int,
    val genre: List<String>?,
    val lastUpdate: Instant?,
    val latestUpload: Instant?,
    val chapterFetchedAt: Instant?,
)

/**
 * Stamps a merge group onto its leading row, the one rule both libraries build a merged row by. The
 * counts are the stored stitch's ([counts], [mergedDownloads]), one per chapter the group covers:
 * summing the members would count every chapter two of them share twice. An unstitched group keeps the
 * leading row's own unread and sums the members' downloads. Each member is searched by its own
 * [MergedRowMember.query], each distinct source badged once, and every badge stays gated by [badgePrefs].
 * [coverFallbacks] are the other members' cover models in ranking order.
 */
suspend fun <S> LibraryItem.stampMergedGroup(
    members: List<MergedRowMember<S>>,
    coverFallbacks: List<Any>,
    counts: MergedGroupCounts?,
    mergedDownloads: Int?,
    badgePrefs: LibraryBadgePrefs,
    showSourceIcons: Boolean,
    sourceBadge: suspend (S) -> SourceBadge,
): LibraryItem {
    val unread = counts?.unread ?: unreadCount
    val downloads = mergedDownloads ?: members.sumOf { it.downloadCount }
    val sources = members.map { it.source }.distinct()
    return copy(
        downloadCount = downloads,
        unreadCount = unread,
        // The date sorts read the newest across the group, so reading or a new chapter on any source
        // moves the card, as its chapter list and unread badge already show.
        libraryManga = libraryManga
            .copy(
                manga = libraryManga.manga.copy(lastUpdate = members.newest { it.lastUpdate }),
                lastRead = members.newest { it.lastRead },
                latestUpload = members.newest { it.latestUpload },
                chapterFetchedAt = members.newest { it.chapterFetchedAt },
            )
            .withGroupCounts(counts),
        relatedMangaIds = members.map { it.id },
        memberSources = members.map { it.query }.distinct(),
        memberGenres = members.flatMap { it.genre.orEmpty() }.distinct(),
        coverFallbacks = coverFallbacks,
        badges = badges.copy(
            downloadCount = badgePrefs.downloadBadge(downloads),
            unreadCount = badgePrefs.unreadBadge(unread),
            mergedSources = if (showSourceIcons) sources.map { sourceBadge(it) } else emptyList(),
        ),
    )
}

private inline fun <S> List<MergedRowMember<S>>.newest(date: (MergedRowMember<S>) -> Instant?): Instant? =
    mapNotNull(date).maxOrNull()

// All three from one query, so the unread they imply matches the badge. Started, Bookmarked, the Total
// chapters sort and the read/total search terms read them.
private fun LibraryManga.withGroupCounts(counts: MergedGroupCounts?): LibraryManga =
    counts?.let { copy(totalChapters = it.total, readCount = it.read, bookmarkCount = it.bookmarked) } ?: this
