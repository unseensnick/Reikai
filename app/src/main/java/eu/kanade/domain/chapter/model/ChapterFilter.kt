package eu.kanade.domain.chapter.model

import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.tachiyomi.ui.manga.ChapterList
import reikai.domain.merge.GroupChapterFlags
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.applyFilter
import tachiyomi.source.local.isLocal

/**
 * Applies the view filters to the list of chapters obtained from the database.
 * @return an observable of the list of chapters filtered and sorted.
 */
// RK: [flags] answers read, bookmarked and on disk as the merge group does, so this filters a merged
// series the way the details list does (the Item overload below): a chapter whose only copy on disk is
// another source's is downloaded, since that is the copy the reader opens.
fun List<Chapter>.applyFilters(
    manga: Manga,
    flags: GroupChapterFlags<Chapter>, // RK
): List<Chapter> {
    val unreadFilter = manga.unreadFilter
    val downloadedFilter = manga.downloadedFilter
    val bookmarkedFilter = manga.bookmarkedFilter

    // RK -->
    return filter { chapter -> applyFilter(unreadFilter) { !flags.isRead(chapter) } }
        .filter { chapter -> applyFilter(bookmarkedFilter) { flags.isBookmarked(chapter) } }
        .filter { chapter -> applyFilter(downloadedFilter) { flags.isDownloaded(chapter) } }
        // RK <--
        .sortedWith(getChapterSort(manga))
}

/**
 * Applies the view filters to the list of chapters obtained from the database.
 * @return an observable of the list of chapters filtered and sorted.
 */
fun List<ChapterList.Item>.applyFilters(manga: Manga): Sequence<ChapterList.Item> {
    val isLocalManga = manga.isLocal()
    val unreadFilter = manga.unreadFilter
    val downloadedFilter = manga.downloadedFilter
    val bookmarkedFilter = manga.bookmarkedFilter
    return asSequence()
        // RK: the item's any-source flags, so this agrees with the details list it filters.
        .filter { applyFilter(unreadFilter) { !it.isRead } }
        .filter { applyFilter(bookmarkedFilter) { it.isBookmarked } }
        .filter { applyFilter(downloadedFilter) { it.isDownloaded || isLocalManga } }
        .sortedWith { (chapter1), (chapter2) -> getChapterSort(manga).invoke(chapter1, chapter2) }
}
