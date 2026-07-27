package reikai.domain.manga

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.merge.MergeGroupRepository

/**
 * The manager is a thin adapter over [MergeGroupRepository] now, so its own logic is the master-switch
 * gate and the group-key mapping; the grouping math itself is covered by MergeGroupRepositoryTest.
 */
class MangaMergeManagerTest {

    /** Groups handed to the dissolve hook, in call order, so the tracker-copy step can be asserted. */
    private val dissolved = mutableListOf<List<Long>>()

    private fun manager(
        repository: MergeGroupRepository = mockk(relaxed = true),
        mergingEnabled: Boolean = true,
    ): MangaMergeManager {
        val preferences = mockk<ReikaiLibraryPreferences> {
            every { seriesMergingEnabled } returns mockk(relaxed = true) { every { get() } returns mergingEnabled }
        }
        return MangaMergeManager(repository, preferences) { dissolved += it }
    }

    @Test
    fun `computeRelatedIds returns the group members`() = runTest {
        val repo = mockk<MergeGroupRepository> {
            coEvery { getGroupId(ContentType.MANGA, 1L) } returns 7L
            coEvery { getMembers(ContentType.MANGA, 7L) } returns listOf(1L, 2L, 3L)
        }

        manager(repo).computeRelatedIds(1L).toList() shouldBe listOf(1L, 2L, 3L)
    }

    @Test
    fun `computeRelatedIds returns just itself when ungrouped`() = runTest {
        val repo = mockk<MergeGroupRepository> {
            coEvery { getGroupId(ContentType.MANGA, 1L) } returns null
        }

        manager(repo).computeRelatedIds(1L).toList() shouldBe listOf(1L)
    }

    @Test
    fun `computeRelatedIds returns just itself when merging is disabled`() = runTest {
        // The repository must not be consulted when the master switch is off.
        manager(mergingEnabled = false).computeRelatedIds(1L).toList() shouldBe listOf(1L)
    }

    @Test
    fun `seriesGroupKeys shares a key within a group and separates the rest`() = runTest {
        val repo = mockk<MergeGroupRepository> {
            coEvery { getAllMemberships(ContentType.MANGA) } returns mapOf(1L to 7L, 2L to 7L)
        }

        val keys = manager(repo).seriesGroupKeys(listOf(1L, 2L, 3L))

        keys[1L] shouldBe keys[2L]
        (keys[1L] == keys[3L]) shouldBe false
    }

    @Test
    fun `seriesGroupKeys gives every series its own key when merging is disabled`() = runTest {
        val keys = manager(mergingEnabled = false).seriesGroupKeys(listOf(1L, 2L))

        (keys[1L] == keys[2L]) shouldBe false
    }

    @Test
    fun `unmerge hands the whole group to the dissolve hook before dissolving`() = runTest {
        val repo = mockk<MergeGroupRepository>(relaxed = true) {
            coEvery { getGroupId(ContentType.MANGA, 1L) } returns 7L
            coEvery { getMembers(ContentType.MANGA, 7L) } returns listOf(1L, 2L, 3L)
        }

        manager(repo).unmerge(listOf(1L))

        dissolved shouldContainExactly listOf(listOf(1L, 2L, 3L))
        coVerify { repo.dissolve(ContentType.MANGA, 1L) }
    }

    @Test
    fun `removeFromGroup hands the group it is leaving to the dissolve hook`() = runTest {
        val repo = mockk<MergeGroupRepository>(relaxed = true)

        manager(repo).removeFromGroup(longArrayOf(1L, 2L, 3L), listOf(3L))

        dissolved shouldContainExactly listOf(listOf(1L, 2L, 3L))
    }

    @Test
    fun `clearing every merge hands each group to the dissolve hook`() = runTest {
        val repo = mockk<MergeGroupRepository>(relaxed = true) {
            coEvery { getAllMemberships(ContentType.MANGA) } returns mapOf(1L to 7L, 2L to 7L, 5L to 9L)
        }

        manager(repo).clearAllMergesIncludingAuto()

        dissolved shouldContainExactlyInAnyOrder listOf(listOf(1L, 2L), listOf(5L))
        coVerify { repo.clearAll(ContentType.MANGA) }
    }
}
