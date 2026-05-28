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
 *   - **Scroll-up while bar is past compact** (heightOffset < `compactThreshold`):
 *     consumes scroll in `onPreScroll` to slide the bar back UP TO compact (not further).
 *     Any leftover scroll passes through to content.
 *   - **Scroll-up while bar is between compact and full**: does NOT consume in
 *     `onPreScroll`. Content gets the scroll first. If content reaches its top and still
 *     has unused scroll, `onPostScroll` consumes the leftover to expand the bar.
 *
 * Net effect: the bar can fully hide on continued scroll-down, but it never re-expands
 * past compact unless the user has scrolled their content back to the very top. Matches
 * upstream where the bar's `updateAppBarAfterY` follows `-offset` until the small toolbar
 * + tabs are exposed, then sticks there until the content's `computeVerticalScrollOffset`
 * comes back down.
 *
 * @param compactThresholdProvider returns the (negative) heightOffset value at which the
 *   bar is considered "at compact" — i.e., `-collapsibleHeightPx`. Read every scroll event
 *   so it stays in sync with the bar's measured collapsible block.
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
            val threshold = compactThresholdProvider()

            return when {
                dy < 0f -> {
                    // Scroll-down: consume to collapse the bar, clamped to the (negative) limit.
                    val newOffset = (currentOffset + dy).coerceAtLeast(limit)
                    val consumed = newOffset - currentOffset
                    if (consumed != 0f) state.heightOffset = newOffset
                    Offset(0f, consumed)
                }
                currentOffset < threshold -> {
                    // Scroll-up while bar is past compact (hidden zone): consume to bring it
                    // back to compact, but no further. Any leftover scroll-up passes through
                    // to content.
                    val newOffset = (currentOffset + dy).coerceAtMost(threshold)
                    val consumed = newOffset - currentOffset
                    if (consumed != 0f) state.heightOffset = newOffset
                    Offset(0f, consumed)
                }
                else -> {
                    // Scroll-up while bar is in the compact-to-full range: don't consume here.
                    // Content gets the scroll first; expansion happens in onPostScroll only
                    // when content can't take more (i.e., it's reached its top).
                    Offset.Zero
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
