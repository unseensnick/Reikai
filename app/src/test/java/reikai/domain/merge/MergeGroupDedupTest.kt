package reikai.domain.merge

import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test

/**
 * The one definition of "count a merged series once", shared by the stats screen and the tracker
 * refresh so they cannot disagree about it.
 */
class MergeGroupDedupTest {

    @Test
    fun `a group contributes one item, whichever members are present`() {
        val membership = mapOf(1L to 7L, 2L to 7L, 3L to 7L)

        listOf(1L, 2L, 3L).dedupeByMergeGroup(membership) { it } shouldContainExactly listOf(1L)
    }

    @Test
    fun `ungrouped items all survive`() {
        // An entry in no group is its own title, so nothing may collapse it into a neighbour.
        listOf(1L, 2L, 3L).dedupeByMergeGroup(mapOf(2L to 7L)) { it } shouldContainExactly listOf(1L, 2L, 3L)
    }

    @Test
    fun `two groups contribute one item each`() {
        val membership = mapOf(1L to 7L, 2L to 7L, 5L to 9L, 6L to 9L)

        listOf(1L, 2L, 5L, 6L).dedupeByMergeGroup(membership) { it } shouldContainExactly listOf(1L, 5L)
    }

    @Test
    fun `the first member in the given order is the one kept`() {
        // Order decides the survivor, which is why callers whose per-item values differ between members
        // hand in a deterministically ordered list.
        val membership = mapOf(1L to 7L, 2L to 7L)

        listOf(2L, 1L).dedupeByMergeGroup(membership) { it } shouldContainExactly listOf(2L)
    }
}
