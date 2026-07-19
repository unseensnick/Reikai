package reikai.domain.novel

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.merge.MergeGroupRepository

/**
 * The manager is a thin adapter over [MergeGroupRepository]; its own logic is the master-switch gate and
 * the group-key mapping. The grouping math is covered by MergeGroupRepositoryTest.
 */
class NovelMergeManagerTest {

    private fun manager(
        repository: MergeGroupRepository = mockk(relaxed = true),
        mergingEnabled: Boolean = true,
    ): NovelMergeManager {
        val preferences = mockk<ReikaiLibraryPreferences> {
            every { seriesMergingEnabled } returns mockk(relaxed = true) { every { get() } returns mergingEnabled }
        }
        return NovelMergeManager(repository, preferences)
    }

    @Test
    fun `computeRelatedIds returns the group members`() = runTest {
        val repo = mockk<MergeGroupRepository> {
            coEvery { getGroupId(ContentType.NOVELS, 1L) } returns 7L
            coEvery { getMembers(ContentType.NOVELS, 7L) } returns listOf(1L, 2L, 3L)
        }

        manager(repo).computeRelatedIds(1L).toList() shouldBe listOf(1L, 2L, 3L)
    }

    @Test
    fun `relatedIdsList returns the group members`() = runTest {
        val repo = mockk<MergeGroupRepository> {
            coEvery { getGroupId(ContentType.NOVELS, 1L) } returns 7L
            coEvery { getMembers(ContentType.NOVELS, 7L) } returns listOf(1L, 2L)
        }

        manager(repo).relatedIdsList(1L) shouldBe listOf(1L, 2L)
    }

    @Test
    fun `computeRelatedIds returns just itself when ungrouped`() = runTest {
        val repo = mockk<MergeGroupRepository> {
            coEvery { getGroupId(ContentType.NOVELS, 1L) } returns null
        }

        manager(repo).computeRelatedIds(1L).toList() shouldBe listOf(1L)
    }

    @Test
    fun `resolution returns just itself when merging is disabled`() = runTest {
        manager(mergingEnabled = false).computeRelatedIds(1L).toList() shouldBe listOf(1L)
        manager(mergingEnabled = false).relatedIdsList(1L) shouldBe listOf(1L)
    }

    @Test
    fun `seriesGroupKeys shares a key within a group and separates the rest`() = runTest {
        val repo = mockk<MergeGroupRepository> {
            coEvery { getAllMemberships(ContentType.NOVELS) } returns mapOf(1L to 7L, 2L to 7L)
        }

        val keys = manager(repo).seriesGroupKeys(listOf(1L, 2L, 3L))

        keys[1L] shouldBe keys[2L]
        (keys[1L] == keys[3L]) shouldBe false
    }
}
