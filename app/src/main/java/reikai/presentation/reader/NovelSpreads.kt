package reikai.presentation.reader

import reikai.presentation.reader.NovelPaging.Page
import reikai.presentation.reader.NovelPaging.Turn

/**
 * Paged reading two pages side by side, by the rules of manga's high quality renderer (WebGpuViewer's
 * `spreadPartner`, `progressPage`): a spread never crosses a chapter, a seam's page stands alone, and a spread
 * reads to its right page. Text pages pair from the chapter's first, since a novel has no cover page to skip.
 * One rule for both renderers, over the pages [NovelPaging.cut] gives them.
 */
object NovelSpreads {

    /** The spread's left page, for the spread page [index] is on. */
    fun leftOf(pages: List<Page>, index: Int): Int {
        if (index !in pages.indices) return index
        // A seam only ever heads a chapter, before any text, so it always starts a spread of its own.
        val ordinal = (0 until index).count { !pages[it].seam }
        return if (ordinal % 2 == 0) index else index - 1
    }

    /** The spread's right page, for the spread whose left page is [left]: null on a seam's page, and on a
     *  chapter's last page when it falls on the left. */
    fun partnerOf(pages: List<Page>, left: Int): Int? {
        if (left !in pages.indices || pages[left].seam || leftOf(pages, left) != left) return null
        return (left + 1).takeIf { it in pages.indices }
    }

    /** The page a spread has been read to, which its progress and page number are taken from. */
    fun readTo(pages: List<Page>, index: Int): Int = leftOf(pages, index).let { partnerOf(pages, it) ?: it }

    /** [NovelPaging.turn] a spread at a time: the next spread's left page, or the chapter beside this one. */
    fun turn(window: List<Long>, chapterId: Long, pages: List<Page>, index: Int, forward: Boolean): Turn {
        val left = leftOf(pages, index)
        val next = if (forward) readTo(pages, index) + 1 else left - 1
        if (next in pages.indices) return Turn.Within(chapterId, leftOf(pages, next))
        return NovelPaging.turn(window, chapterId, if (forward) pages.lastIndex else 0, pages.size, forward)
    }
}
