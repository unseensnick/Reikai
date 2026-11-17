package reikai.presentation.reader

import kotlin.math.abs

/**
 * Which scroll frames hide the reader's menu, for every scrolling renderer of both readers: one past
 * [threshold] (Sensitivity for hiding menu on scroll) made by a finger, or by the fling it lets go into,
 * never one the app makes (auto-scroll, a jump, a landing). Each renderer reports per frame whether a finger
 * is on the content; a fling ends at the first gap of [FLING_GAP_MS] between frames, since not every
 * renderer reports when a scroll stops. One instance per renderer, fed from one thread.
 */
class MenuHideOnScroll(
    private val threshold: Int,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private var dragging = false
    private var flinging = false
    private var lastFrameAt = 0L

    /** Whether a frame that scrolled [dy] pixels, with a finger [touching] the content or not, hides the menu. */
    fun hides(dy: Float, touching: Boolean): Boolean {
        val now = clock()
        if (touching) {
            dragging = true
            flinging = false
        } else {
            flinging = (dragging || flinging) && now - lastFrameAt <= FLING_GAP_MS
            dragging = false
        }
        lastFrameAt = now
        return (dragging || flinging) && abs(dy) > threshold
    }

    companion object {
        /** Longer than any frame of a fling, shorter than a pause before a scroll the reader did not make. */
        const val FLING_GAP_MS = 100L
    }
}
