package tachiyomi.presentation.core.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Set by a swipeable pager (e.g. `TabbedDialog`) so its content can temporarily turn the pager's
 * user-swipe off. Defaults to a no-op, so [lockPagerSwipeWhileDragging] is harmless when there is no
 * enclosing pager.
 */
val LocalPagerSwipeSetter = compositionLocalOf<(Boolean) -> Unit> { {} }

/**
 * While a pointer is down on this element, disable the enclosing pager's user-swipe (via
 * [LocalPagerSwipeSetter]) and re-enable it when the gesture ends. The gesture is NOT consumed, so the
 * element's own drag handling (a slider thumb, a horizontally-scrolling chip row) still works. This
 * stops a horizontal drag on such an element from leaking into the pager and switching tabs, while
 * leaving swipe-to-switch on the rest of the page intact.
 */
fun Modifier.lockPagerSwipeWhileDragging(): Modifier = composed {
    val setSwipeEnabled = LocalPagerSwipeSetter.current
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            setSwipeEnabled(false)
            try {
                do {
                    val event = awaitPointerEvent()
                } while (event.changes.any { it.pressed })
            } finally {
                setSwipeEnabled(true)
            }
        }
    }
}
