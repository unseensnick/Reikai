package reikai.presentation.selection

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

private typealias Verb = (SelectionState<Long>) -> SelectionState<Long>

/**
 * The selection rules every multi-select surface shares. Pinned here once, because the seven surfaces
 * that used to hand-roll them disagreed with each other in ways nothing caught. Each table row checks
 * the whole state a verb leaves, the selection and the anchor the next range measures from.
 */
class EntrySelectionTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("tapsAndRanges")
    fun `a tap toggles and a range fills from the anchor`(
        name: String,
        before: SelectionState<Long>,
        verb: Verb,
        expected: SelectionState<Long>,
    ) {
        verb(before) shouldBe expected
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("longPresses")
    fun `a long press extends to a row or group, or drops one already selected`(
        name: String,
        before: SelectionState<Long>,
        verb: Verb,
        expected: SelectionState<Long>,
    ) {
        verb(before) shouldBe expected
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bulkVerbs")
    fun `a bulk verb acts on the visible rows and drops the anchor`(
        name: String,
        before: SelectionState<Long>,
        verb: Verb,
        expected: SelectionState<Long>,
    ) {
        verb(before) shouldBe expected
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("listChanges")
    fun `a list change keeps only what is still there`(
        name: String,
        before: SelectionState<Long>,
        verb: Verb,
        expected: SelectionState<Long>,
    ) {
        verb(before) shouldBe expected
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
    fun `dropping by long press still re-anchors, so extending on stays contiguous`() {
        var s = EntrySelection.rangeOrToggle(state(), 2L, rows)
        s = EntrySelection.rangeOrToggle(s, 5L, rows)
        s = EntrySelection.rangeOrToggle(s, 5L, rows)
        s = EntrySelection.rangeOrToggle(s, 7L, rows)
        s.selection shouldBe setOf(2L, 3L, 4L, 5L, 6L, 7L)
    }

    @Test
    fun `the selection a details screen counts and acts on is only its visible rows`() {
        EntrySelection.selectedAmong(setOf(1L, 5L, 9L), listOf(1L, 2L, 5L)) shouldBe setOf(1L, 5L)
    }

    companion object {
        private val rows = (0L..12L).toList()
        private val group = listOf(5L, 6L, 7L)

        private fun state(vararg selected: Long, anchor: Long? = null) = SelectionState(selected.toSet(), anchor)

        private fun case(name: String, before: SelectionState<Long>, verb: Verb, expected: SelectionState<Long>) =
            Arguments.of(name, before, verb, expected)

        @JvmStatic
        fun tapsAndRanges() = listOf(
            case(
                "toggling an unselected row adds it and anchors there",
                state(),
                { EntrySelection.toggle(it, 3L) },
                state(3L, anchor = 3L),
            ),
            case(
                "emptying the selection drops the anchor, so the next range has nothing stale to measure from",
                state(3L, anchor = 3L),
                { EntrySelection.toggle(it, 3L) },
                state(),
            ),
            case(
                "dropping a row leaves it as the anchor, since it is still the last row touched",
                state(2L, 5L, anchor = 2L),
                { EntrySelection.toggle(it, 5L) },
                state(2L, anchor = 5L),
            ),
            case(
                "a range with no anchor selects only the pressed row",
                state(),
                { EntrySelection.range(it, 4L, rows) },
                state(4L, anchor = 4L),
            ),
            case(
                "a range runs forward from the anchor, inclusive at both ends, and anchors on the pressed row",
                state(2L, anchor = 2L),
                { EntrySelection.range(it, 5L, rows) },
                state(2L, 3L, 4L, 5L, anchor = 5L),
            ),
            case(
                "a range runs backward from the anchor just as far",
                state(5L, anchor = 5L),
                { EntrySelection.range(it, 2L, rows) },
                state(2L, 3L, 4L, 5L, anchor = 2L),
            ),
            case(
                "a range only ever adds, so rows outside it survive",
                state(9L, 2L, anchor = 2L),
                { EntrySelection.range(it, 4L, rows) },
                state(2L, 3L, 4L, 9L, anchor = 4L),
            ),
            case(
                "an anchor filtered out of the list degrades to selecting the pressed row alone",
                state(7L, anchor = 7L),
                { EntrySelection.range(it, 4L, listOf(1L, 2L, 3L, 4L)) },
                state(7L, 4L, anchor = 4L),
            ),
            case(
                "pressing a row that is not in the list changes nothing",
                state(1L, anchor = 1L),
                { EntrySelection.range(it, 99L, rows) },
                state(1L, anchor = 1L),
            ),
        )

        @JvmStatic
        fun longPresses() = listOf(
            case(
                "a long press on an unselected row extends the range to it",
                state(2L, anchor = 2L),
                { EntrySelection.rangeOrToggle(it, 5L, rows) },
                state(2L, 3L, 4L, 5L, anchor = 5L),
            ),
            case(
                "a long press on a selected row drops it instead of extending",
                state(2L, 3L, 4L, anchor = 2L),
                { EntrySelection.rangeOrToggle(it, 3L, rows) },
                state(2L, 4L, anchor = 3L),
            ),
            case(
                "a long press on a group below the anchor sweeps to the group's far edge and anchors there",
                state(1L, anchor = 1L),
                { EntrySelection.rangeOrToggleBlock(it, group, rows) },
                state(1L, 2L, 3L, 4L, 5L, 6L, 7L, anchor = 7L),
            ),
            case(
                "a long press on a group above the anchor sweeps down to its far edge",
                state(9L, anchor = 9L),
                { EntrySelection.rangeOrToggleBlock(it, group, rows) },
                state(5L, 6L, 7L, 8L, 9L, anchor = 5L),
            ),
            case(
                "a long press on a group with no anchor selects just that group",
                state(),
                { EntrySelection.rangeOrToggleBlock(it, group, rows) },
                state(5L, 6L, 7L, anchor = 7L),
            ),
            case(
                "a long press on a fully selected group drops it",
                state(5L, 6L, 7L, anchor = 5L),
                { EntrySelection.rangeOrToggleBlock(it, group, rows) },
                state(anchor = 7L),
            ),
            case(
                "a partly selected group is completed rather than dropped",
                state(6L, anchor = 6L),
                { EntrySelection.rangeOrToggleBlock(it, group, rows) },
                state(5L, 6L, 7L, anchor = 5L),
            ),
        )

        @JvmStatic
        fun bulkVerbs() = listOf(
            case(
                "select all drops the anchor, since no row on screen is the one you pressed",
                state(1L, anchor = 1L),
                { EntrySelection.selectAll(it, rows) },
                state(*rows.toLongArray()),
            ),
            case(
                "select all adds every visible row",
                state(),
                { EntrySelection.selectAll(it, listOf(1L, 2L)) },
                state(1L, 2L),
            ),
            case(
                "select all leaves a row selected that is no longer visible",
                state(9L, anchor = 9L),
                { EntrySelection.selectAll(it, listOf(1L, 2L)) },
                state(1L, 2L, 9L),
            ),
            case(
                "inverting nothing selects every visible row",
                state(),
                { EntrySelection.invert(it, listOf(1L, 2L)) },
                state(1L, 2L),
            ),
            case(
                "invert leaves selected rows outside the visible slice alone",
                state(1L, 9L),
                { EntrySelection.invert(it, listOf(1L, 2L)) },
                state(2L, 9L),
            ),
            case(
                "invert keeps exactly the visible rows that were not selected",
                state(1L, 3L),
                { EntrySelection.invert(it, listOf(1L, 2L, 3L, 4L)) },
                state(2L, 4L),
            ),
            case(
                "invert drops the anchor, which may no longer be selected",
                state(1L, anchor = 1L),
                { EntrySelection.invert(it, rows) },
                state(*(rows - 1L).toLongArray()),
            ),
            case(
                "a block toggle on a partly selected block selects the rest",
                state(6L, 9L, anchor = 6L),
                { EntrySelection.toggleBlock(it, group) },
                state(5L, 6L, 7L, 9L),
            ),
            // Library categories and Recents groups used to leave three different anchors behind here.
            case(
                "a block toggle on a fully selected block drops only that block, and the anchor",
                state(5L, 6L, 7L, 9L, anchor = 9L),
                { EntrySelection.toggleBlock(it, group) },
                state(9L),
            ),
            case(
                "a block toggle on an empty block selects nothing",
                state(1L, anchor = 1L),
                { EntrySelection.toggleBlock(it, emptyList()) },
                state(1L),
            ),
        )

        @JvmStatic
        fun listChanges() = listOf(
            case(
                "retain drops selected rows the list no longer contains",
                state(1L, 5L, 9L),
                { EntrySelection.retain(it, listOf(1L, 2L, 5L)) },
                state(1L, 5L),
            ),
            case(
                "retain drops an anchor that is gone, so a later range is not measured from nothing",
                state(1L, anchor = 9L),
                { EntrySelection.retain(it, listOf(1L, 2L)) },
                state(1L),
            ),
            case(
                "retain keeps an anchor that survived",
                state(1L, anchor = 1L),
                { EntrySelection.retain(it, listOf(1L, 2L)) },
                state(1L, anchor = 1L),
            ),
            case(
                "flipping a chip drops the selection",
                state(1L, anchor = 1L),
                { EntrySelection.afterChipFlip(it, from = "Manga", to = "Novels") },
                state(),
            ),
            case(
                "re-selecting the chip shown keeps the selection",
                state(1L, anchor = 1L),
                { EntrySelection.afterChipFlip(it, from = "Manga", to = "Manga") },
                state(1L, anchor = 1L),
            ),
        )
    }
}
