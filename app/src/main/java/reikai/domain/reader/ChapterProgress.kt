package reikai.domain.reader

import androidx.compose.runtime.Immutable

/**
 * How far into a chapter the reader got, in the engine's own unit, so nothing shared has to know what
 * a page or a scroll position is. Whether a value rounds away to nothing is the renderer's call, since
 * only it knows how the number is written out.
 */
@Immutable
sealed interface ChapterProgress {
    /**
     * Whether the reader got past the start. Read from the stored value rather than the displayed one,
     * which rounds a novel's hundredths down and says nothing about whether the chapter was opened.
     */
    val hasStarted: Boolean

    /** [pageCount] is 0 where the reader has never loaded the chapter, so the row leaves it off. */
    data class Pages(val lastPageRead: Long, val pageCount: Long) : ChapterProgress {
        override val hasStarted: Boolean get() = lastPageRead > 0L
    }

    /** Hundredths of a percent, the unit the novel reader stores. */
    data class Percent(val hundredths: Long) : ChapterProgress {
        override val hasStarted: Boolean get() = hundredths > 0L
    }
}
