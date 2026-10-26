package reikai.presentation.reader.text

/**
 * The veil both renderers draw over a chapter while its saved percent waits for the chapter's pictures: until
 * it lands the screen shows the chapter's top, and a scroll would cancel the landing and strand the reader
 * there. Touches are held off the text while it is up. [onChange] draws or drops it; a line landing never waits.
 */
internal class ResumeVeil(private val onChange: (up: Boolean) -> Unit) {

    var isUp = false
        private set

    fun set(up: Boolean) {
        if (up == isUp) return
        isUp = up
        onChange(up)
    }

    companion object {
        /** The native renderer's hold for a landing at [fraction]: past the chapter's start, while a picture loads,
         *  until [CHAPTER_IMAGE_WAIT_MS] runs out. */
        fun waitsForPictures(fraction: Float, picturesLoading: Boolean, waitOver: Boolean): Boolean =
            pageWaits(fraction) && picturesLoading && !waitOver

        /** reader.js's `landAndReportReady` test: a page opening on a percent reports ready only once it landed. */
        fun pageWaits(initialFraction: Float): Boolean = initialFraction > 0f
    }
}
