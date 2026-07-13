package reikai.presentation.novel

import eu.kanade.presentation.manga.DownloadAction
import reikai.domain.novel.model.NovelChapter

/**
 * Resolve a toolbar/library [DownloadAction] to the novel chapters it should enqueue, the single
 * source of truth shared by the novel library (multi-select) and the novel details toolbar. NEXT_N
 * counts the next unread chapters in reading (source) order; UNREAD is every unread chapter;
 * BOOKMARKED is every bookmarked chapter.
 *
 * Already-downloaded and already-queued chapters are excluded up front (mirroring manga, which drops
 * both before picking targets). This must happen BEFORE NEXT_N's take(N): otherwise, once the first N
 * unread chapters are downloaded or queued, take(N) keeps returning those same chapters (which the
 * downloader then skips), so repeated NEXT_N never advances past them and short-changes the batch.
 *
 * [excludedChapterIds] carries the already-downloaded (from NovelDownloadCache) plus already-queued
 * (from NovelDownloadManager.queueState) membership; the caller resolves and unions it, since this
 * pure selector has no DB / cache / queue access.
 */
fun selectChaptersForDownloadAction(
    chapters: List<NovelChapter>,
    action: DownloadAction,
    excludedChapterIds: Set<Long>,
): List<NovelChapter> {
    val sorted = chapters.sortedBy { it.sourceOrder }
    val unread = sorted.filterNot { it.read || it.id in excludedChapterIds }
    return when (action) {
        DownloadAction.NEXT_1_CHAPTER -> unread.take(1)
        DownloadAction.NEXT_5_CHAPTERS -> unread.take(5)
        DownloadAction.NEXT_10_CHAPTERS -> unread.take(10)
        DownloadAction.NEXT_25_CHAPTERS -> unread.take(25)
        DownloadAction.UNREAD_CHAPTERS -> unread
        DownloadAction.BOOKMARKED_CHAPTERS -> sorted.filter { it.bookmark && it.id !in excludedChapterIds }
    }
}
