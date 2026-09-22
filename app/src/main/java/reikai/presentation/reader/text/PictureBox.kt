package reikai.presentation.reader.text

import kotlin.math.min
import kotlin.math.roundToInt

/** A picture's box in the column, in pixels. */
internal data class PictureBox(val width: Int, val height: Int)

/**
 * A picture's place in the column: its own width in density-independent pixels, as the page's
 * `max-width: 100%` draws it, up to the column. Measured from the picture at its source, never from the
 * copy that was decoded, which is smaller whenever the decode hit its size cap, as a strip taller than
 * 4096px does; sized from that copy, such a picture drew at a fraction of the width the page gives it.
 * Null for a size with nothing to draw.
 */
internal fun pictureBox(sourceWidth: Int, sourceHeight: Int, columnPx: Int, density: Float): PictureBox? {
    if (sourceWidth <= 0 || sourceHeight <= 0 || columnPx <= 0) return null
    val width = min(columnPx, (sourceWidth * density).roundToInt()).coerceAtLeast(1)
    val height = (sourceHeight * (width.toFloat() / sourceWidth)).toInt().coerceAtLeast(1)
    return PictureBox(width, height)
}
