package reikai.domain.manga

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.merge.MergeGroupRepository
import tachiyomi.domain.manga.model.Manga

/**
 * The manager is a thin adapter over [MergeGroupRepository] now, so its own logic is the master-switch
 * gate and the group-key mapping; the grouping math itself is covered by MergeGroupRepositoryTest.
 */
class MangaMergeManagerTest {

    private fun manga(id: Long) = Manga.create().copy(id = id, title = "t$id", favorite = true)

    private fun manager(
        repository: MergeGroupRepository = mockk(relaxed = true),
        mergingEnabled: Boolean = true,
    ): MangaMergeManager {
        val preferences = mockk<ReikaiLibraryPreferences> {
            every { seriesMergingEnabled } returns mockk(relaxed = true) { every { get() } returns mergingEnabled }
        }
        return MangaMergeManager(repository, preferences)
    }

    @Test
    fun `computeRelatedMangaIds returns the group members`() = runTest {
        val repo = mockk<MergeGroupRepository> {
            coEvery { getGroupId(ContentType.MANGA, 1L) } returns 7L
            coEvery { getMembers(ContentType.MANGA, 7L) } returns listOf(1L, 2L, 3L)
        }

        manager(repo).computeRelatedMangaIds(1L).toList() shouldBe listOf(1L, 2L, 3L)
    }

    @Test
    fun `computeRelatedMangaIds returns just itself when ungrouped`() = runTest {
        val repo = mockk<MergeGroupRepository> {
            coEvery { getGroupId(ContentType.MANGA, 1L) } returns null
        }

        manager(repo).computeRelatedMangaIds(1L).toList() shouldBe listOf(1L)
    }

    @Test
    fun `computeRelatedMangaIds returns just itself when merging is disabled`() = runTest {
        // The repository must not be consulted when the master switch is off.
        manager(mergingEnabled = false).computeRelatedMangaIds(1L).toList() shouldBe listOf(1L)
    }

    @Test
    fun `seriesGroupKeys shares a key within a group and separates the rest`() = runTest {
        val repo = mockk<MergeGroupRepository> {
            coEvery { getAllMemberships(ContentType.MANGA) } returns mapOf(1L to 7L, 2L to 7L)
        }

        val keys = manager(repo).seriesGroupKeys(listOf(manga(1), manga(2), manga(3)))

        keys[1L] shouldBe keys[2L]
        (keys[1L] == keys[3L]) shouldBe false
    }

    @Test
    fun `seriesGroupKeys gives every series its own key when merging is disabled`() = runTest {
        val keys = manager(mergingEnabled = false).seriesGroupKeys(listOf(manga(1), manga(2)))

        (keys[1L] == keys[2L]) shouldBe false
    }
}
