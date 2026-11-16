package reikai.domain.reader

import androidx.compose.runtime.Immutable
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Where the reader is, scoped to the chapter it is in. The viewer holds more than one chapter at a
 * time, so pairing a number from one chapter with a total from another is the tear this type makes
 * unrepresentable. [chapterId] is a row id in the owning engine's own chapter table, and manga and
 * novel chapter ids are separate spaces, so never compare one across content types.
 */
@Immutable
data class ReaderPosition(
    val chapterId: Long,
    val progress: ChapterProgress,
)

/** The whole percent at which a continuous chapter counts as read, unless the reader sets its own. */
const val CONTINUOUS_COMPLETE_PERCENT = 97

/** Detents on a continuous chapter's rail, the count the novel reader has always drawn. */
private const val CONTINUOUS_DETENTS = 33

/**
 * Whether the chapter counts as read. A paged chapter finishes on its last page, which for a
 * one-page chapter is the page it opens on; a continuous one cannot finish on entry, which is why a
 * continuous chapter must never be modelled as a single stub page. A chapter the reader has never
 * loaded has a page count of 0 and so never matches, since a page is never read before the first.
 */
val ChapterProgress.isChapterComplete: Boolean
    get() = isChapterComplete(CONTINUOUS_COMPLETE_PERCENT)

/** [isChapterComplete] with a continuous chapter read from [continuousPercent], a whole percent. */
fun ChapterProgress.isChapterComplete(continuousPercent: Int): Boolean = when (this) {
    is ChapterProgress.Pages -> lastPageRead == pageCount - 1
    is ChapterProgress.Percent -> hundredths >= ChapterProgress.Percent.ofWhole(continuousPercent).hundredths
}

/** Where the thumb sits, 0 at the chapter's start and 1 at its end. */
val ChapterProgress.fraction: Float
    get() = when (this) {
        is ChapterProgress.Pages ->
            if (pageCount <= 1) 0f else (lastPageRead.toFloat() / (pageCount - 1)).coerceIn(0f, 1f)
        is ChapterProgress.Percent ->
            (hundredths.toFloat() / ChapterProgress.Percent.FULL).coerceIn(0f, 1f)
    }

/**
 * Discrete slider detents. Material throws below zero and the page arithmetic goes there for a
 * chapter of one page or of unknown length, so it is clamped here rather than guarded at each caller.
 */
val ChapterProgress.stepCount: Int
    get() = when (this) {
        is ChapterProgress.Pages -> (pageCount - 2).coerceAtLeast(0)
        // A percentage has no natural unit to detent on, so it keeps the count the novel rail has
        // always drawn. Scrubbing freely instead loses the marks that say the rail is a progress bar.
        is ChapterProgress.Percent -> CONTINUOUS_DETENTS
    }

/** Whether a chapter can be scrubbed at all. A single page has nowhere to scrub to. */
val ChapterProgress.isSeekable: Boolean
    get() = when (this) {
        is ChapterProgress.Pages -> pageCount > 1
        is ChapterProgress.Percent -> true
    }

/** The label at the reading end of the navigator. A page is stored zero-based and reads one-based. */
val ChapterProgress.leadingLabel: String
    get() = when (this) {
        is ChapterProgress.Pages -> (lastPageRead + 1).toString()
        is ChapterProgress.Percent -> "$wholePercent%"
    }

/** The label at the far end, which also sizes the leading slot so it cannot shift as digits grow. */
val ChapterProgress.trailingLabel: String
    get() = when (this) {
        is ChapterProgress.Pages -> pageCount.toString()
        is ChapterProgress.Percent -> "100%"
    }

/**
 * The zero-based page this sits on, or null where the medium has no pages. An exhaustive `when`
 * rather than a cast, so a medium added later cannot be silently read as a paged one.
 */
val ChapterProgress.pageIndex: Int?
    get() = when (this) {
        is ChapterProgress.Pages -> lastPageRead
        is ChapterProgress.Percent -> null
    }

/** The inverse of [fraction]: where a scrub lands, in the medium's own unit. */
fun ChapterProgress.seekTo(fraction: Float): ChapterProgress = when (this) {
    is ChapterProgress.Pages -> {
        val lastIndex = (pageCount - 1).coerceAtLeast(0)
        copy(lastPageRead = (fraction * lastIndex).roundToInt().coerceIn(0, lastIndex))
    }
    is ChapterProgress.Percent ->
        ChapterProgress.Percent.clamped((fraction * ChapterProgress.Percent.FULL).roundToLong())
}
