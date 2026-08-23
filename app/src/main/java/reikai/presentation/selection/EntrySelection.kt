package reikai.presentation.selection

import kotlin.math.abs

/**
 * A multi-select in progress: what is picked, plus the row a range press measures from.
 *
 * The anchor is part of the state rather than a field beside it because the two are only ever correct
 * together. Five surfaces used to keep them apart and two of them drifted: one derived the anchor from
 * the selection's last element, so deselecting a row silently moved it, while the other kept its own.
 */
data class SelectionState<T>(
    val selection: Set<T> = emptySet(),
    val anchor: T? = null,
) {
    val isEmpty: Boolean get() = selection.isEmpty()

    operator fun contains(item: T): Boolean = item in selection

    val size: Int get() = selection.size
}

/**
 * The four selection verbs, shared by every multi-select surface and both content types. Pure, so the
 * caller owns the state and this owns the rules.
 *
 * A range runs from the last row you touched to the one you pressed, and only ever adds. It never
 * tracks a first/last window: a window can disagree with the selection it describes, which is how
 * long-pressing a selected chapter used to leave a hole in the next range.
 */
object EntrySelection {

    /** Flip one row, and make it the anchor the next range measures from. */
    fun <T> toggle(state: SelectionState<T>, item: T): SelectionState<T> {
        val selection = if (item in state.selection) state.selection - item else state.selection + item
        return SelectionState(selection, item.takeIf { selection.isNotEmpty() })
    }

    /**
     * Add every row between the anchor and [item] inclusive. With no usable anchor (nothing touched
     * yet, or the anchor has since been filtered out of [ordered]) this selects [item] alone, which is
     * what a long press with nothing to measure from means.
     */
    fun <T> range(state: SelectionState<T>, item: T, ordered: List<T>): SelectionState<T> {
        val to = ordered.indexOf(item)
        if (to < 0) return state
        val from = state.anchor?.let(ordered::indexOf) ?: -1
        val selection = if (from < 0) {
            state.selection + item
        } else {
            state.selection + ordered.subList(minOf(from, to), maxOf(from, to) + 1)
        }
        return SelectionState(selection, item)
    }

    /**
     * A long press where pressing a selected row is also how you drop it: extend to [item], unless it
     * is already selected, in which case remove it. The chapter lists work this way; surfaces whose
     * long press only ever extends call [range] directly.
     */
    fun <T> rangeOrToggle(state: SelectionState<T>, item: T, ordered: List<T>): SelectionState<T> =
        if (item in state.selection) toggle(state, item) else range(state, item, ordered)

    /**
     * A long press on a collapsed group, which stands for a contiguous block of rows rather than one.
     * Extends to whichever end of [block] is farther from the anchor, so the group and everything
     * between it and the anchor come in together. A block that is already fully selected is dropped
     * instead, matching [rangeOrToggle] on a single row.
     */
    fun <T> rangeOrToggleBlock(state: SelectionState<T>, block: List<T>, ordered: List<T>): SelectionState<T> {
        if (block.isEmpty()) return state
        if (block.all { it in state.selection }) {
            return SelectionState(state.selection - block.toSet(), block.last())
        }
        val anchorAt = state.anchor?.let(ordered::indexOf) ?: -1
        val firstAt = ordered.indexOf(block.first())
        val lastAt = ordered.indexOf(block.last())
        if (anchorAt < 0 || firstAt < 0 || lastAt < 0) {
            return SelectionState(state.selection + block, block.last())
        }
        val far = if (abs(anchorAt - firstAt) >= abs(anchorAt - lastAt)) block.first() else block.last()
        // Union the block as well: an anchor sitting inside it would otherwise leave part unselected.
        val ranged = range(state, far, ordered)
        return SelectionState(ranged.selection + block, ranged.anchor)
    }

    /** Add every visible row. Both bulk verbs drop the anchor: after one, nothing on screen
     *  corresponds to a row the user pressed, so a range measured from it would be arbitrary. */
    fun <T> selectAll(state: SelectionState<T>, visible: List<T>): SelectionState<T> =
        SelectionState(state.selection + visible, null)

    /**
     * Flip the visible rows, and leave anything selected outside [visible] alone. That matters where
     * the visible list is a slice: inverting one library category must not silently drop what is
     * picked in the others.
     */
    fun <T> invert(state: SelectionState<T>, visible: List<T>): SelectionState<T> {
        val (toRemove, toAdd) = visible.partition { it in state.selection }
        return SelectionState(state.selection - toRemove.toSet() + toAdd, null)
    }

    fun <T> clear(): SelectionState<T> = SelectionState()

    /**
     * Drop everything the list no longer contains, for when a filter or a refresh rebuilds it. The
     * anchor is pruned with the selection: a range measured from a row that is gone would silently
     * fall back to selecting one row, which reads as a broken long press rather than a stale anchor.
     */
    fun <T> retain(state: SelectionState<T>, visible: List<T>): SelectionState<T> {
        val keep = visible.toSet()
        return SelectionState(
            selection = state.selection.filterTo(LinkedHashSet()) { it in keep },
            anchor = state.anchor?.takeIf { it in keep },
        )
    }
}
