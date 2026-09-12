package reikai.presentation.novel

import eu.kanade.presentation.manga.DownloadAction
import reikai.domain.chapter.ReadingOrder
import reikai.domain.novel.model.NovelChapter

/**
 * Resolve a toolbar or library [DownloadAction] to the novel chapters it should enqueue, shared by the
 * novel library's multi-select and the details toolbar. [chapters] arrives in the order it is shown and
 * [sortDescending] says which way, so "next N" queues the N the reader reaches next. Downloaded and
 * queued chapters ([excludedChapterIds], resolved by the caller) drop out BEFORE take(N), as on manga:
 * otherwise repeated NEXT_N keeps handing back the same first N. The "in other sources" sets carry a
 * merge group's read and bookmark state, so the next unread is the group's.
 */
fun selectChaptersForDownloadAction(
    chapters: List<NovelChapter>,
    sortDescending: Boolean,
    action: DownloadAction,
    excludedChapterIds: Set<Long>,
    readInOtherSources: Set<Long>,
    bookmarkedInOtherSources: Set<Long>,
): List<NovelChapter> {
    val sorted = ReadingOrder.of(chapters, sortDescending)
    val unread = sorted.filterNot { it.read || it.id in readInOtherSources || it.id in excludedChapterIds }
    return when (action) {
        DownloadAction.NEXT_1_CHAPTER -> unread.take(1)
        DownloadAction.NEXT_5_CHAPTERS -> unread.take(5)
        DownloadAction.NEXT_10_CHAPTERS -> unread.take(10)
        DownloadAction.NEXT_25_CHAPTERS -> unread.take(25)
        DownloadAction.UNREAD_CHAPTERS -> unread
        DownloadAction.BOOKMARKED_CHAPTERS -> sorted.filter {
            (it.bookmark || it.id in bookmarkedInOtherSources) && it.id !in excludedChapterIds
        }
    }
}
