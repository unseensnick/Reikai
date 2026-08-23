package reikai.presentation.selection

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The selection rules every multi-select surface shares. Pinned here once, because the seven surfaces
 * that used to hand-roll them disagreed with each other in ways nothing caught.
 */
class EntrySelectionTest {

    private val rows = (0L..12L).toList()

    private fun state(vararg selected: Long, anchor: Long? = null) =
        SelectionState(selected.toSet(), anchor)

    @Test
    fun `toggling an unselected row adds it and anchors there`() {
        EntrySelection.toggle(state(), 3L) shouldBe state(3L, anchor = 3L)
    }

    @Test
    fun `toggling a selected row removes it`() {
        EntrySelection.toggle(state(3L, anchor = 3L), 3L).selection shouldBe emptySet()
    }

    @Test
    fun `emptying the selection drops the anchor, so the next range has nothing stale to measure from`() {
        EntrySelection.toggle(state(3L, anchor = 3L), 3L).anchor shouldBe null
    }

    @Test
    fun `a range with no anchor selects only the pressed row`() {
        EntrySelection.range(state(), 4L, rows) shouldBe state(4L, anchor = 4L)
    }

    @Test
    fun `a range runs forward from the anchor, inclusive at both ends`() {
        EntrySelection.range(state(2L, anchor = 2L), 5L, rows).selection shouldBe setOf(2L, 3L, 4L, 5L)
    }

    @Test
    fun `a range runs backward from the anchor just as far`() {
        EntrySelection.range(state(5L, anchor = 5L), 2L, rows).selection shouldBe setOf(2L, 3L, 4L, 5L)
    }

    @Test
    fun `a range moves the anchor to the row just pressed`() {
        EntrySelection.range(state(2L, anchor = 2L), 5L, rows).anchor shouldBe 5L
    }

    @Test
    fun `a range only ever adds, so rows outside it survive`() {
        EntrySelection.range(state(9L, 2L, anchor = 2L), 4L, rows).selection shouldBe setOf(2L, 3L, 4L, 9L)
    }

    @Test
    fun `an anchor filtered out of the list degrades to selecting the pressed row alone`() {
        EntrySelection.range(state(7L, anchor = 7L), 4L, listOf(1L, 2L, 3L, 4L)).selection shouldBe setOf(7L, 4L)
    }

    @Test
    fun `pressing a row that is not in the list changes nothing`() {
        val before = state(1L, anchor = 1L)
        EntrySelection.range(before, 99L, rows) shouldBe before
    }

    /**
     * The bug this kernel makes unrepresentable. Select 2 to 5, drop 5, then range to 7. The old
     * two-slot window still claimed 5 as its top, so it filled only 6 and left 5 unselected inside a
     * range the user had just asked for. Measuring from the last touched row cannot go stale that
     * way: 5 was the last row touched, so the range runs 5 to 7 and comes out contiguous.
     */
    @Test
    fun `dropping a row then extending past it leaves the range contiguous`() {
        var s = EntrySelection.range(state(), 2L, rows)
        s = EntrySelection.range(s, 5L, rows)
        s = EntrySelection.toggle(s, 5L)
        s = EntrySelection.range(s, 7L, rows)
        s.selection shouldBe setOf(2L, 3L, 4L, 5L, 6L, 7L)
    }

    @Test
    fun `dropping a row leaves it as the anchor, since it is still the last row touched`() {
        EntrySelection.toggle(state(2L, 5L, anchor = 2L), 5L).anchor shouldBe 5L
    }

    /** Range 2 to 10, then tap 5 to drop it. The deselection sticks: 5 is the only gap. */
    @Test
    fun `deselecting one row out of a range removes exactly that row`() {
        var s = EntrySelection.range(state(), 2L, rows)
        s = EntrySelection.range(s, 10L, rows)
        s = EntrySelection.toggle(s, 5L)
        s.selection shouldBe setOf(2L, 3L, 4L, 6L, 7L, 8L, 9L, 10L)
    }

    /**
     * Follow-on from the case above, and the one place "measure from the last row you touched" is
     * arguable: the tap that dropped 5 also anchored there, so ranging on to 12 runs 5 to 12 and
     * takes 5 back. Every existing surface behaves this way today.
     */
    @Test
    fun `ranging on after a deselect measures from the dropped row and takes it back`() {
        var s = EntrySelection.range(state(), 2L, rows)
        s = EntrySelection.range(s, 10L, rows)
        s = EntrySelection.toggle(s, 5L)
        s = EntrySelection.range(s, 12L, rows)
        s.selection shouldBe setOf(2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L)
    }

    @Test
    fun `select all drops the anchor, since no row on screen is the one you pressed`() {
        EntrySelection.selectAll(state(1L, anchor = 1L), rows).anchor shouldBe null
    }

    @Test
    fun `inverting nothing selects every visible row`() {
        EntrySelection.invert(state(), listOf(1L, 2L)).selection shouldBe setOf(1L, 2L)
    }

    @Test
    fun `invert leaves selected rows outside the visible slice alone`() {
        EntrySelection.invert(state(1L, 9L), listOf(1L, 2L)).selection shouldBe setOf(2L, 9L)
    }

    @Test
    fun `select all adds every visible row`() {
        EntrySelection.selectAll(state(), listOf(1L, 2L)).selection shouldBe setOf(1L, 2L)
    }

    @Test
    fun `invert keeps exactly the visible rows that were not selected`() {
        EntrySelection.invert(state(1L, 3L), listOf(1L, 2L, 3L, 4L)).selection shouldBe setOf(2L, 4L)
    }

    @Test
    fun `invert drops the anchor, which may no longer be selected`() {
        EntrySelection.invert(state(1L, anchor = 1L), rows).anchor shouldBe null
    }

    /** Device-verified on both content types: a tap re-anchors, so the range runs from it, not from
     *  where the first long press started. */
    @Test
    fun `a tap between two long presses becomes the point the next range measures from`() {
        var s = EntrySelection.range(state(), 2L, rows)
        s = EntrySelection.toggle(s, 10L)
        s = EntrySelection.range(s, 8L, rows)
        s.selection shouldBe setOf(2L, 8L, 9L, 10L)
    }

    @Test
    fun `a long press on an unselected row extends the range to it`() {
        EntrySelection.rangeOrToggle(state(2L, anchor = 2L), 5L, rows).selection shouldBe setOf(2L, 3L, 4L, 5L)
    }

    @Test
    fun `a long press on a selected row drops it instead of extending`() {
        EntrySelection.rangeOrToggle(state(2L, 3L, 4L, anchor = 2L), 3L, rows).selection shouldBe setOf(2L, 4L)
    }

    @Test
    fun `dropping by long press still re-anchors, so extending on stays contiguous`() {
        var s = EntrySelection.rangeOrToggle(state(), 2L, rows)
        s = EntrySelection.rangeOrToggle(s, 5L, rows)
        s = EntrySelection.rangeOrToggle(s, 5L, rows)
        s = EntrySelection.rangeOrToggle(s, 7L, rows)
        s.selection shouldBe setOf(2L, 3L, 4L, 5L, 6L, 7L)
    }

    @Test
    fun `a long press on a group below the anchor sweeps up to the group's far edge`() {
        EntrySelection.rangeOrToggleBlock(state(1L, anchor = 1L), listOf(5L, 6L, 7L), rows)
            .selection shouldBe setOf(1L, 2L, 3L, 4L, 5L, 6L, 7L)
    }

    @Test
    fun `a long press on a group above the anchor sweeps down to its far edge`() {
        EntrySelection.rangeOrToggleBlock(state(9L, anchor = 9L), listOf(5L, 6L, 7L), rows)
            .selection shouldBe setOf(5L, 6L, 7L, 8L, 9L)
    }

    @Test
    fun `a long press on a group with no anchor selects just that group`() {
        EntrySelection.rangeOrToggleBlock(state(), listOf(5L, 6L, 7L), rows).selection shouldBe setOf(5L, 6L, 7L)
    }

    @Test
    fun `a long press on a fully selected group drops it`() {
        EntrySelection.rangeOrToggleBlock(state(5L, 6L, 7L, anchor = 5L), listOf(5L, 6L, 7L), rows)
            .selection shouldBe emptySet()
    }

    @Test
    fun `a partly selected group is completed rather than dropped`() {
        EntrySelection.rangeOrToggleBlock(state(6L, anchor = 6L), listOf(5L, 6L, 7L), rows)
            .selection shouldBe setOf(5L, 6L, 7L)
    }

    @Test
    fun `a group press anchors on the group, so the next range measures from it`() {
        EntrySelection.rangeOrToggleBlock(state(1L, anchor = 1L), listOf(5L, 6L, 7L), rows).anchor shouldBe 7L
    }

    @Test
    fun `retain drops selected rows the list no longer contains`() {
        EntrySelection.retain(state(1L, 5L, 9L), listOf(1L, 2L, 5L)).selection shouldBe setOf(1L, 5L)
    }

    @Test
    fun `retain drops an anchor that is gone, so a later range is not measured from nothing`() {
        EntrySelection.retain(state(1L, anchor = 9L), listOf(1L, 2L)).anchor shouldBe null
    }

    @Test
    fun `retain keeps an anchor that survived`() {
        EntrySelection.retain(state(1L, anchor = 1L), listOf(1L, 2L)).anchor shouldBe 1L
    }

    @Test
    fun `select all leaves a row selected that is no longer visible`() {
        EntrySelection.selectAll(state(9L, anchor = 9L), listOf(1L, 2L)).selection shouldBe setOf(1L, 2L, 9L)
    }
}
