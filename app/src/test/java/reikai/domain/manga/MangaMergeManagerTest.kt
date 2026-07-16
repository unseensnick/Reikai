package reikai.domain.manga

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.library.ReikaiLibraryPreferences
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga

class MangaMergeManagerTest {

    private fun manga(id: Long, title: String) = Manga.create().copy(id = id, title = title, favorite = true)

    private fun groupKeyManager(
        merges: Set<String> = emptySet(),
        unmerges: Set<String> = emptySet(),
        autoMerge: Boolean = true,
    ): MangaMergeManager {
        val preferences = mockk<ReikaiLibraryPreferences> {
            every { mangaManualMerges } returns mockk(relaxed = true) { every { get() } returns merges }
            every { mangaManualUnmerges } returns mockk(relaxed = true) { every { get() } returns unmerges }
            every { autoMergeSameTitle } returns mockk(relaxed = true) { every { get() } returns autoMerge }
        }
        return MangaMergeManager(preferences, mockk(), mockk())
    }

    @Test
    fun `seriesGroupKeys gives same-title manga one shared key`() {
        val favs = listOf(manga(1, "A"), manga(2, "A"))
        val keys = groupKeyManager().seriesGroupKeys(favs)
        keys[1L] shouldBe keys[2L]
    }

    @Test
    fun `seriesGroupKeys keeps an unmerged same-title pair in distinct keys`() {
        val favs = listOf(manga(1, "A"), manga(2, "A"))
        val keys = groupKeyManager(unmerges = setOf("1,2")).seriesGroupKeys(favs)
        (keys[1L] == keys[2L]) shouldBe false
    }

    @Test
    fun `seriesGroupKeys gives unrelated manga distinct keys`() {
        val favs = listOf(manga(1, "A"), manga(2, "B"))
        val keys = groupKeyManager().seriesGroupKeys(favs)
        (keys[1L] == keys[2L]) shouldBe false
    }

    private fun managerWith(
        merges: Set<String>,
        unmerges: Set<String>,
    ): Triple<MangaMergeManager, Preference<Set<String>>, Preference<Set<String>>> {
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
    fun `computeHealing keeps a sibling tracked on a different service`() {
        val result = MangaMergeManager.computeHealing(
            targetId = 1L,
            merges = setOf("1,2"),
            unmerges = emptySet(),
            trackerKeysByMangaId = mapOf(
                1L to setOf(10L to 100L), // e.g. AniList
                2L to setOf(20L to 555L), // e.g. MyAnimeList: different service, not comparable -> kept
            ),
        )
        result.dropped shouldBe 0
        result.newMerges shouldContainExactly setOf("1,2")
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

    @Test
    fun `mergeSelectedManga expands same-title cards so one merge coalesces every source`() = runTest {
        val mergesPref = mockk<Preference<Set<String>>>(relaxed = true)
        val unmergesPref = mockk<Preference<Set<String>>>(relaxed = true)
        every { mergesPref.get() } returns emptySet()
        every { unmergesPref.get() } returns emptySet()
        val preferences = mockk<ReikaiLibraryPreferences> {
            every { mangaManualMerges } returns mergesPref
            every { mangaManualUnmerges } returns unmergesPref
            every { autoMergeSameTitle } returns mockk(relaxed = true) { every { get() } returns true }
        }
        val getFavorites = mockk<GetFavorites>()
        coEvery { getFavorites.await() } returns listOf(manga(1, "A"), manga(2, "A"), manga(3, "B"), manga(4, "B"))
        val manager = MangaMergeManager(preferences, getFavorites, mockk())

        // Select only the two collapsed cards' representatives (1 = card "A", 3 = card "B").
        manager.mergeSelectedManga(listOf(1L, 3L))

        // Both hidden same-title members (2, 4) are pulled in, so one merge records all four.
        verify { mergesPref.set(setOf("1,2,3,4")) }
    }

    @Test
    fun `mergeSelectedManga with auto-merge off merges only the selected ids`() = runTest {
        val mergesPref = mockk<Preference<Set<String>>>(relaxed = true)
        val unmergesPref = mockk<Preference<Set<String>>>(relaxed = true)
        every { mergesPref.get() } returns emptySet()
        every { unmergesPref.get() } returns emptySet()
        val preferences = mockk<ReikaiLibraryPreferences> {
            every { mangaManualMerges } returns mergesPref
            every { mangaManualUnmerges } returns unmergesPref
            every { autoMergeSameTitle } returns mockk(relaxed = true) { every { get() } returns false }
        }
        val manager = MangaMergeManager(preferences, mockk(), mockk())

        manager.mergeSelectedManga(listOf(1L, 3L))

        verify { mergesPref.set(setOf("1,3")) }
    }
}
