package reikai.presentation.library

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.library.LibraryItem
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyGridState
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyGridState
import sh.calvin.reorderable.rememberReorderableLazyListState

/** A section whose tiles may be dragged into a Manual order: [onDrop] gets its whole new order and the tile moved. */
class ManualOrderDrag(val onDrop: (order: List<LibraryItem>, moved: LibraryItem) -> Unit)

/**
 * One lazy list's drag state over its sections: the order each shows while a drag is under way, and the
 * press bookkeeping its tiles share. A move lands only on a tile of the dragged one's own section, so a tile
 * never crosses a header. The order shown starts again from the section's rows whenever they change, which
 * is how a stored drop replaces the local one. Rules: docs/dev/subsystems/library.md.
 */
@Stable
class ManualOrder internal constructor(private val haptic: HapticFeedback) {

    private val orders = mutableStateMapOf<Any, Pair<List<LibraryItem>, List<LibraryItem>>>()
    private val owners = HashMap<Any, Any>()

    // The rows each section last drew with. A tile's gesture outlives the composition that started it, so the
    // rows it captured can be ones the library has since replaced.
    private val latest = HashMap<Any, List<LibraryItem>>()
    private var moved: LibraryItem? = null
    private var downAt = Offset.Zero
    private var travel = 0f

    /** Set once a press turns into a long press, so the tap its release also reports is dropped. */
    var swallowsTap = false
        private set

    /** [section]'s rows in the order shown now, registering each tile's key as belonging to [section]. */
    fun orderOf(section: Any, rows: List<LibraryItem>): List<LibraryItem> {
        rows.forEach { owners[manualOrderKey(section, it)] = section }
        latest[section] = rows
        return orders[section]?.takeIf { it.first === rows }?.second ?: rows
    }

    internal fun move(fromKey: Any, toKey: Any, rowsOf: (Any) -> List<LibraryItem>) {
        val section = owners[fromKey] ?: return
        if (owners[toKey] != section) return
        val rows = rowsOf(section)
        val current = orderOf(section, rows)
        val from = current.indexOfFirst { manualOrderKey(section, it) == fromKey }
        val to = current.indexOfFirst { manualOrderKey(section, it) == toKey }
        if (from < 0 || to < 0) return
        moved = current[from]
        orders[section] = rows to current.toMutableList().apply { add(to, removeAt(from)) }
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    internal fun pressed(at: Offset) {
        swallowsTap = false
        downAt = at
        travel = 0f
    }

    internal fun dragged(to: Offset) {
        travel = maxOf(travel, (to - downAt).getDistance())
    }

    internal fun longPressed() {
        swallowsTap = true
        moved = null
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    /**
     * A long press released near where it started selects, as a long press does elsewhere; one that travelled
     * further was a drag, even if nothing swapped (a release in the system gesture strip ends one that way).
     */
    internal fun released(section: Any, slop: Float, drag: ManualOrderDrag, select: () -> Unit) {
        val item = moved
        moved = null
        val rows = latest[section]
        when {
            travel < slop -> select()
            item != null && rows != null -> drag.onDrop(orderOf(section, rows), item)
        }
    }
}

/** The one section of a list that shows a single category, a page of the paged view. */
const val PAGE_SECTION = "page"

/** A tile's lazy key: its section, since an entry can sit in several, and its type, since ids can collide. */
fun manualOrderKey(section: Any, item: LibraryItem): String =
    "manual_${section}_${item.entryId.contentType}_${item.entryId.rawId}"

@Composable
fun rememberManualOrder(): ManualOrder {
    val haptic = LocalHapticFeedback.current
    return remember(haptic) { ManualOrder(haptic) }
}

@Composable
fun rememberManualOrderGrid(
    gridState: LazyGridState,
    manualOrder: ManualOrder,
    rowsOf: (Any) -> List<LibraryItem>,
): ReorderableLazyGridState = rememberReorderableLazyGridState(gridState) { from, to ->
    manualOrder.move(from.key, to.key, rowsOf)
}

@Composable
fun rememberManualOrderColumn(
    listState: LazyListState,
    manualOrder: ManualOrder,
    rowsOf: (Any) -> List<LibraryItem>,
): ReorderableLazyListState = rememberReorderableLazyListState(listState) { from, to ->
    manualOrder.move(from.key, to.key, rowsOf)
}

/**
 * [section]'s library tiles in a grid, draggable when [drag] is set. A dragged tile has no long-press of its
 * own: one there takes the long press and the drag never starts, so the select comes from [ManualOrder.released].
 */
fun LazyGridScope.libraryCells(
    section: Any,
    rows: List<LibraryItem>,
    contentType: Any,
    drag: ManualOrderDrag?,
    manualOrder: ManualOrder,
    reorder: ReorderableLazyGridState,
    onLongClick: (LibraryItem) -> Unit,
    cell: @Composable (item: LibraryItem, onLongClick: (() -> Unit)?, tapAllowed: () -> Boolean) -> Unit,
) {
    val shown = if (drag != null) manualOrder.orderOf(section, rows) else rows
    items(count = shown.size, key = { manualOrderKey(section, shown[it]) }, contentType = { contentType }) { index ->
        val item = shown[index]
        if (drag == null) {
            cell(item, { onLongClick(item) }, { true })
            return@items
        }
        ReorderableItem(reorder, key = manualOrderKey(section, item)) { isDragging ->
            ManualOrderTile(manualOrder, section, drag, isDragging, { onLongClick(item) }) {
                cell(item, null) { !manualOrder.swallowsTap }
            }
        }
    }
}

/** [libraryCells] for the list display mode, which draws in a column. */
fun LazyListScope.libraryRows(
    section: Any,
    rows: List<LibraryItem>,
    contentType: Any,
    drag: ManualOrderDrag?,
    manualOrder: ManualOrder,
    reorder: ReorderableLazyListState,
    onLongClick: (LibraryItem) -> Unit,
    cell: @Composable (item: LibraryItem, onLongClick: (() -> Unit)?, tapAllowed: () -> Boolean) -> Unit,
) {
    val shown = if (drag != null) manualOrder.orderOf(section, rows) else rows
    items(count = shown.size, key = { manualOrderKey(section, shown[it]) }, contentType = { contentType }) { index ->
        val item = shown[index]
        if (drag == null) {
            cell(item, { onLongClick(item) }, { true })
            return@items
        }
        ReorderableItem(reorder, key = manualOrderKey(section, item)) { isDragging ->
            ManualOrderTile(manualOrder, section, drag, isDragging, { onLongClick(item) }) {
                cell(item, null) { !manualOrder.swallowsTap }
            }
        }
    }
}

@Composable
private fun ReorderableCollectionItemScope.ManualOrderTile(
    manualOrder: ManualOrder,
    section: Any,
    drag: ManualOrderDrag,
    isDragging: Boolean,
    select: () -> Unit,
    content: @Composable () -> Unit,
) {
    val slop = LocalViewConfiguration.current.touchSlop
    val lift by animateDpAsState(if (isDragging) 8.dp else 0.dp, label = "manualOrderLift")
    // The tile follows the finger while dragged, so travel is measured on screen, not within the tile.
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    fun onScreen(local: Offset) = coordinates[0]?.takeIf { it.isAttached }?.localToRoot(local) ?: local
    Surface(
        shadowElevation = lift,
        color = Color.Transparent,
        modifier = Modifier
            .onGloballyPositioned { coordinates[0] = it }
            // Read before the tile's own handlers see the press, so the travel is known when the drag stops.
            .pointerInput(manualOrder) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    manualOrder.pressed(onScreen(down.position))
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.firstOrNull { it.id == down.id }
                            ?.let { manualOrder.dragged(onScreen(it.position)) }
                    } while (event.changes.any { it.pressed })
                }
            }
            .longPressDraggableHandle(
                onDragStarted = { manualOrder.longPressed() },
                onDragStopped = { manualOrder.released(section, slop, drag, select) },
            ),
    ) {
        content()
    }
}
