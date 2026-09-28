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
     * A size change alone counts only while something is measured against it: indent, spacing, or a
     * picture the window holds, whose margins and failure box are sized from the text. With none of
     * them a text-size drag restyles the views already built instead of re-parsing per step; spacing
     * defaults above zero, so by default each step is a redraw, which a newer one supersedes.
     */
    fun needsRedrawFor(next: ParagraphShape, holdsPictures: Boolean): Boolean {
        if (indent != next.indent || spacing != next.spacing || bionic != next.bionic) return true
        if (sideMargins != next.sideMargins) return true
        val measuredAgainstSize = next.indent > 0f || next.spacing > 0f || holdsPictures
        return measuredAgainstSize && fontSize != next.fontSize
    }
}
