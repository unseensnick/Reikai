package reikai.domain.merge

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.merge.MergeGroupReconstruction.Candidate

class MergeGroupReconstructionTest {

    private fun candidate(id: Long, title: String = "t$id", author: String? = null) = Candidate(id, title, author)

    private fun reconstruct(
        candidates: List<Candidate>,
        manualMerges: Set<String> = emptySet(),
        unmerges: Set<String> = emptySet(),
        autoMergeByTitle: Boolean = false,
        requireAuthor: Boolean = false,
    ) = MergeGroupReconstruction.reconstruct(candidates, manualMerges, unmerges, autoMergeByTitle, requireAuthor)

    @Test
    fun `a manual merge entry becomes one group`() {
        val groups = reconstruct(
            candidates = listOf(candidate(1), candidate(2), candidate(3)),
            manualMerges = setOf("1,2,3"),
        )

        groups shouldBe listOf(listOf(1L, 2L, 3L))
    }

    @Test
    fun `same-title favorites group only when auto-merge is on`() {
        val candidates = listOf(candidate(1, title = "Solo"), candidate(2, title = "solo "))

        reconstruct(candidates, autoMergeByTitle = false) shouldBe emptyList()
        reconstruct(candidates, autoMergeByTitle = true) shouldBe listOf(listOf(1L, 2L))
    }

    @Test
    fun `the author guard splits same-title different-author entries`() {
        val candidates = listOf(
            candidate(1, title = "Re Zero", author = "A"),
            candidate(2, title = "Re Zero", author = "B"),
            candidate(3, title = "Re Zero", author = "A"),
        )

        // Title-only: all three group.
        reconstruct(candidates, autoMergeByTitle = true, requireAuthor = false) shouldBe
            listOf(listOf(1L, 2L, 3L))
        // Author guard: only the matching-author pair groups.
        reconstruct(candidates, autoMergeByTitle = true, requireAuthor = true) shouldBe
            listOf(listOf(1L, 3L))
    }

    @Test
    fun `an unmerge pair excludes a same-title link`() {
        val candidates = listOf(candidate(1, title = "Same"), candidate(2, title = "Same"))

        reconstruct(candidates, unmerges = setOf("1,2"), autoMergeByTitle = true) shouldBe emptyList()
    }

    @Test
    fun `a manual merge overrides an unmerge on the same pair`() {
        val groups = reconstruct(
            candidates = listOf(candidate(1), candidate(2)),
            manualMerges = setOf("1,2"),
            unmerges = setOf("1,2"),
        )

        groups shouldBe listOf(listOf(1L, 2L))
    }

    @Test
    fun `transitive same-title links level up past an unmerged pair`() {
        // A-B and B-C share a title; A-C is unmerged. They still land in one group via B (level up).
        val candidates = listOf(
            candidate(1, title = "Series"),
            candidate(2, title = "Series"),
            candidate(3, title = "Series"),
        )

        reconstruct(candidates, unmerges = setOf("1,3"), autoMergeByTitle = true) shouldBe
            listOf(listOf(1L, 2L, 3L))
    }

    @Test
    fun `blank titles never auto-group`() {
        val candidates = listOf(candidate(1, title = "  "), candidate(2, title = ""))

        reconstruct(candidates, autoMergeByTitle = true) shouldBe emptyList()
    }

    @Test
    fun `singletons are dropped and manual plus same-title combine`() {
        val candidates = listOf(
            candidate(1, title = "X"),
            candidate(2, title = "X"),
            candidate(3, title = "Y"),
            candidate(4, title = "Z"),
            candidate(5, title = "W"),
        )

        // 1+2 by title; 4+5 by manual merge; 3 stays a singleton (dropped).
        val groups = reconstruct(
            candidates = candidates,
            manualMerges = setOf("4,5"),
            autoMergeByTitle = true,
        )

        groups shouldContainExactlyInAnyOrder listOf(listOf(1L, 2L), listOf(4L, 5L))
    }

    @Test
    fun `a manual merge referencing a missing id ignores it`() {
        // Id 9 is not a favorite (deleted or unfavorited); it must not appear in any group.
        val groups = reconstruct(
            candidates = listOf(candidate(1), candidate(2)),
            manualMerges = setOf("1,2,9"),
        )

        groups shouldBe listOf(listOf(1L, 2L))
    }
}
