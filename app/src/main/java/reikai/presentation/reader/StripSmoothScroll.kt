package reikai.presentation.reader

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.withContext

/** Normal speed, whatever the system's animator duration scale, as the library pins its own flings. */
private object NormalMotion : MotionDurationScale {
    override val scaleFactor = 1f
}

/**
 * Scrolls [delta] pixels on the spring the library's `animateScroll` uses, handing [scrollBy] each frame's step.
 * That one runs at the system's animator duration scale, so with animations off it lands in one frame; the classic
 * long strip's smooth scroll ignores the scale, and so does this. Needs a frame clock in the caller's context.
 */
internal suspend fun springScroll(delta: Float, scrollBy: (Float) -> Unit) = withContext(NormalMotion) {
    var applied = 0f
    val spec = spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = 0.002f)
    animate(0f, delta, animationSpec = spec) { value, _ ->
        scrollBy(value - applied)
        applied = value
    }
}
