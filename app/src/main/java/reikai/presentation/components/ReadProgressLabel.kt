package reikai.presentation.components

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/**
 * Which string names the page reading stopped on, and its arguments, or null before reading visibly
 * starts. Lives here rather than at its call sites because the same line is drawn from a composable
 * on a recents row and from a ViewModel on the details chapter list. A page is stored zero-based and
 * reads one-based. A count of 0 means the reader has never loaded that chapter, so the total is left
 * off rather than shown as a bare 0: nothing backfills it, and it arrives the next time that chapter
 * is opened.
 */
fun pageProgressLabel(lastPageRead: Long, pageCount: Long): Pair<StringResource, Array<Any>>? {
    val page = lastPageRead.takeIf { it > 0L }?.let { it + 1 } ?: return null
    return when {
        pageCount > 0L -> MR.strings.chapter_progress_of_total to arrayOf<Any>(page, pageCount)
        else -> MR.strings.chapter_progress to arrayOf<Any>(page)
    }
}

/**
 * The same line for a novel, whose reader stores hundredths of a percent. A fraction of a percent
 * claims no progress rather than rounding up to one, and no total is written: a percent carries it.
 */
fun percentProgressLabel(hundredths: Long): String? =
    (hundredths / 100L).takeIf { it > 0L }?.let { "$it%" }
