package reikai.presentation.library

import android.view.ViewConfiguration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastMaxBy
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.sample
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A fast-scroll [LazyVerticalGrid] for Reikai's single-list library.
 *
 * Mihon's [tachiyomi.presentation.core.components.FastScrollLazyVerticalGrid] positions its thumb as
 * `rowsBefore * averageRowHeight`, which is unstable when a grid mixes tall cover rows with short
 * full-span category headers: the average wobbles as headers scroll through the viewport and, scaled
 * by the row count, the thumb jitters. Mihon documents this and ships the stable algorithm only for
 * plain lists.
 *
 * This grid borrows that stable approach: the thumb tracks a smooth proportion of rows scrolled past
 * ([LazyGridItemInfo.row] is span-aware), never averaging heights. The one input the proportion
 * approach can't infer through span headers, the total row count, is supplied by the caller
 * ([totalRows]) from the category data, along with [itemIndexForRow] so dragging the thumb can map a
 * target row back to a grid item.
 */
@Composable
fun ReikaiFastScrollLazyVerticalGrid(
    columns: GridCells,
    totalRows: Int,
    itemIndexForRow: (Int) -> Int,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    thumbAllowed: () -> Boolean = { true },
    thumbColor: Color = MaterialTheme.colorScheme.primary,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    topContentPadding: Dp = Dp.Hairline,
    bottomContentPadding: Dp = Dp.Hairline,
    endContentPadding: Dp = Dp.Hairline,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    content: LazyGridScope.() -> Unit,
) {
    SubcomposeLayout(modifier = modifier) { constraints ->
        val contentPlaceable = subcompose("content") {
            LazyVerticalGrid(
                columns = columns,
                state = state,
                contentPadding = contentPadding,
                verticalArrangement = verticalArrangement,
                horizontalArrangement = horizontalArrangement,
                content = content,
            )
        }.map { it.measure(constraints) }
        val contentHeight = contentPlaceable.fastMaxBy { it.height }?.height ?: 0
        val contentWidth = contentPlaceable.fastMaxBy { it.width }?.width ?: 0

        val scrollerConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val scrollerPlaceable = subcompose("scroller") {
            val layoutInfo = state.layoutInfo
            if (layoutInfo.visibleItemsInfo.isEmpty() ||
                layoutInfo.visibleItemsInfo.size >= layoutInfo.totalItemsCount ||
                totalRows <= 0
            ) {
                return@subcompose
            }

            val thumbTopPadding = with(LocalDensity.current) { topContentPadding.toPx() }
            var thumbOffsetY by remember(thumbTopPadding) { mutableFloatStateOf(thumbTopPadding) }

            val dragInteractionSource = remember { MutableInteractionSource() }
            val isThumbDragged by dragInteractionSource.collectIsDraggedAsState()
            val scrolled = remember {
                MutableSharedFlow<Unit>(
                    extraBufferCapacity = 1,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST,
                )
            }

            // listState.isScrollInProgress occasionally flickers
            val scrollStateTracker = remember { MutableData(state.isScrollInProgress) }
            val stableScrollInProgress = scrollStateTracker.value || state.isScrollInProgress
            scrollStateTracker.value = state.isScrollInProgress

            val thumbBottomPadding = with(LocalDensity.current) { bottomContentPadding.toPx() }
            val heightPx = contentHeight.toFloat() -
                thumbTopPadding -
                thumbBottomPadding -
                layoutInfo.afterContentPadding
            val thumbHeightPx = with(LocalDensity.current) { ThumbLength.toPx() }
            val trackHeightPx = heightPx - thumbHeightPx
            val visibleItems = layoutInfo.visibleItemsInfo

            // Anchor the thumb to the authoritative first-visible item + its scroll offset, converted
            // to a fractional row via the true row pitch (row height + spacing). This pixel-accurate
            // fraction is what keeps the thumb steady in grid modes, where each row is a large slice
            // of the track and a small error would show as jitter.
            val anchorIndex = state.firstVisibleItemIndex
            val anchorOffset = state.firstVisibleItemScrollOffset
            val anchorItem = visibleItems.fastFirstOrNull { it.index == anchorIndex } ?: visibleItems.first()
            val anchorRow = anchorItem.row.coerceAtLeast(0)
            val nextRowItem = visibleItems.fastFirstOrNull { it.row == anchorRow + 1 }
            val rowPitch = (
                if (nextRowItem != null) nextRowItem.offset.y - anchorItem.offset.y else anchorItem.size.height
                ).coerceAtLeast(1)
            val scrolledRows = anchorRow + (anchorOffset.toFloat() / rowPitch).coerceIn(0f, 1f)

            // Scrollable span in rows = totalRows - rowsThatFitOnScreen. Rows-on-screen wobbles as
            // short headers vs tall covers pass through, so use its running minimum (only shrinks):
            // a stable, converging denominator instead of a wobbling one. Reset when the list reshapes.
            val rowsOnScreen = (visibleItems.last().row - visibleItems.first().row + 1).coerceAtLeast(1)
            val minRowsOnScreen = remember(totalRows) { MutableData(Int.MAX_VALUE) }
            if (!isThumbDragged) minRowsOnScreen.value = min(minRowsOnScreen.value, rowsOnScreen)
            val maxScrollRows = (totalRows - minRowsOnScreen.value.coerceAtMost(totalRows)).coerceAtLeast(1).toFloat()

            // When thumb dragged: map the thumb position to a target row and jump there. Top-anchored,
            // the same mapping the indicator uses below, so the two never fight and there's no jump on
            // release.
            LaunchedEffect(thumbOffsetY) {
                if (!isThumbDragged) return@LaunchedEffect
                val thumbProportion = ((thumbOffsetY - thumbTopPadding) / trackHeightPx).coerceIn(0f, 1f)
                val targetRow = (thumbProportion * maxScrollRows).roundToInt().coerceIn(0, totalRows - 1)
                val targetItem = itemIndexForRow(targetRow).coerceIn(0, layoutInfo.totalItemsCount - 1)
                state.scrollToItem(targetItem)
                scrolled.tryEmit(Unit)
            }

            // When list scrolled: place the thumb at the scrolled-rows proportion.
            if (!isThumbDragged) {
                val proportion = (scrolledRows / maxScrollRows).coerceIn(0f, 1f)
                thumbOffsetY = trackHeightPx * proportion + thumbTopPadding
                if (stableScrollInProgress) scrolled.tryEmit(Unit)
            }

            // Thumb alpha
            val alpha = remember { Animatable(0f) }
            val isThumbVisible = alpha.value > 0f
            LaunchedEffect(scrolled, alpha) {
                scrolled
                    .sample(100)
                    .collectLatest {
                        if (thumbAllowed()) {
                            alpha.snapTo(1f)
                            delay(ScrollBarVisibilityDurationMillis)
                            alpha.animateTo(0f, animationSpec = ImmediateFadeOutAnimationSpec)
                        } else {
                            alpha.animateTo(0f, animationSpec = ImmediateFadeOutAnimationSpec)
                        }
                    }
            }

            Box(
                modifier = Modifier
                    .offset { IntOffset(0, thumbOffsetY.roundToInt()) }
                    .then(
                        // Draggable whenever the thumb is shown (even while a fling is settling: the
                        // first drag scrolls, which cancels the fling). Gating on !isScrollInProgress
                        // made the thumb ungrabbable until a fling fully stopped.
                        if (isThumbVisible) {
                            Modifier.draggable(
                                interactionSource = dragInteractionSource,
                                orientation = Orientation.Vertical,
                                state = rememberDraggableState { delta ->
                                    val newOffsetY = thumbOffsetY + delta
                                    thumbOffsetY = newOffsetY.coerceIn(
                                        thumbTopPadding,
                                        thumbTopPadding + trackHeightPx,
                                    )
                                },
                            )
                        } else {
                            Modifier
                        },
                    )
                    .then(
                        if (isThumbVisible && !isThumbDragged) {
                            Modifier.systemGestureExclusion()
                        } else {
                            Modifier
                        },
                    )
                    // Transparent touch target, wider than the visible thumb so it is easy to grab.
                    .height(ThumbLength)
                    .padding(end = endContentPadding)
                    .width(ThumbTouchThickness)
                    .alpha(alpha.value),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    Modifier
                        .height(ThumbLength)
                        .width(ThumbThickness)
                        .background(color = thumbColor, shape = ThumbShape),
                )
            }
        }.map { it.measure(scrollerConstraints) }
        val scrollerWidth = scrollerPlaceable.fastMaxBy { it.width }?.width ?: 0

        layout(contentWidth, contentHeight) {
            contentPlaceable.fastForEach {
                it.place(0, 0)
            }
            scrollerPlaceable.fastForEach {
                it.placeRelative(contentWidth - scrollerWidth, 0)
            }
        }
    }
}

private class MutableData<T>(var value: T)

private val ThumbLength = 48.dp
private val ThumbThickness = 12.dp
private val ThumbTouchThickness = 32.dp
private val ThumbShape = RoundedCornerShape(ThumbThickness / 2)
private const val ScrollBarVisibilityDurationMillis = 2000L
private val ImmediateFadeOutAnimationSpec = tween<Float>(
    durationMillis = ViewConfiguration.getScrollBarFadeDuration(),
)
