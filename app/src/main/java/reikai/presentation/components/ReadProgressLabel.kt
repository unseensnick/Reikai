package reikai.presentation.components

import androidx.compose.runtime.Composable
import dev.icerock.moko.resources.StringResource
import reikai.domain.reader.ChapterProgress
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * How far into a chapter reading stopped, written out in whichever unit the engine behind it counts.
 * The one line every chapter row draws (recents, details), so a rounding or a hide-at-zero rule cannot
 * differ by content type or by surface.
 */
@Composable
fun readProgressLabel(progress: ChapterProgress?): String? = when (progress) {
    null -> null
    is ChapterProgress.Pages -> pageProgressLabel(progress.lastPageRead, progress.pageCount)
        ?.let { (resource, args) -> stringResource(resource, *args) }
    is ChapterProgress.Percent -> percentProgressLabel(progress.hundredths)
}

/**
 * Which string names the page reading stopped on, and its arguments, or null before reading visibly
 * starts. A page is stored zero-based and reads one-based. A count of 0 means the reader has never
 * loaded that chapter, so the total is left off rather than shown as a bare 0: nothing backfills it,
 * and it arrives the next time that chapter is opened.
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
    ChapterProgress.Percent(hundredths).wholePercent.takeIf { it > 0L }?.let { "$it%" }
