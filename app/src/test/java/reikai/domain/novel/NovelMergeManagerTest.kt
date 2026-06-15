package reikai.domain.novel

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.novel.model.Novel
import tachiyomi.core.common.preference.Preference

class NovelMergeManagerTest {

    private fun novel(id: Long, title: String, author: String?) =
        Novel.create().copy(id = id, title = title, author = author, favorite = true)

    private fun <T> pref(value: T): Preference<T> = mockk(relaxed = true) { every { get() } returns value }

    private fun manager(
        favorites: List<Novel>,
        merges: Set<String> = emptySet(),
        unmerges: Set<String> = emptySet(),
        autoMergeSameTitle: Boolean = true,
        requireAuthor: Boolean = true,
    ): NovelMergeManager {
        val preferences = mockk<ReikaiLibraryPreferences> {
            every { novelManualMerges } returns pref(merges)
            every { novelManualUnmerges } returns pref(unmerges)
            every { novelAutoMergeSameTitle } returns pref(autoMergeSameTitle)
            every { novelAutoMergeRequireAuthor } returns pref(requireAuthor)
        }
        val repo = mockk<NovelRepository>()
        coEvery { repo.getFavorites() } returns favorites
        return NovelMergeManager(preferences, repo)
    }

    @Test
    fun `same-title same-author auto-groups when the author guard is on`() = runTest {
        val manager = manager(listOf(novel(1, "A", "X"), novel(2, "A", "X")))
        manager.computeRelatedNovelIds(1L, "A", "X").toList() shouldContainExactly listOf(1L, 2L)
    }

    @Test
    fun `same-title different-author does not auto-group with the author guard on`() = runTest {
        val manager = manager(listOf(novel(1, "A", "X"), novel(2, "A", "Y")))
        manager.computeRelatedNovelIds(1L, "A", "X").toList() shouldContainExactly listOf(1L)
    }

    @Test
    fun `a blank target author does not auto-group with the guard on`() = runTest {
        val manager = manager(listOf(novel(1, "A", null), novel(2, "A", null)))
        manager.computeRelatedNovelIds(1L, "A", null).toList() shouldContainExactly listOf(1L)
    }

    @Test
    fun `same-title different-author auto-groups with the guard off`() = runTest {
        val manager = manager(listOf(novel(1, "A", "X"), novel(2, "A", "Y")), requireAuthor = false)
        manager.computeRelatedNovelIds(1L, "A", "X").toList() shouldContainExactly listOf(1L, 2L)
    }

    @Test
    fun `auto-merge off groups nothing by title`() = runTest {
        val manager = manager(listOf(novel(1, "A", "X"), novel(2, "A", "X")), autoMergeSameTitle = false)
        manager.computeRelatedNovelIds(1L, "A", "X").toList() shouldContainExactly listOf(1L)
    }

    @Test
    fun `a manual merge groups regardless of title or author`() = runTest {
        val manager = manager(listOf(novel(1, "A", "X"), novel(5, "B", "Y")), merges = setOf("1,5"))
        manager.computeRelatedNovelIds(1L, "A", "X").toList() shouldContainExactly listOf(1L, 5L)
    }

    @Test
    fun `an explicit unmerge keeps an auto-grouped novel apart`() = runTest {
        val manager = manager(listOf(novel(1, "A", "X"), novel(2, "A", "X")), unmerges = setOf("1,2"))
        manager.computeRelatedNovelIds(1L, "A", "X").toList() shouldContainExactly listOf(1L)
    }

    // seriesGroupKeys backs the Updates merge-aware grouping: same group => same key.

    @Test
    fun `seriesGroupKeys gives a merged same-title pair one shared key`() {
        val favs = listOf(novel(1, "A", "X"), novel(3, "A", "X"))
        val keys = manager(favs).seriesGroupKeys(favs)
        keys[1L] shouldBe keys[3L]
    }

    @Test
    fun `seriesGroupKeys keeps an unmerged same-title pair in distinct keys`() {
        val favs = listOf(novel(1, "A", "X"), novel(3, "A", "X"))
        val keys = manager(favs, unmerges = setOf("1,3")).seriesGroupKeys(favs)
        (keys[1L] == keys[3L]) shouldBe false
    }

    @Test
    fun `seriesGroupKeys gives unrelated novels distinct keys`() {
        val favs = listOf(novel(1, "A", "X"), novel(2, "B", "Y"))
        val keys = manager(favs).seriesGroupKeys(favs)
        (keys[1L] == keys[2L]) shouldBe false
    }
}
