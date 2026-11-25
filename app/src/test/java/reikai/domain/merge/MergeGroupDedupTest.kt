package reikai.domain.merge

import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType

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

    /** A cover widget draws through this, and only redraws when it emits again. */
    @Test
    fun `a live list counts a series merged after it was first drawn once`() = runTest {
        val merges = TestMergeManagers(emptyMap(), mergingOn = true)

        val regrouped = flowOf(listOf(1L, 2L))
            .onePerSeries(merges.manga) { it }
            .onEach { merges.groups.value = mapOf(ContentType.MANGA to mapOf(1L to 9L, 2L to 9L)) }
            .first { it.size == 1 }

        regrouped shouldContainExactly listOf(1L)
    }
}
