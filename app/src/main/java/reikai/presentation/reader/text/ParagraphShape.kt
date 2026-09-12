package reikai.presentation.reader.text

/**
 * The values the chapter's spans are built from, paragraph shape, bionic emphasis and image bounds.
 *
 * Indent and spacing become pixels at build time, bionic emphasis becomes bold spans, and an image's
 * bounds are fixed to the text column, so none of them can be restyled into a view afterwards: the
 * chapter has to be drawn again.
 */
data class ParagraphShape(
    val indent: Float,
    val spacing: Float,
    val fontSize: Int,
    val bionic: Boolean,
    /** Left plus right, in dp: the text column width every image was bound to when the chapter was
     *  built. A restyle re-pads the column but cannot re-fit a drawable, which would then overflow. */
    val sideMargins: Int,
) {

    /**
     * Whether moving to [next] owes a redraw rather than a restyle.
     *
     * A size change alone counts only while something is measured against it. That exemption is the
     * point of the rule: with both at zero, dragging the text-size slider restyles the views it
     * already has instead of re-parsing the chapter per step. Spacing defaults above zero, so by
     * default each step is a redraw, which is why the viewport lets a newer one supersede it.
     */
    fun needsRedrawFor(next: ParagraphShape): Boolean {
        if (indent != next.indent || spacing != next.spacing || bionic != next.bionic) return true
        if (sideMargins != next.sideMargins) return true
        val measuredAgainstSize = next.indent > 0f || next.spacing > 0f
        return measuredAgainstSize && fontSize != next.fontSize
    }
}
