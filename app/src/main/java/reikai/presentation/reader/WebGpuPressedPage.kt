package reikai.presentation.reader

/*
 * Which page a press on the WebGPU viewers lands on. The library hands over only the press as a fraction of
 * the viewer and keeps its own placement internal, so these restate it over the state it exposes:
 * ImageSpread.leafRect for a spread's seam, and the strip's render walk (ImageViewerContinuousState).
 */

/**
 * Whether a press at [pressX], a fraction of the viewer's width, falls left of a spread's seam. The seam is the
 * spread's anchor, so it moves with the spread's pan [x] and zoom [scale] and is never the screen's middle.
 */
internal fun pressIsLeftOfSeam(pressX: Float, x: Float, scale: Float, rendererOffsetX: Float): Boolean =
    pressX < 0.5f + scale * (x + rendererOffsetX)

/**
 * The index from the strip's top page (0) of the page a press at [pressY], a fraction of the viewer's height,
 * lands on. [scrollY] is how far page 0's top sits above the viewer, and [gap], [scrollY] and [contentHeight]
 * are page pixels, which [scale] maps to screen pixels. Null in a gap, above page 0 or past [maxPages].
 */
internal fun stripPageAt(
    pressY: Float,
    viewerHeight: Float,
    scale: Float,
    scrollY: Float,
    gap: Float,
    maxPages: Int,
    contentHeight: (index: Int) -> Float?,
): Int? {
    if (scale <= 0f) return null
    var y = pressY * viewerHeight / scale + scrollY
    if (y < 0f) return null
    for (index in 0..maxPages) {
        val height = contentHeight(index) ?: return null
        if (y < height) return index
        y -= height + gap
        if (y < 0f) return null
    }
    return null
}
