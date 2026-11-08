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
 * Paged, [maxHeightPx] is a page's height, and a taller picture shrinks to it keeping its shape when
 * [shrinksToPage] allows, as the page's paged `max-height` does. Null for a size with nothing to draw.
 */
internal fun pictureBox(
    sourceWidth: Int,
    sourceHeight: Int,
    columnPx: Int,
    density: Float,
    maxHeightPx: Int? = null,
): PictureBox? {
    if (sourceWidth <= 0 || sourceHeight <= 0 || columnPx <= 0) return null
    val width = min(columnPx, (sourceWidth * density).roundToInt()).coerceAtLeast(1)
    val height = (sourceHeight * (width.toFloat() / sourceWidth)).toInt().coerceAtLeast(1)
    if (maxHeightPx == null || maxHeightPx <= 0 || !shrinksToPage(height.toFloat(), maxHeightPx.toFloat())) {
        return PictureBox(width, height)
    }
    return PictureBox((width.toLong() * maxHeightPx / height).toInt().coerceAtLeast(1), maxHeightPx)
}

/**
 * Whether a picture [height] tall at its own width is drawn [pageHeight] tall, whole on one page, when paged:
 * only if it is taller than the page and needs shrinking to no less than [MIN_PAGE_FIT_SCALE]. Shrunk further, long
 * vertical art would be unreadable, so it keeps its width and runs across pages. The page asks the same
 * (`shrinksToPage` in reader.js), taking the scale from here.
 */
internal fun shrinksToPage(height: Float, pageHeight: Float): Boolean =
    height > pageHeight && pageHeight >= height * MIN_PAGE_FIT_SCALE

internal const val MIN_PAGE_FIT_SCALE = 0.5f
