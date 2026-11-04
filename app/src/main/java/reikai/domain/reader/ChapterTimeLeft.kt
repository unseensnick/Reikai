package reikai.domain.reader

import reikai.domain.library.ContentType
import reikai.domain.readinglog.ReadingPace
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The open chapter's length in its type's pace unit (pages for manga, words for a novel) and who it
 * belongs to, which is what a pace is learned for and stored against.
 */
data class ChapterLength(val contentType: ContentType, val entryId: Long, val chapterId: Long, val units: Long)

/** The open chapter's length with the pace learned for it. */
data class ChapterPace(val units: Long, val pace: ReadingPace)

/** What the reader shows of the chapter's remaining time. */
sealed interface ReaderTimeLeft {
    /** Not enough finished chapters yet to know the reader's pace. */
    data object Learning : ReaderTimeLeft

    data class Remaining(val duration: Duration) : ReaderTimeLeft
}

/**
 * How long the rest of the chapter takes at the learned pace, the page on screen counted as unread; null
 * with no position to measure from.
 */
fun chapterTimeLeft(chapter: ChapterPace, progress: ChapterProgress?): ReaderTimeLeft? {
    val left = progress?.remainingFraction ?: return null
    return when (val pace = chapter.pace) {
        ReadingPace.Learning -> ReaderTimeLeft.Learning
        is ReadingPace.Known -> ReaderTimeLeft.Remaining((chapter.units * left * pace.msPerUnit).milliseconds)
    }
}

private val ChapterProgress.remainingFraction: Double?
    get() = when (this) {
        is ChapterProgress.Pages ->
            pageCount.takeIf { it > 0 }?.let { (it - lastPageRead).coerceIn(0, it).toDouble() / it }
        is ChapterProgress.Percent ->
            (ChapterProgress.Percent.FULL - hundredths).coerceIn(0L, ChapterProgress.Percent.FULL).toDouble() /
                ChapterProgress.Percent.FULL
    }
