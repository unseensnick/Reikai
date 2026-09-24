package reikai.domain.merge

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.manga.MangaMergeManager
import reikai.domain.merge.model.MergeGroup
import reikai.domain.novel.NovelMergeManager
import tachiyomi.core.common.preference.Preference

/**
 * One suite over both content types, each built through its own subclass so the type it fixes is
 * pinned too. The manager's own logic is the master-switch gate, the dissolve hook and the per-type
 * same-title preference; the grouping math is covered by MergeGroupRepositoryTest.
 */
class EntryMergeManagerTest {

    /** Groups handed to the dissolve hook, in call order, so the tracker-copy step can be asserted. */
    private val dissolved = mutableListOf<List<Long>>()

    private fun manager(
        type: ContentType,
        repository: MergeGroupRepository = mockk(relaxed = true),
        mergingEnabled: Boolean = true,
        mangaSameTitle: Boolean = false,
        novelSameTitle: Boolean = false,
    ): EntryMergeManager {
        val preferences = mockk<ReikaiLibraryPreferences> {
            every { seriesMergingEnabled } returns preference(mergingEnabled)
            every { autoMergeSameTitle } returns preference(mangaSameTitle)
            every { novelAutoMergeSameTitle } returns preference(novelSameTitle)
        }
        val onBeforeDissolve: suspend (List<Long>) -> Unit = { dissolved += it }
        return when (type) {
            ContentType.MANGA -> MangaMergeManager(repository, preferences, onBeforeDissolve)
            ContentType.NOVELS -> NovelMergeManager(repository, preferences, onBeforeDissolve)
            ContentType.ALL -> error("not a merge-group type")
        }
    }

    private fun preference(value: Boolean) = mockk<Preference<Boolean>> { every { get() } returns value }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `computeRelatedIds returns the group members`(type: ContentType) = runTest {
        val repo = mockk<MergeGroupRepository> {
            coEvery { getGroupId(type, 1L) } returns 7L
            coEvery { getFavoriteMembers(type, 7L) } returns listOf(1L, 2L, 3L)
        }

        manager(type, repo).computeRelatedIds(1L).toList() shouldBe listOf(1L, 2L, 3L)
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `relatedIdsList returns the group members`(type: ContentType) = runTest {
        val repo = mockk<MergeGroupRepository> {
            coEvery { getGroupId(type, 1L) } returns 7L
            coEvery { getFavoriteMembers(type, 7L) } returns listOf(1L, 2L)
        }

        manager(type, repo).relatedIdsList(1L) shouldBe listOf(1L, 2L)
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `computeRelatedIds leaves out a member that is no longer in the library`(type: ContentType) = runTest {
        // The group is preserved so a re-add rejoins it, but a removed source must stop feeding what
        // the group shows: its chapters, its counts, its chip. Every display read comes through here.
        val repo = mockk<MergeGroupRepository> {
            coEvery { getGroupId(type, 1L) } returns 7L
            coEvery { getFavoriteMembers(type, 7L) } returns listOf(1L, 3L)
        }

        manager(type, repo).computeRelatedIds(1L).toList() shouldBe listOf(1L, 3L)
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `computeRelatedIds falls back to the target when the whole group has left the library`(
        type: ContentType,
    ) = runTest {
        // A caller resolving an entry it is already holding must never get an empty list back.
        val repo = mockk<MergeGroupRepository> {
            coEvery { getGroupId(type, 1L) } returns 7L
            coEvery { getFavoriteMembers(type, 7L) } returns emptyList()
        }

        manager(type, repo).computeRelatedIds(1L).toList() shouldBe listOf(1L)
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `computeRelatedIds returns just itself when ungrouped`(type: ContentType) = runTest {
        val repo = mockk<MergeGroupRepository> {
            coEvery { getGroupId(type, 1L) } returns null
        }

        manager(type, repo).computeRelatedIds(1L).toList() shouldBe listOf(1L)
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `resolution returns just itself when merging is disabled`(type: ContentType) = runTest {
        // The repository must not be consulted when the master switch is off.
        manager(type, mergingEnabled = false).computeRelatedIds(1L).toList() shouldBe listOf(1L)
        manager(type, mergingEnabled = false).relatedIdsList(1L) shouldBe listOf(1L)
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `handOutTrackersBeforeRemoval hands each group its members once`(type: ContentType) = runTest {
        // Two members of one group are being removed together; the hand-out is per group, not per entry.
        val repo = mockk<MergeGroupRepository>(relaxed = true) {
            coEvery { getGroupId(type, 1L) } returns 7L
            coEvery { getGroupId(type, 2L) } returns 7L
            coEvery { getFavoriteMembers(type, 7L) } returns listOf(1L, 2L, 3L)
        }

        manager(type, repo).handOutTrackersBeforeRemoval(listOf(1L, 2L))

        dissolved shouldContainExactly listOf(listOf(1L, 2L, 3L))
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `handOutTrackersBeforeRemoval does nothing while merging is disabled`(type: ContentType) = runTest {
        // Nothing resolves as a group with the switch off, so no tracker was ever shared to hand out.
        manager(type, mergingEnabled = false).handOutTrackersBeforeRemoval(listOf(1L))

        dissolved.isEmpty() shouldBe true
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `merge is refused when merging is disabled`(type: ContentType) = runTest {
        // Nothing renders a group written while the switch is off, and the library's Unmerge action only
        // appears for a row the collapse marked, so the write had no undo on the surface that made it.
        val repo = mockk<MergeGroupRepository>(relaxed = true)

        manager(type, repo, mergingEnabled = false).merge(listOf(1L, 2L))

        coVerify(exactly = 0) { repo.merge(any(), any()) }
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `merge reaches the repository when merging is enabled`(type: ContentType) = runTest {
        val repo = mockk<MergeGroupRepository>(relaxed = true)

        manager(type, repo).merge(listOf(1L, 2L))

        coVerify { repo.merge(type, listOf(1L, 2L)) }
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `unmerge hands the whole group to the dissolve hook before dissolving`(type: ContentType) = runTest {
        val repo = mockk<MergeGroupRepository>(relaxed = true) {
            coEvery { getGroupId(type, 1L) } returns 7L
            coEvery { getFavoriteMembers(type, 7L) } returns listOf(1L, 2L, 3L)
        }

        manager(type, repo).unmerge(listOf(1L))

        dissolved shouldContainExactly listOf(listOf(1L, 2L, 3L))
        // Ordered: the hook has to read the members while the group still has them. Run it after the
        // dissolve and it sees nothing, so nobody gets their own copy of the shared tracker link.
        coVerifyOrder {
            repo.getFavoriteMembers(type, 7L)
            repo.dissolve(type, 1L)
        }
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `removeFromGroup hands the group it is leaving to the dissolve hook`(type: ContentType) = runTest {
        val repo = mockk<MergeGroupRepository>(relaxed = true)

        manager(type, repo).removeFromGroup(longArrayOf(1L, 2L, 3L), listOf(3L))

        dissolved shouldContainExactly listOf(listOf(1L, 2L, 3L))
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `clearing every merge hands each group to the dissolve hook`(type: ContentType) = runTest {
        val repo = mockk<MergeGroupRepository>(relaxed = true) {
            coEvery { getAllMemberships(type) } returns mapOf(1L to 7L, 2L to 7L, 5L to 9L)
        }

        manager(type, repo).clearAllMergesIncludingAuto()

        dissolved shouldContainExactlyInAnyOrder listOf(listOf(1L, 2L), listOf(5L))
        coVerify { repo.clearAll(type) }
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `suggestGroupingOnAdd reads its own type's same-title preference`(type: ContentType) {
        // Only this type's preference is on, so a manager reading the other type's gets false.
        val manager = manager(
            type,
            mangaSameTitle = type == ContentType.MANGA,
            novelSameTitle = type == ContentType.NOVELS,
        )

        manager.suggestGroupingOnAdd shouldBe true
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `groupIdsFor maps only the grouped ids`(type: ContentType) = runTest {
        val repo = mockk<MergeGroupRepository> {
            coEvery { getAllMemberships(type) } returns mapOf(1L to 7L, 2L to 7L)
        }

        manager(type, repo).groupIdsFor(listOf(1L, 3L)) shouldBe mapOf(1L to 7L)
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `groupIdsFor is empty while merging is disabled`(type: ContentType) = runTest {
        val repo = mockk<MergeGroupRepository> {
            coEvery { getAllMemberships(type) } returns mapOf(1L to 7L)
        }

        manager(type, repo, mergingEnabled = false).groupIdsFor(listOf(1L)) shouldBe emptyMap()
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `overrideRankingMemberIds returns the group's own order when it overrides the ranking`(
        type: ContentType,
    ) = runTest {
        val repo = mockk<MergeGroupRepository> {
            coEvery { getGroupId(type, 1L) } returns 7L
            coEvery { getGroup(7L) } returns MergeGroup(7L, type, overrideSourceRanking = true)
            coEvery { getMembers(type, 7L) } returns listOf(2L, 1L)
        }

        manager(type, repo).overrideRankingMemberIds(1L) shouldBe listOf(2L, 1L)
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `overrideRankingMemberIds is empty when the group follows the global ranking`(type: ContentType) =
        runTest {
            val repo = mockk<MergeGroupRepository> {
                coEvery { getGroupId(type, 1L) } returns 7L
                coEvery { getGroup(7L) } returns MergeGroup(7L, type, overrideSourceRanking = false)
                coEvery { getMembers(type, 7L) } returns listOf(2L, 1L)
            }

            manager(type, repo).overrideRankingMemberIds(1L) shouldBe emptyList()
        }
}
