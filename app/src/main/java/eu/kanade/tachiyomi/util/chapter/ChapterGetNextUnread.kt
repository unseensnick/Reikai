package eu.kanade.tachiyomi.util.chapter

import eu.kanade.domain.chapter.model.applyFilters
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.manga.ChapterList
import reikai.domain.chapter.ReadingOrder
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * Gets next unread chapter with filters and sorting applied
 */
fun List<Chapter>.getNextUnread(
    manga: Manga,
    downloadManager: DownloadManager,
    // RK: chapters whose own row is unread but which another grouped source has read. Skipped, so
    // resuming a merged series does not reopen something the library already counts as read.
    readInOtherSources: Set<Long> = emptySet(),
    // RK: each chapter's own manga, so the downloaded filter probes the source that copy came from.
    mangaById: Map<Long, Manga> = emptyMap(),
): Chapter? {
    val shown = applyFilters(manga, downloadManager) { mangaById[it.mangaId] ?: manga }
    // RK: the shared reading-order rule, so novels resume at the same chapter this picks.
    return ReadingOrder.nextToRead(ReadingOrder.of(shown, manga.sortDescending())) {
        it.read || it.id in readInOtherSources
    }
}

/**
 * Gets next unread chapter with filters and sorting applied
 */
fun List<ChapterList.Item>.getNextUnread(manga: Manga): Chapter? {
    // RK: as above, the shared rule rather than a second copy of it.
    val shown = applyFilters(manga).toList()
    return ReadingOrder.nextToRead(ReadingOrder.of(shown, manga.sortDescending())) { it.isRead }?.chapter
}
