package reikai.domain.reader

/** How large the reader's progress line draws, as a multiple of upstream's size. */
enum class ReaderProgressSize(val scale: Float) {
    DEFAULT(1f),
    LARGE(1.25f),
    LARGER(1.5f),
    HUGE(2f),
    MAX(2.5f),
}

/**
 * How the progress line under the page looks. Each reader stores its own. A backed line sits on a strip of
 * the page's colour, so the text under it never shows through the readout.
 */
data class ReaderProgressLook(
    val size: ReaderProgressSize = ReaderProgressSize.DEFAULT,
    val isBacked: Boolean = false,
)
