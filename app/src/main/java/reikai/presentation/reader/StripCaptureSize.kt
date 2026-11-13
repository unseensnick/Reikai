package reikai.presentation.reader

import ca.mpreg.webgpuviewer.viewer.ComposePageSize

/** How many times smaller, each way, a strip's chrome page is captured than the slot it fills. */
internal const val STRIP_CAPTURE_SCALE = 2

/**
 * The size a long strip's loading or error page is captured at. Up to 27 such pages are in reach zoomed out,
 * each keeping a snapshot texture of this size, and they take turns at one shared capture, so a quarter of the
 * pixels is a quarter of the texture memory and turns four times as fast. The strip stretches the page to its
 * width, so the slot keeps its shape.
 */
internal fun stripCaptureSize(viewerWidth: Int, viewerHeight: Int, heightFraction: Float): ComposePageSize.Of =
    ComposePageSize.Of(
        (viewerWidth / STRIP_CAPTURE_SCALE).coerceAtLeast(1),
        (viewerHeight * heightFraction / STRIP_CAPTURE_SCALE).toInt().coerceAtLeast(1),
    )
