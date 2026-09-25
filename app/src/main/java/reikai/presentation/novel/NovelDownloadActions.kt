package reikai.presentation.novel

import eu.kanade.presentation.manga.DownloadAction
import reikai.domain.chapter.DownloadCandidates
import reikai.domain.chapter.ReadingOrder
import reikai.domain.novel.model.NovelChapter

/**
 * Resolve a toolbar or library [DownloadAction] to the novel chapters it should enqueue, shared by the
 * novel library's multi-select and the details toolbar, over the rule manga's toolbar also runs
 * ([DownloadCandidates.forAction]). [chapters] arrives in the order it is shown and [sortDescending]
 * says which way. The "in other sources" sets carry a merge group's read and bookmark state, so the
 * next unread is the group's. [isHidden] keys each copy by its own novel's source.
 */
fun selectChaptersForDownloadAction(
    chapters: List<NovelChapter>,
    sortDescending: Boolean,
    action: DownloadAction,
    excludedChapterIds: Set<Long>,
    readInOtherSources: Set<Long>,
    bookmarkedInOtherSources: Set<Long>,
    isHidden: (NovelChapter) -> Boolean,
): List<NovelChapter> = DownloadCandidates.forAction(
    ReadingOrder.of(chapters, sortDescending),
    action,
    isRead = { it.read || it.id in readInOtherSources },
    isBookmarked = { it.bookmark || it.id in bookmarkedInOtherSources },
    isHidden = isHidden,
    isExcluded = { it.id in excludedChapterIds },
)
