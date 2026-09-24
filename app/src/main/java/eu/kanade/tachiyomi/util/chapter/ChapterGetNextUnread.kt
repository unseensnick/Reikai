package eu.kanade.tachiyomi.util.chapter

import eu.kanade.domain.chapter.model.applyFilters
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.manga.ChapterList
import reikai.domain.chapter.ReadingOrder
import reikai.domain.chapter.hiddenChapterKey
import reikai.domain.manga.inReadingOrder
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
    // RK: the hidden-chapter keys, passed over unless only hidden chapters are left unread.
    hiddenKeys: Set<String> = emptySet(),
): Chapter? {
    val shown = applyFilters(manga, downloadManager) { mangaById[it.mangaId] ?: manga }
    // RK: the order the reader pages in, asked the question novels resume by, hidden chapters last.
    val isHidden = { chapter: Chapter ->
        hiddenChapterKey((mangaById[chapter.mangaId] ?: manga).source.toString(), chapter.url) in hiddenKeys
    }
    return ReadingOrder.nextToRead(ReadingOrder.hiddenLast(shown.inReadingOrder(manga), isHidden)) {
        it.read || it.id in readInOtherSources
    }
}

/**
 * Gets next unread chapter with filters and sorting applied
 */
fun List<ChapterList.Item>.getNextUnread(manga: Manga): Chapter? {
    // RK: as above, through the reader's order. Filtered on the item, whose read flag spans the group.
    return applyFilters(manga).filterNot { it.isRead }.map { it.chapter }.toList().inReadingOrder(manga).firstOrNull()
}
