package yokai.presentation.core.util

import androidx.compose.foundation.lazy.LazyListState

/**
 * Whether a scroll-aware FAB should show its extended (labelled) form: expand when the user scrolls
 * up or the list can't scroll in a direction (top/bottom reached), collapse to the icon while
 * scrolling down through content. Ported from Mihon's `presentation-core` utility.
 */
fun LazyListState.shouldExpandFAB(): Boolean =
    lastScrolledBackward || !canScrollForward || !canScrollBackward
