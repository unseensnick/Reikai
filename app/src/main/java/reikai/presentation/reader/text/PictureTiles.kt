package reikai.presentation.reader.text

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/*
 * A tall picture is drawn from horizontal slices, so only the part on screen is decoded: decoding one
 * whole would cost the column's width times its own height, which for a webtoon strip runs to tens of
 * megabytes a picture. The slices are cut in the picture's own pixels; what is on screen arrives in the
 * pixels it is drawn at, so the two are converted here rather than at each call.
 */

/** How many slices a picture [sourceHeight] tall is cut into, at [tileSourceHeight] each. */
internal fun tileCount(sourceHeight: Int, tileSourceHeight: Int): Int {
    if (sourceHeight <= 0 || tileSourceHeight <= 0) return 0
    return ceil(sourceHeight.toDouble() / tileSourceHeight).toInt()
}

/** The rows of the picture slice [index] covers, empty when it lies past its end. */
internal fun tileRows(index: Int, tileSourceHeight: Int, sourceHeight: Int): IntRange {
    val top = index * tileSourceHeight
    if (index < 0 || tileSourceHeight <= 0 || top >= sourceHeight) return IntRange.EMPTY
    return top until min(top + tileSourceHeight, sourceHeight)
}

/**
 * The slices to hold for a picture drawn [drawnHeightPx] tall whose rows [visibleTopPx] to [visibleBottomPx]
 * are on screen, with [ahead] more either side so a scroll finds them already there. Empty when none of it
 * is on screen, which is what lets a picture scrolled away let its slices go.
 */
internal fun tilesFor(
    visibleTopPx: Int,
    visibleBottomPx: Int,
    drawnHeightPx: Int,
    tileCount: Int,
    ahead: Int,
): IntRange {
    if (tileCount <= 0 || drawnHeightPx <= 0 || visibleBottomPx <= 0 || visibleTopPx >= drawnHeightPx) {
        return IntRange.EMPTY
    }
    if (visibleBottomPx <= visibleTopPx) return IntRange.EMPTY
    val tileHeight = drawnHeightPx.toDouble() / tileCount
    val first = (visibleTopPx / tileHeight).toInt() - ahead
    val last = ((visibleBottomPx - 1) / tileHeight).toInt() + ahead
    return max(0, first)..min(tileCount - 1, last)
}

/** The picture's own rows one slice covers, for slices about [tileDrawnPx] tall as the picture is drawn. */
internal fun tileSourceHeight(sourceHeight: Int, drawnHeightPx: Int, tileDrawnPx: Int): Int {
    if (sourceHeight <= 0 || drawnHeightPx <= 0 || tileDrawnPx <= 0) return 0
    return max(1, (tileDrawnPx.toLong() * sourceHeight / drawnHeightPx).toInt())
}

/** How far down a slice is decoded: never below the width it is drawn at, and never up. */
internal fun tileSampleSize(sourceWidth: Int, drawnWidthPx: Int): Int {
    if (sourceWidth <= 0 || drawnWidthPx <= 0) return 1
    var sample = 1
    while (sourceWidth / (sample * 2) >= drawnWidthPx) sample *= 2
    return sample
}

/**
 * Whether a picture is worth drawing from slices: only when the loader had to shrink it ([decodedWidthPx]
 * below [sourceWidth]) and the box it is drawn in is wider than what it shrank to. A picture that decoded
 * whole has nothing sharper to offer, and slicing it would hold the same pixels twice.
 */
internal fun worthSlicing(sourceWidth: Int, decodedWidthPx: Int, drawnWidthPx: Int): Boolean =
    decodedWidthPx in 1 until sourceWidth && decodedWidthPx < drawnWidthPx

/**
 * How far down a picture stored inside a chapter is decoded: no larger than the column it is drawn in, and
 * never over [maxSidePx] a side. Without that second limit a stored strip decoded at its full height, which
 * for a tall one runs to tens of megabytes; the loader caps a fetched picture the same way.
 */
internal fun inlineSampleSize(sourceWidth: Int, sourceHeight: Int, columnPx: Int, maxSidePx: Int): Int {
    if (sourceWidth <= 0 || sourceHeight <= 0) return 1
    var sample = 1
    if (columnPx > 0) while (sourceWidth / (sample * 2) >= columnPx) sample *= 2
    if (maxSidePx > 0) while (sourceWidth / sample > maxSidePx || sourceHeight / sample > maxSidePx) sample *= 2
    return sample
}
