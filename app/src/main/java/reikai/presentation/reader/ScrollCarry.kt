package reikai.presentation.reader

/**
 * The arithmetic of a frame-driven scroll, kept apart from the Choreographer so it runs on the JVM.
 * The fraction is carried between frames, or a speed below one pixel a frame never moves at all.
 */
internal class ScrollCarry {

    private var lastFrameNanos = NO_FRAME
    private var carry = 0f

    fun reset() {
        lastFrameNanos = NO_FRAME
        carry = 0f
    }

    /**
     * Moves by what [pxPerSecond] covers since the last frame. The first frame only takes a timestamp,
     * since there is no interval to scroll over yet. A step [scrollBy] refuses drops the carry, so a
     * scroll held at a loading page resumes at its speed instead of jumping by what it saved up.
     */
    fun onFrame(frameTimeNanos: Long, pxPerSecond: Float, scrollBy: (Int) -> Boolean) {
        val previous = lastFrameNanos
        lastFrameNanos = frameTimeNanos
        if (previous == NO_FRAME) return
        carry += pxPerSecond * ((frameTimeNanos - previous) / NANOS_PER_SECOND)
        val whole = carry.toInt()
        if (whole == 0) return
        carry -= whole
        if (!scrollBy(whole)) carry = 0f
    }

    private companion object {
        const val NO_FRAME = 0L
        const val NANOS_PER_SECOND = 1_000_000_000f
    }
}
