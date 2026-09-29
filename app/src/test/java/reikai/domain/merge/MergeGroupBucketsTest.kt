package reikai.domain.merge

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The one bucketing rule the library collapses and the Updates series grouping share: which entries
 * render as one merged row. The collapses keep their own per-type merge of a bucket's counts.
 */
class MergeGroupBucketsTest {

    private fun List<Long>.buckets(membership: Map<Long, Long>, mergingEnabled: Boolean = true) =
        bucketByMergeGroup(membership, mergingEnabled) { it }.map { it.groupId to it.members }

    @Test
    fun `merging off keeps every item alone`() {
        listOf(1L, 2L).buckets(mapOf(1L to 7L, 2L to 7L), mergingEnabled = false) shouldContainExactly
            listOf(null to listOf(1L), null to listOf(2L))
    }

    @Test
    fun `ungrouped items stay alone`() {
        listOf(1L, 2L).buckets(emptyMap()) shouldContainExactly listOf(null to listOf(1L), null to listOf(2L))
    }

    @Test
    fun `an ungrouped item between two group members stays alone and the group keeps its first place`() {
        listOf(1L, 5L, 2L).buckets(mapOf(1L to 7L, 2L to 7L)) shouldContainExactly
            listOf(7L to listOf(1L, 2L), null to listOf(5L))
    }

    @Test
    fun `a group with one member present is not merged`() {
        listOf(1L, 5L).buckets(mapOf(1L to 7L)).first().first shouldBe null
    }

    @Test
    fun `a standalone entry whose id equals a group id stays apart`() {
        listOf(1L, 7L).buckets(mapOf(1L to 7L)) shouldContainExactly listOf(null to listOf(1L), null to listOf(7L))
    }

    @Test
    fun `repeated standalone items of one entry share a bucket`() {
        listOf(5L, 5L).buckets(emptyMap()) shouldContainExactly listOf(null to listOf(5L, 5L))
    }
}
