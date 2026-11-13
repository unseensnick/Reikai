package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.viewer.DualPageSettings
import tachiyomi.core.common.util.system.ImageUtil
import java.nio.ByteBuffer
import ca.mpreg.webgpuviewer.ImageUtil as LibraryImageUtil

/*
 * Split wide pages and Rotate to fit, as one rule for every viewer: the classic ones apply it to a re-encoded
 * bitmap (PageImageProcessing.kt), the high quality renderer to its decoded pixels, which keeps every format
 * and HDR the decoder reads. See docs/dev/subsystems/reader.md.
 */

/** Which half of a split page shows: the reading side first, so [second] is the other, both swapped by [invert]. */
fun splitHalfSide(isLeftToRight: Boolean, second: Boolean, invert: Boolean): ImageUtil.Side {
    val left = isLeftToRight != second
    return if (left != invert) ImageUtil.Side.LEFT else ImageUtil.Side.RIGHT
}

/** The half a long strip draws on top when it stacks a split page. */
fun stripUpperSide(invert: Boolean): ImageUtil.Side = if (invert) ImageUtil.Side.LEFT else ImageUtil.Side.RIGHT

/** Rotate to fit's turn: clockwise unless inverted. */
fun rotationDegrees(invert: Boolean): Float = if (invert) -90f else 90f

/**
 * Whether the page just found [split] moves the reader to its second half: only while it is [shown] and was reached
 * reading backward ([arrivedBackward]), where its second half is the page that comes before the one left.
 */
fun <K> landsOnSecondHalf(arrivedBackward: K?, shown: K?, split: K): Boolean =
    arrivedBackward == split && shown == split

sealed interface WidePageShape {
    data object Whole : WidePageShape
    data class Half(val side: ImageUtil.Side) : WidePageShape
    data class Stacked(val upper: ImageUtil.Side) : WidePageShape
    data class Rotated(val degrees: Float) : WidePageShape
}

/**
 * How a page of [width] x [height] is shown. Wide is wider than tall, as `ImageUtil.isWideImage` has it; rotate
 * wins over split. A [strip] stacks the halves, while a paged viewer shows one, [second] naming the later one.
 */
fun DualPageSettings.widePageShape(
    width: Int,
    height: Int,
    strip: Boolean,
    leftToRight: Boolean,
    second: Boolean,
): WidePageShape = when {
    width <= height -> WidePageShape.Whole
    dualPageRotateToFit -> WidePageShape.Rotated(rotationDegrees(dualPageRotateToFitInvert))
    !dualPageSplit -> WidePageShape.Whole
    strip -> WidePageShape.Stacked(stripUpperSide(dualPageInvert))
    else -> WidePageShape.Half(splitHalfSide(leftToRight, second, dualPageInvert))
}

class ReshapedPixels(val pixels: ByteBuffer, val width: Int, val height: Int)

/** Packed [pixels], [bytesPerPixel] each in rows of [width], redrawn as [shape]; halves are `width / 2` wide. */
fun reshapePixels(
    pixels: ByteBuffer,
    width: Int,
    height: Int,
    bytesPerPixel: Int,
    shape: WidePageShape,
): ReshapedPixels {
    val half = width / 2
    fun leftOf(side: ImageUtil.Side) = if (side == ImageUtil.Side.LEFT) 0 else width - half
    return when (shape) {
        WidePageShape.Whole -> ReshapedPixels(pixels, width, height)
        is WidePageShape.Half -> {
            val out = newPixels(half * height * bytesPerPixel, pixels)
            copyColumns(pixels, out, width, height, bytesPerPixel, leftOf(shape.side), half, 0)
            ReshapedPixels(out, half, height)
        }
        is WidePageShape.Stacked -> {
            val out = newPixels(half * height * 2 * bytesPerPixel, pixels)
            val lower = if (shape.upper == ImageUtil.Side.LEFT) ImageUtil.Side.RIGHT else ImageUtil.Side.LEFT
            copyColumns(pixels, out, width, height, bytesPerPixel, leftOf(shape.upper), half, 0)
            copyColumns(pixels, out, width, height, bytesPerPixel, leftOf(lower), half, height)
            ReshapedPixels(out, half, height * 2)
        }
        is WidePageShape.Rotated -> {
            // The library's native turn reads only a direct buffer and leaves the output blank for any other.
            val source = if (pixels.isDirect) pixels else directCopyOf(pixels)
            // The native turn also writes nothing into a buffer short of this, which would show a blank page.
            require(source.capacity() >= width * height * bytesPerPixel) { "Short pixel buffer for $width x $height" }
            val out = LibraryImageUtil.rotateQuarter(source, width, height, bytesPerPixel, shape.degrees > 0f)
            ReshapedPixels(out.order(pixels.order()), height, width)
        }
    }
}

private fun newPixels(size: Int, like: ByteBuffer): ByteBuffer = ByteBuffer.allocateDirect(size).order(like.order())

private fun directCopyOf(pixels: ByteBuffer): ByteBuffer {
    val src = pixels.duplicate()
    src.position(0)
    src.limit(src.capacity())
    val out = newPixels(src.capacity(), pixels)
    out.put(src)
    out.position(0)
    return out
}

/** Copies [columns] columns from [fromX] of every row into [out], starting at row [toRow] of a [columns]-wide image. */
private fun copyColumns(
    pixels: ByteBuffer,
    out: ByteBuffer,
    width: Int,
    height: Int,
    bytesPerPixel: Int,
    fromX: Int,
    columns: Int,
    toRow: Int,
) {
    val rowBytes = columns * bytesPerPixel
    val src = pixels.duplicate()
    val dst = out.duplicate()
    for (y in 0 until height) {
        val start = (y * width + fromX) * bytesPerPixel
        src.limit(start + rowBytes)
        src.position(start)
        dst.position((toRow + y) * rowBytes)
        dst.put(src)
    }
}
