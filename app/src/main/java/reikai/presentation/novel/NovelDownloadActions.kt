package reikai.presentation.novel

import eu.kanade.presentation.manga.DownloadAction
import reikai.domain.novel.model.NovelChapter

/**
 * Resolve a toolbar/library [DownloadAction] to the novel chapters it should enqueue, the single
 * source of truth shared by the novel library (multi-select) and the novel details toolbar. NEXT_N
 * counts the next unread chapters in reading (source) order; UNREAD is every unread chapter;
 * BOOKMARKED is every bookmarked chapter.
 *
 * Already-downloaded chapters are excluded up front (mirroring manga's getUnreadChapters /
 * getBookmarkedChapters, which filter NOT_DOWNLOADED). This must happen BEFORE NEXT_N's take(N):
 * otherwise, once the first N unread chapters are downloaded, take(N) keeps returning those same
 * chapters (which downloadChapters then skips), so repeated NEXT_N never advances past them.
 *
 * [downloadedChapterIds] carries the disk-download membership (from NovelDownloadCache); the caller
 * resolves it since this pure selector has no DB / cache access.
 */
fun selectChaptersForDownloadAction(
    chapters: List<NovelChapter>,
    action: DownloadAction,
    downloadedChapterIds: Set<Long>,
): List<NovelChapter> {
    val sorted = chapters.sortedBy { it.sourceOrder }
    val unread = sorted.filterNot { it.read || it.id in downloadedChapterIds }
    return when (action) {
        DownloadAction.NEXT_1_CHAPTER -> unread.take(1)
        DownloadAction.NEXT_5_CHAPTERS -> unread.take(5)
        DownloadAction.NEXT_10_CHAPTERS -> unread.take(10)
        DownloadAction.NEXT_25_CHAPTERS -> unread.take(25)
        DownloadAction.UNREAD_CHAPTERS -> unread
        DownloadAction.BOOKMARKED_CHAPTERS -> sorted.filter { it.bookmark && it.id !in downloadedChapterIds }
    }
}
