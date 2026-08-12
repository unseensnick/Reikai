package reikai.domain.merge

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The number the delete dialog offers to widen a removal by. It counted every selected row, so a
 * selection of three rows with one merged pair among them offered to remove "all 4 grouped sources"
 * while the toolbar said 3 and the option could only ever reach 2 of them.
 */
class GroupedSourceIdsTest {

    /** One row's members, as each content type's own state answers it: itself when unmerged. */
    private fun members(vararg groups: Pair<Long, List<Long>>): (Long) -> List<Long> {
        val byId = groups.toMap()
        return { id -> byId[id] ?: listOf(id) }
    }

    @Test
    fun `an unmerged selection has no grouped sources`() {
        groupedSourceIdsOf(listOf(1L, 2L), members()) shouldBe emptySet()
    }

    @Test
    fun `a merged row contributes every source behind it`() {
        val merged = members(1L to listOf(1L, 5L))

        groupedSourceIdsOf(listOf(1L), merged) shouldContainExactlyInAnyOrder listOf(1L, 5L)
    }

    /**
     * The case that was wrong: the unmerged rows are removed identically whether the option is on or
     * off, so counting them states a number nothing on screen can explain.
     */
    @Test
    fun `unmerged rows beside a merged one are not counted`() {
        val merged = members(1L to listOf(1L, 5L))

        groupedSourceIdsOf(listOf(1L, 2L, 3L), merged) shouldContainExactlyInAnyOrder listOf(1L, 5L)
    }

    @Test
    fun `two merged rows contribute both their groups`() {
        val merged = members(1L to listOf(1L, 5L), 2L to listOf(2L, 6L, 7L))

        groupedSourceIdsOf(listOf(1L, 2L), merged).size shouldBe 5
    }

    /**
     * Selecting several sources of one group is possible only where a list does not collapse them, and
     * the number is a count of entries either way, so the shared member has to land once.
     */
    @Test
    fun `a source counted twice lands once`() {
        val merged = members(1L to listOf(1L, 5L), 5L to listOf(1L, 5L))

        groupedSourceIdsOf(listOf(1L, 5L), merged) shouldContainExactlyInAnyOrder listOf(1L, 5L)
    }

    @Test
    fun `a row whose state has gone contributes nothing`() {
        val absent: (Long) -> List<Long> = { emptyList() }

        groupedSourceIdsOf(listOf(1L), absent) shouldBe emptySet()
    }
}
