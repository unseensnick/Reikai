package yokai.presentation.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.math.absoluteValue
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Snapping wheel picker, trimmed port of Mihon's WheelPicker (no tap-to-type manual input, which
 * depends on soft-keyboard utilities this fork doesn't have). Shows [ROW_COUNT] rows with the
 * centered item highlighted; scrolling snaps and reports the selected index. Used by the tracking
 * chapter / score editors.
 */
@Composable
fun WheelNumberPicker(
    items: List<Int>,
    startIndex: Int,
    modifier: Modifier = Modifier,
    size: DpSize = DpSize(160.dp, 144.dp),
    onSelectionChanged: (index: Int) -> Unit,
) {
    WheelPicker(items = items, startIndex = startIndex, modifier = modifier, size = size, onSelectionChanged = onSelectionChanged) {
        Text(text = "$it", style = MaterialTheme.typography.titleMedium, maxLines = 1)
    }
}

@Composable
fun WheelTextPicker(
    items: List<String>,
    startIndex: Int,
    modifier: Modifier = Modifier,
    size: DpSize = DpSize(220.dp, 144.dp),
    onSelectionChanged: (index: Int) -> Unit,
) {
    WheelPicker(items = items, startIndex = startIndex, modifier = modifier, size = size, onSelectionChanged = onSelectionChanged) {
        Text(text = it, style = MaterialTheme.typography.titleMedium, maxLines = 1)
    }
}

@Composable
private fun <T> WheelPicker(
    items: List<T>,
    startIndex: Int,
    modifier: Modifier = Modifier,
    size: DpSize = DpSize(160.dp, 144.dp),
    onSelectionChanged: (index: Int) -> Unit,
    itemContent: @Composable LazyItemScope.(item: T) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val lazyListState = rememberLazyListState(startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)))

    LaunchedEffect(lazyListState) {
        snapshotFlow { lazyListState.firstVisibleItemScrollOffset }
            .map { calculateSnappedItemIndex(lazyListState) }
            .distinctUntilChanged()
            .collectLatest { index ->
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onSelectionChanged(index)
            }
    }

    Box(
        modifier = modifier.height(size.height).width(size.width),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(size.width, size.height / ROW_COUNT),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            content = {},
        )
        LazyColumn(
            state = lazyListState,
            contentPadding = PaddingValues(vertical = size.height / ROW_COUNT * ((ROW_COUNT - 1) / 2)),
            flingBehavior = rememberSnapFlingBehavior(lazyListState = lazyListState),
        ) {
            itemsIndexed(items) { index, item ->
                Box(
                    modifier = Modifier
                        .height(size.height / ROW_COUNT)
                        .width(size.width)
                        .alpha(calculateAnimatedAlpha(lazyListState, index)),
                    contentAlignment = Alignment.Center,
                ) {
                    itemContent(item)
                }
            }
        }
    }
}

private fun LazyListState.snapOffsetForItem(itemInfo: LazyListItemInfo): Int {
    val endScrollOffset = layoutInfo.let { it.viewportEndOffset - it.afterContentPadding }
    return (endScrollOffset - itemInfo.size) / 2
}

private fun LazyListState.distanceToSnapForIndex(index: Int): Int {
    val itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return 0
    return itemInfo.offset - snapOffsetForItem(itemInfo)
}

private fun calculateAnimatedAlpha(lazyListState: LazyListState, index: Int): Float {
    val distanceToIndexSnap = lazyListState.distanceToSnapForIndex(index).absoluteValue
    val viewPortHeight = lazyListState.layoutInfo.viewportSize.height.toFloat()
    val singleViewPortHeight = viewPortHeight / ROW_COUNT
    if (singleViewPortHeight <= 0f) return 0.2f
    return if (distanceToIndexSnap in 0..singleViewPortHeight.toInt()) {
        1.2f - (distanceToIndexSnap / singleViewPortHeight)
    } else {
        0.2f
    }
}

private fun calculateSnappedItemIndex(lazyListState: LazyListState): Int {
    return lazyListState.layoutInfo.visibleItemsInfo
        .maxByOrNull { calculateAnimatedAlpha(lazyListState, it.index) }
        ?.index
        ?: lazyListState.firstVisibleItemIndex
}

private const val ROW_COUNT = 3
