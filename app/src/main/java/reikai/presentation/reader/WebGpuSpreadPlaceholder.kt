package reikai.presentation.reader

import kotlin.math.roundToInt

// The WebGPU viewer's placeholder sizing rule, kept out of it so it can be tested without a surface.

/**
 * The width a loading or failed page reports, against the viewport's height. The library scales a
 * spread's two sides to one height and lays their widths side by side, so a placeholder shaped like
 * half the screen beside a page of any other shape splits the spread unevenly. Shaped like its
 * partner it takes exactly half, which is where its own image lands.
 *
 * @param partnerAspect the decoded partner's width over height, or null while there is none.
 * @param viewportWidth the screen's share for this page: half of it as a spread side.
 */
internal fun placeholderSideWidth(
    isSpreadSide: Boolean,
    partnerAspect: Float?,
    viewportWidth: Int,
    viewportHeight: Int,
): Int {
    if (!isSpreadSide || partnerAspect == null || partnerAspect <= 0f) return viewportWidth
    return (viewportHeight * partnerAspect).roundToInt()
}
