package reikai.domain.reader

import reikai.domain.library.ContentType
import reikai.domain.readinglog.ReadingPace
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The open chapter's length in its type's pace unit (pages for manga, words for a novel) and the series
 * it is read in, every member of a merge group, which is what a pace is learned for. [recorded] is false
 * for a chapter read in incognito, which keeps no length or listened time, as it keeps no history.
 */
data class ChapterLength(
    val contentType: ContentType,
    val seriesIds: Set<Long>,
    val chapterId: Long,
    val units: Long,
    val recorded: Boolean,
)

/** The open chapter's length with the paces learned for it, read and listened to. */
data class ChapterPace(val units: Long, val reading: ReadingPace, val listening: ReadingPace)

/** What the reader shows of the chapter's remaining time. */
sealed interface ReaderTimeLeft {
    /** Not enough finished chapters yet to know the pace, read or [listening]. */
    data class Learning(val listening: Boolean) : ReaderTimeLeft

    data class Remaining(val duration: Duration) : ReaderTimeLeft
}

/**
 * How long the rest of the chapter takes at the learned pace, the page on screen counted as unread; null
 * with no position to measure from.
 */
fun chapterTimeLeft(chapter: ChapterPace, progress: ChapterProgress?): ReaderTimeLeft? {
    val left = progress?.remainingFraction ?: return null
    return when (val pace = chapter.reading) {
        ReadingPace.Learning -> ReaderTimeLeft.Learning(listening = false)
        is ReadingPace.Known -> ReaderTimeLeft.Remaining((chapter.units * left * pace.msPerUnit).milliseconds)
    }
}

/**
 * How long read-aloud takes over the rest of what it speaks, the paragraph being spoken counted as
 * unspoken: at the [live] pace measured since play, else the [learned] listening pace.
 */
fun listeningTimeLeft(progress: ReadAloudProgress, live: Double?, learned: ReadingPace): ReaderTimeLeft {
    val msPerWord = live ?: (learned as? ReadingPace.Known)?.msPerUnit
        ?: return ReaderTimeLeft.Learning(listening = true)
    val left = (progress.words - progress.wordsSpoken).coerceAtLeast(0)
    return ReaderTimeLeft.Remaining((left * msPerWord).milliseconds)
}

private val ChapterProgress.remainingFraction: Double?
    get() = when (this) {
        is ChapterProgress.Pages ->
            pageCount.takeIf { it > 0 }?.let { (it - lastPageRead).coerceIn(0, it).toDouble() / it }
        is ChapterProgress.Percent ->
            (ChapterProgress.Percent.FULL - hundredths).coerceIn(0L, ChapterProgress.Percent.FULL).toDouble() /
                ChapterProgress.Percent.FULL
    }
