package reikai.presentation.novel

import eu.kanade.presentation.manga.DownloadAction
import reikai.domain.novel.model.NovelChapter

/**
 * Resolve a toolbar/library [DownloadAction] to the novel chapters it should enqueue, the single
 * source of truth shared by the novel library (multi-select) and the novel details toolbar. NEXT_N
 * counts unread chapters in reading (source) order; UNREAD is every unread chapter; BOOKMARKED is
 * every bookmarked chapter regardless of read state. Already-downloaded chapters are skipped by
 * [reikai.novel.download.NovelDownloadManager.downloadChapters], so they aren't filtered here.
 */
fun selectChaptersForDownloadAction(
    chapters: List<NovelChapter>,
    action: DownloadAction,
): List<NovelChapter> {
    val sorted = chapters.sortedBy { it.sourceOrder }
    val unread = sorted.filterNot { it.read }
    return when (action) {
        DownloadAction.NEXT_1_CHAPTER -> unread.take(1)
        DownloadAction.NEXT_5_CHAPTERS -> unread.take(5)
        DownloadAction.NEXT_10_CHAPTERS -> unread.take(10)
        DownloadAction.NEXT_25_CHAPTERS -> unread.take(25)
        DownloadAction.UNREAD_CHAPTERS -> unread
        DownloadAction.BOOKMARKED_CHAPTERS -> sorted.filter { it.bookmark }
    }
}
