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
 * How long the rest of the chapter takes at the learned pace, else at the [live] one measured in it
 * ([LiveReading]), the page on screen counted as unread; null with no position to measure from.
 */
fun chapterTimeLeft(chapter: ChapterPace, progress: ChapterProgress?, live: Double? = null): ReaderTimeLeft? {
    val before = progress?.unitsBefore(chapter.units) ?: return null
    val msPerUnit = (chapter.reading as? ReadingPace.Known)?.msPerUnit ?: live
        ?: return ReaderTimeLeft.Learning(listening = false)
    return ReaderTimeLeft.Remaining(((chapter.units - before) * msPerUnit).milliseconds)
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

/**
 * How much of a chapter [units] long lies before the position, the page on screen counted as unread; null
 * before its pages load. Multiplied before dividing, so whole pages come out whole.
 */
internal fun ChapterProgress.unitsBefore(units: Long): Double? = when (this) {
    is ChapterProgress.Pages ->
        pageCount.takeIf { it > 0 }?.let { units.toDouble() * lastPageRead.coerceIn(0, it) / it }
    is ChapterProgress.Percent ->
        units.toDouble() * hundredths.coerceIn(0L, ChapterProgress.Percent.FULL) / ChapterProgress.Percent.FULL
}
