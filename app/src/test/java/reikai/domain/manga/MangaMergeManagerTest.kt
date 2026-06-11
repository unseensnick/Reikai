package reikai.domain.manga

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MangaMergeManagerTest {

    @Test
    fun `unmergeKey is normalized min,max regardless of argument order`() {
        MangaMergeManager.unmergeKey(5, 2) shouldBe "2,5"
        MangaMergeManager.unmergeKey(2, 5) shouldBe "2,5"
    }

    @Test
    fun `computeGroupIds returns only the target when nothing matches`() {
        MangaMergeManager.computeGroupIds(1L, emptySet(), emptySet(), emptySet())
            .toList() shouldContainExactly listOf(1L)
    }

    @Test
    fun `computeGroupIds unions the manual merge entry containing the target`() {
        val ids = MangaMergeManager.computeGroupIds(
            targetId = 1L,
            merges = setOf("1,5,9", "3,7"),
            sameTitleIds = emptySet(),
            unmerges = emptySet(),
        )
        ids.toList() shouldContainExactly listOf(1L, 5L, 9L)
    }

    @Test
    fun `computeGroupIds adds same-title ids`() {
        val ids = MangaMergeManager.computeGroupIds(
            targetId = 1L,
            merges = emptySet(),
            sameTitleIds = setOf(2L, 3L),
            unmerges = emptySet(),
        )
        ids.toList() shouldContainExactly listOf(1L, 2L, 3L)
    }

    @Test
    fun `computeGroupIds drops an id explicitly unmerged from the target`() {
        val ids = MangaMergeManager.computeGroupIds(
            targetId = 1L,
            merges = setOf("1,5,9"),
            sameTitleIds = emptySet(),
            unmerges = setOf("1,9"),
        )
        ids.toList() shouldContainExactly listOf(1L, 5L)
    }

    @Test
    fun `computeSplit returns null when there are no targets`() {
        MangaMergeManager.computeSplit(longArrayOf(1L, 2L), emptyList(), setOf("1,2"), emptySet())
            .shouldBeNull()
    }

    @Test
    fun `computeSplit returns null when nothing would survive`() {
        MangaMergeManager.computeSplit(longArrayOf(1L, 2L), listOf(1L, 2L), setOf("1,2"), emptySet())
            .shouldBeNull()
    }

    @Test
    fun `computeSplit removes a sibling, re-merges survivors, and records unmerge pairs`() {
        val result = MangaMergeManager.computeSplit(
            relatedMangaIds = longArrayOf(1L, 2L, 3L),
            targetIds = listOf(3L),
            merges = setOf("1,2,3"),
            unmerges = emptySet(),
        )
        result.shouldNotBeNull()
        result.survivors.toList() shouldContainExactly listOf(1L, 2L)
        result.newMerges shouldContainExactly setOf("1,2")
        // 3 is now unmerged from both 1 and 2.
        result.newUnmerges shouldContainExactlyInAnyOrder setOf("1,3", "2,3")
    }

    @Test
    fun `computeSplit drops the merge entry but keeps a lone survivor`() {
        val result = MangaMergeManager.computeSplit(
            relatedMangaIds = longArrayOf(1L, 2L),
            targetIds = listOf(2L),
            merges = setOf("1,2"),
            unmerges = emptySet(),
        )
        result.shouldNotBeNull()
        result.survivors.toList() shouldContainExactly listOf(1L)
        // One survivor is not a group, so no merge entry remains.
        result.newMerges shouldContainExactly emptySet()
        result.newUnmerges shouldContainExactly setOf("1,2")
    }

    @Test
    fun `computeHealing leaves entries that do not mention the target untouched`() {
        val result = MangaMergeManager.computeHealing(
            targetId = 1L,
            merges = setOf("3,4"),
            unmerges = emptySet(),
            trackerKeysByMangaId = emptyMap(),
        )
        result.dropped shouldBe 0
        result.newMerges shouldContainExactly setOf("3,4")
    }

    @Test
    fun `computeHealing keeps a sibling when either side is untracked`() {
        val result = MangaMergeManager.computeHealing(
            targetId = 1L,
            merges = setOf("1,2"),
            unmerges = emptySet(),
            trackerKeysByMangaId = mapOf(1L to setOf(10L to 100L)), // 2 untracked
        )
        result.dropped shouldBe 0
        result.newMerges shouldContainExactly setOf("1,2")
    }

    @Test
    fun `computeHealing keeps a sibling that shares a tracker key`() {
        val result = MangaMergeManager.computeHealing(
            targetId = 1L,
            merges = setOf("1,2"),
            unmerges = emptySet(),
            trackerKeysByMangaId = mapOf(
                1L to setOf(10L to 100L),
                2L to setOf(10L to 100L),
            ),
        )
        result.dropped shouldBe 0
        result.newMerges shouldContainExactly setOf("1,2")
    }

    @Test
    fun `computeHealing drops a sibling when both are tracked with no overlap`() {
        val result = MangaMergeManager.computeHealing(
            targetId = 1L,
            merges = setOf("1,2"),
            unmerges = emptySet(),
            trackerKeysByMangaId = mapOf(
                1L to setOf(10L to 100L),
                2L to setOf(10L to 999L), // same tracker, different remote id
            ),
        )
        result.dropped shouldBe 1
        // Only the target survives, so no merge entry remains, and the pair is unmerged.
        result.newMerges shouldContainExactly emptySet()
        result.newUnmerges shouldContainExactly setOf("1,2")
    }

    @Test
    fun `computeHealing keeps verified siblings while dropping a suspect one`() {
        val result = MangaMergeManager.computeHealing(
            targetId = 1L,
            merges = setOf("1,2,3"),
            unmerges = emptySet(),
            trackerKeysByMangaId = mapOf(
                1L to setOf(10L to 100L),
                2L to setOf(10L to 100L), // shares with target -> kept
                3L to setOf(10L to 999L), // no overlap -> dropped
            ),
        )
        result.dropped shouldBe 1
        result.newMerges shouldContainExactly setOf("1,2")
        // The suspect is unmerged from every survivor so it can't regroup with either.
        result.newUnmerges shouldContainExactlyInAnyOrder setOf("1,3", "2,3")
    }

    // Bulk Unmerge dissolves the WHOLE group at once: every member is separated and nothing regroups.
    @Test
    fun `computeDissolve separates every member of the group`() {
        val result = MangaMergeManager.computeDissolve(
            group = longArrayOf(1L, 2L, 3L),
            merges = setOf("1,2,3"),
            unmerges = emptySet(),
        )
        result.newMerges shouldContainExactly emptySet()
        result.newUnmerges shouldContainExactlyInAnyOrder setOf("1,2", "1,3", "2,3")
    }

    @Test
    fun `computeDissolve drops only entries referencing the group and keeps the rest`() {
        val result = MangaMergeManager.computeDissolve(
            group = longArrayOf(1L, 2L, 3L),
            merges = setOf("1,2,3", "4,5"),
            unmerges = setOf("8,9"),
        )
        result.newMerges shouldContainExactly setOf("4,5")
        result.newUnmerges shouldContainExactlyInAnyOrder setOf("8,9", "1,2", "1,3", "2,3")
    }

    // A same-title-only member (no merge entry of its own) is still pinned apart so it can't re-auto-merge.
    @Test
    fun `computeDissolve pins same-title-only members apart`() {
        val result = MangaMergeManager.computeDissolve(
            group = longArrayOf(1L, 2L, 5L),
            merges = setOf("1,2"),
            unmerges = emptySet(),
        )
        result.newMerges shouldContainExactly emptySet()
        result.newUnmerges shouldContainExactlyInAnyOrder setOf("1,2", "1,5", "2,5")
    }
}
