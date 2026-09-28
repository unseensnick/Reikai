package reikai.presentation.reader

import kotlinx.coroutines.flow.Flow
import tachiyomi.core.common.preference.Preference

/**
 * How a viewport moves on its own, in one of two shapes, each with the setting its rate is read from.
 * The engine owns whether it runs and when it pauses; the viewport only says how to take a step or how
 * to move at a speed, and the settings sheet offers whichever rate the shape running now reads.
 */
sealed interface ViewportAutoScroll {

    /**
     * Turns a page every [intervalSeconds], counted from when the page on screen is [ShownPage.ready],
     * so a loading page does not use up its reading time. Only this shape is asked about readiness,
     * since a continuous scroll holds itself at a page that is still loading.
     */
    class Stepped(
        val intervalSeconds: Preference<Int>,
        val shownPage: Flow<ShownPage>,
        val advance: () -> Unit,
    ) : ViewportAutoScroll

    /**
     * Scrolls at [speed], in CSS pixels a frame at 60Hz, while [run] is given it; 0 stops. Called with
     * 0 after the viewport may already be destroyed, so that call must be safe then.
     */
    class Continuous(
        val speed: Preference<Float>,
        val run: (Float) -> Unit,
    ) : ViewportAutoScroll
}

/** The page a stepped scroll is counting on. [key] changes when a different page, or a reload, shows. */
data class ShownPage(val key: Any, val ready: Boolean)
