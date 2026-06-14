package reikai.domain.manga

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import reikai.domain.library.ReikaiLibraryPreferences
import tachiyomi.core.common.preference.Preference

class MangaMergeManagerTest {

    private fun managerWith(merges: Set<String>, unmerges: Set<String>): Triple<MangaMergeManager, Preference<Set<String>>, Preference<Set<String>>> {
        val mergesPref = mockk<Preference<Set<String>>>(relaxed = true)
        val unmergesPref = mockk<Preference<Set<String>>>(relaxed = true)
        every { mergesPref.get() } returns merges
        every { unmergesPref.get() } returns unmerges
        val preferences = mockk<ReikaiLibraryPreferences> {
            every { mangaManualMerges } returns mergesPref
            every { mangaManualUnmerges } returns unmergesPref
        }
        return Triple(MangaMergeManager(preferences, mockk(), mockk()), mergesPref, unmergesPref)
    }

    @Test
    fun `splitOrDissolve dissolves the group when every source is selected`() {
        val (manager, mergesPref, unmergesPref) = managerWith(setOf("1,2,3"), emptySet())

        val survivors = manager.splitOrDissolve(longArrayOf(1L, 2L, 3L), listOf(1L, 2L, 3L))

        survivors.toList().shouldBeEmpty()
        verify { mergesPref.set(emptySet()) }
        verify { unmergesPref.set(setOf("1,2", "1,3", "2,3")) }
    }

    @Test
    fun `splitOrDissolve splits a subset and keeps the survivors grouped`() {
        val (manager, mergesPref, unmergesPref) = managerWith(setOf("1,2,3"), emptySet())

        val survivors = manager.splitOrDissolve(longArrayOf(1L, 2L, 3L), listOf(3L))

        survivors.toList() shouldContainExactly listOf(1L, 2L)
        verify { mergesPref.set(setOf("1,2")) }
        verify { unmergesPref.set(setOf("1,3", "2,3")) }
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
}
