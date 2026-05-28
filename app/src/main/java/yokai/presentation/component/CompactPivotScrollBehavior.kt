package yokai.presentation.component

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.TopAppBarState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource

/**
 * Asymmetric top-bar scroll behavior that mirrors the legacy `ExpandedAppBarLayout`'s
 * scroll-driven `setTranslationY` (refs/yokai ExpandedAppBarLayout.kt:215-225, 278-381).
 *
 * The bar has THREE distinguishable y positions, parameterized by [state.heightOffset]:
 *   - `0f`: fully expanded (headline + action row + card + tabs)
 *   - `-compactThreshold`: collapsed to compact (card + tabs only)
 *   - `state.heightOffsetLimit` (negative, more than compact): fully hidden (phone only)
 *
 * Scroll behavior is direction-dependent:
 *   - **Scroll-down**: standard enterAlways-style. Consumes scroll deltas in `onPreScroll`
 *     to collapse the bar by the dragged amount, all the way down to `heightOffsetLimit`
 *     (= fully hidden on phone, = compact on tablet via the bar's own limit). Bar follows
 *     the finger 1:1 like upstream's `bar.translationY = -offset` math.
 *   - **Scroll-up**: consumes scroll in `onPreScroll` 1:1 to restore the bar from
 *     whatever offset it sits at back to fully expanded. Matches the bottom nav's restore
 *     rate (ControllerExtensions.kt:548) which is also 1:1 every frame regardless of
 *     content position, so the bar and the nav reappear in lockstep.
 *
 * `onPostScroll` is kept as a safety net for fling-only deltas that bypass `onPreScroll`
 * (the layered flings the lazy lists dispatch when content reaches the boundary).
 *
 * @param compactThresholdProvider unused after the speed-match change but kept on the
 *   constructor so existing call sites (LibraryContent / LibraryHostController) don't have
 *   to churn. Will be deleted once nothing supplies it.
 */
@OptIn(ExperimentalMaterial3Api::class)
class CompactPivotScrollBehavior(
    override val state: TopAppBarState,
    private val compactThresholdProvider: () -> Float,
    override val snapAnimationSpec: AnimationSpec<Float>? = null,
    override val flingAnimationSpec: DecayAnimationSpec<Float>? = null,
) : TopAppBarScrollBehavior {
    override val isPinned: Boolean = false

    override val nestedScrollConnection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val dy = available.y
            if (dy == 0f) return Offset.Zero
            val currentOffset = state.heightOffset
            val limit = state.heightOffsetLimit

            return when {
                dy < 0f -> {
                    // Scroll-down: consume to collapse the bar, clamped to the (negative) limit.
                    val newOffset = (currentOffset + dy).coerceAtLeast(limit)
                    val consumed = newOffset - currentOffset
                    if (consumed != 0f) state.heightOffset = newOffset
                    Offset(0f, consumed)
                }
                else -> {
                    // Scroll-up (anywhere from fully hidden to fully expanded): consume 1:1.
                    // Matches the bottom nav's restore rate (ControllerExtensions.kt:548 +
                    // LibraryContent.kt navHideConnection), which moves `translationY += dy`
                    // every frame regardless of content position. Bar and nav now reappear at
                    // the same pace whether the bar is hidden or just compact.
                    val newOffset = (currentOffset + dy).coerceAtMost(0f)
                    val consumed = newOffset - currentOffset
                    if (consumed != 0f) state.heightOffset = newOffset
                    Offset(0f, consumed)
                }
            }
        }

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            val dy = available.y
            if (dy <= 0f) return Offset.Zero
            val currentOffset = state.heightOffset
            if (currentOffset >= 0f) return Offset.Zero // bar already fully expanded

            // Positive available delta after content scroll means content reached its top
            // (it didn't consume the scroll). Consume to expand the bar back toward 0.
            val newOffset = (currentOffset + dy).coerceAtMost(0f)
            val consumedHere = newOffset - currentOffset
            if (consumedHere != 0f) state.heightOffset = newOffset
            return Offset(0f, consumedHere)
        }
    }
}
