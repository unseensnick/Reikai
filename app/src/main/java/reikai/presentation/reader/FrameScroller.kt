package reikai.presentation.reader

import android.view.Choreographer

/**
 * A scroll driven once a frame, moving its view the way a drag does rather than competing with it.
 * The speed is the web renderer's unit, a CSS pixel a frame at 60Hz, so it becomes a rate in device
 * pixels here; without [density] the same setting would move three times as far in one renderer.
 * [scrollBy] answers false to hold the scroll, which drops the carried fraction.
 */
class FrameScroller(private val density: Float, private val scrollBy: (Int) -> Boolean) {

    private val carry = ScrollCarry()
    private var pxPerSecond = 0f

    /** Scrolls at [pixelsPerFrame]; 0 stops. */
    fun run(pixelsPerFrame: Float) {
        val rate = pixelsPerFrame * FRAMES_PER_SECOND * density
        if (rate == pxPerSecond) return
        val wasRunning = pxPerSecond > 0f
        pxPerSecond = rate
        if (rate <= 0f) {
            Choreographer.getInstance().removeFrameCallback(frames)
        } else if (!wasRunning) {
            carry.reset()
            Choreographer.getInstance().postFrameCallback(frames)
        }
    }

    private val frames = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (pxPerSecond <= 0f) return
            carry.onFrame(frameTimeNanos, pxPerSecond, scrollBy)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private companion object {
        const val FRAMES_PER_SECOND = 60f
    }
}
