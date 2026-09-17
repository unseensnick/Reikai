package reikai.presentation.reader

import androidx.compose.runtime.Immutable
import reikai.domain.reader.ChapterProgress

/**
 * What the reader's progress navigator shows for the open session. [progress] is null while nothing is
 * loaded, which is what leaves the slider off rather than drawing an empty one. Only manga reaches that
 * state: a novel knows its resumed percent before the chapter renders, so it always answers a value.
 */
@Immutable
data class ReaderNavigatorState(
    val progress: ChapterProgress? = null,
    val shape: ReaderNavigatorShape = ReaderNavigatorShape.Slider,
    val railOnLeft: Boolean = false,
    val railHeightPercent: Int = 100,
    /** Whether a chapter is reachable in each direction, which is what enables the step buttons. */
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
)

/** How the progress navigator is drawn. With [None] the chapter buttons move to the ends of the button bar. */
enum class ReaderNavigatorShape {
    /** The horizontal bar above the buttons. */
    Slider,

    /** The vertical rail on the reader's edge. */
    Rail,
    None,
}
