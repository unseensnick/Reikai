package reikai.presentation.reader

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import ca.mpreg.webgpuviewer.filter.Lut3d
import reikai.domain.reader.CONTRAST_DEFAULT_PERCENT

/**
 * The reader's grayscale, inverted colours and contrast as one colour matrix, which every viewer applies to
 * page images: the normal viewers and a novel's renderer through [colorFilter], the high quality renderer
 * through [lut]. Both come from [apply], so the renderers cannot drift apart. The three steps commute while
 * nothing clamps until the end, so their order changes nothing. See docs/dev/subsystems/reader.md.
 */
data class PageColorAdjust(
    val grayscale: Boolean = false,
    val inverted: Boolean = false,
    /** 100 leaves the page alone; contrast stretches or squeezes it around mid grey. */
    val contrastPercent: Int = CONTRAST_DEFAULT,
) {
    val isIdentity: Boolean get() = !grayscale && !inverted && contrastPercent == CONTRAST_DEFAULT

    /** Rows R, G, B; columns red, green, blue and an offset, all in 0..1. Alpha passes through. */
    private val matrix: FloatArray by lazy {
        var m = IDENTITY
        if (inverted) m = m.then(floatArrayOf(-1f, 0f, 0f, 1f, 0f, -1f, 0f, 1f, 0f, 0f, -1f, 1f))
        if (grayscale) m = m.then(GRAY)
        val c = contrastPercent / 100f
        if (c != 1f) {
            val offset = 0.5f * (1f - c)
            m = m.then(floatArrayOf(c, 0f, 0f, offset, 0f, c, 0f, offset, 0f, 0f, c, offset))
        }
        m
    }

    /** [rgb] in 0..1 through the matrix, clamped to 0..1 as a colour filter clamps its output. */
    fun apply(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        fun row(i: Int) = (matrix[i] * r + matrix[i + 1] * g + matrix[i + 2] * b + matrix[i + 3]).coerceIn(0f, 1f)
        return Triple(row(0), row(4), row(8))
    }

    /** For the normal viewers and a novel's renderer; null when the page is left alone. */
    fun colorFilter(): ColorFilter? = colorMatrix()?.let(ColorFilter::colorMatrix)

    /** [colorFilter]'s matrix, in the 0..255 offsets a colour matrix takes. */
    internal fun colorMatrix(): ColorMatrix? {
        if (isIdentity) return null
        val m = matrix
        return ColorMatrix(
            floatArrayOf(
                m[0], m[1], m[2], 0f, m[3] * 255f,
                m[4], m[5], m[6], 0f, m[7] * 255f,
                m[8], m[9], m[10], 0f, m[11] * 255f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
    }

    /** For the high quality renderer's page table, sampled from [apply]; null when the page is left alone. */
    fun lut(size: Int = LUT_SIZE): Lut3d? {
        if (isIdentity) return null
        val data = FloatArray(size * size * size * 3)
        var i = 0
        val step = 1f / (size - 1)
        for (b in 0 until size) {
            for (g in 0 until size) {
                for (r in 0 until size) {
                    val (outR, outG, outB) = apply(r * step, g * step, b * step)
                    data[i++] = outR
                    data[i++] = outG
                    data[i++] = outB
                }
            }
        }
        return Lut3d(size, data)
    }

    companion object {
        const val CONTRAST_DEFAULT = CONTRAST_DEFAULT_PERCENT
        const val CONTRAST_STEP = 10
        val CONTRAST_RANGE = 50..200

        /** Enough steps that a contrast's clamp at black and white shows no banding between them. */
        private const val LUT_SIZE = 33

        private val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)

        // The weights Compose's setToSaturation(0) uses, so grayscale reads as it did.
        private val GRAY = floatArrayOf(
            0.213f, 0.715f, 0.072f, 0f,
            0.213f, 0.715f, 0.072f, 0f,
            0.213f, 0.715f, 0.072f, 0f,
        )

        /** [next] applied after this one, both 3x4 matrices over 0..1. */
        private fun FloatArray.then(next: FloatArray): FloatArray {
            val out = FloatArray(12)
            for (row in 0 until 3) {
                for (col in 0 until 3) {
                    out[row * 4 + col] = (0 until 3).sumOf { k -> (next[row * 4 + k] * this[k * 4 + col]).toDouble() }
                        .toFloat()
                }
                out[row * 4 + 3] = next[row * 4 + 3] +
                    (0 until 3).sumOf { k -> (next[row * 4 + k] * this[k * 4 + 3]).toDouble() }.toFloat()
            }
            return out
        }
    }
}

/** The open session's [PageColorAdjust], for the high quality renderer, which applies it as its page table. */
val LocalPageColorAdjust = compositionLocalOf { PageColorAdjust() }
