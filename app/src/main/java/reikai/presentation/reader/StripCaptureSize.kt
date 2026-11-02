package reikai.presentation.reader

import ca.mpreg.webgpuviewer.viewer.ComposePageSize

/** How many times smaller, each way, a strip's chrome page is captured than the slot it fills. */
internal const val STRIP_CAPTURE_SCALE = 2

/**
 * The size a long strip's loading or error page is captured at. The viewer keeps one capture buffer per
 * Compose page in reach, up to 27 pages zoomed out, and a window each ran the heap out; a failed page keeps
 * its buffer until Retry. The strip stretches the page to its width, so the slot keeps its shape.
 */
internal fun stripCaptureSize(viewerWidth: Int, viewerHeight: Int, heightFraction: Float): ComposePageSize.Of =
    ComposePageSize.Of(
        (viewerWidth / STRIP_CAPTURE_SCALE).coerceAtLeast(1),
        (viewerHeight * heightFraction / STRIP_CAPTURE_SCALE).toInt().coerceAtLeast(1),
    )
