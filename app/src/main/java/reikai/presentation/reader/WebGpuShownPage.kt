package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters

/**
 * Whether the WebGPU viewer keeps [shown], the page on screen, when [chapters] arrive, rather than landing
 * on the current chapter's requested page. Scrolling across a boundary is not a jump: a long strip reports
 * a page once its bottom shows, while the page on screen is the one whose top is there, so the two can sit
 * in different chapters. [readerAt] is the chapter of the last page reported, which a scroll moves before
 * its switch loads and a pick or a step never moves. A page a reload replaced is never kept.
 */
internal fun keepsShownPage(shown: ReaderPage, chapters: ViewerChapters, readerAt: Long?): Boolean {
    val current = chapters.currChapter
    val holder = listOfNotNull(current, chapters.prevChapter, chapters.nextChapter)
        .firstOrNull { it.chapter.id == shown.chapter.chapter.id }
        ?: return false
    if (!isCachedPageCurrent(shown, holder.pages?.getOrNull(shown.index))) return false
    return holder === current || readerAt == current.chapter.id
}
