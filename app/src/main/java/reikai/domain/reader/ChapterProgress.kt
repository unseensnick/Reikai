package reikai.domain.reader

import androidx.compose.runtime.Immutable
import kotlin.math.floor

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
    data class Pages(val lastPageRead: Int, val pageCount: Int) : ChapterProgress {
        override val hasStarted: Boolean get() = lastPageRead > 0
    }

    /** Hundredths of a percent, the unit the novel reader stores. */
    data class Percent(val hundredths: Long) : ChapterProgress {
        override val hasStarted: Boolean get() = hundredths > 0L

        /** Rounded down, so a fraction of a percent claims no progress. */
        val wholePercent: Long get() = hundredths / HUNDREDTHS_PER_PERCENT

        companion object {
            private const val HUNDREDTHS_PER_PERCENT = 100L

            /** All of a chapter. */
            const val FULL = 10_000L

            val END = Percent(FULL)

            /** A whole percent, the unit a reader's thresholds are set in. */
            fun ofWhole(percent: Int): Percent = Percent(percent * HUNDREDTHS_PER_PERCENT)

            /**
             * What both novel renderers report for a scroll [fraction]: rounded down, so a reopen never lands
             * past the line it was saved at. The slack absorbs a fraction echoed back through a double.
             */
            fun of(fraction: Double): Percent =
                Percent(floor(fraction * FULL + ECHO_SLACK).toLong().coerceIn(0L, FULL))

            private const val ECHO_SLACK = 1e-6
        }
    }
}
