package reikai.presentation.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Drag-to-reorder over [items], which moves under the finger with no write per step: [onSettled] gets
 * the new order once, when the drag is released, and not at all for a drag that moved nothing. The
 * caller owns [items], so it decides when an external change resyncs them.
 */
@Composable
fun <T> rememberSettledReorder(
    items: SnapshotStateList<T>,
    listState: LazyListState,
    keyOf: (T) -> Any,
    contentPadding: PaddingValues = PaddingValues(),
    onSettled: (List<T>) -> Unit,
): ReorderableLazyListState {
    // The order the drag started from, so one that ends where it began settles nothing.
    var dragStart by remember { mutableStateOf<List<Any>?>(null) }
    val currentOnSettled by rememberUpdatedState(onSettled)
    val state = rememberReorderableLazyListState(listState, contentPadding) { from, to ->
        val fromIndex = items.indexOfFirst { keyOf(it) == from.key }
        val toIndex = items.indexOfFirst { keyOf(it) == to.key }
        if (fromIndex == -1 || toIndex == -1) return@rememberReorderableLazyListState
        if (dragStart == null) dragStart = items.map(keyOf)
        items.add(toIndex, items.removeAt(fromIndex))
    }
    LaunchedEffect(state.isAnyItemDragging) {
        val start = dragStart
        if (!state.isAnyItemDragging && start != null) {
            dragStart = null
            if (items.map(keyOf) != start) currentOnSettled(items.toList())
        }
    }
    return state
}
