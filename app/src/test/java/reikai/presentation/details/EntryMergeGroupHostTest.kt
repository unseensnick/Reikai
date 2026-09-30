package reikai.presentation.details

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.merge.ChapterUnit
import reikai.domain.merge.EntryMergeManager

/**
 * The host is the shared merge read wiring both details models compose. Its own responsibility is small:
 * resolve the group id -> chips for the first-render seed, and keep [EntryMergeGroupHost.relatedIds] +
 * [EntryMergeGroupHost.chips] live off the injected anchor flow. The per-type source resolution is the
 * caller's closure, so it is stubbed here; the manager math is covered by the *MergeManagerTest classes.
 */
class EntryMergeGroupHostTest {

    private val chips = listOf(EntryMergeSource(1L, "A"), EntryMergeSource(2L, "B"))

    private fun host(
        manager: EntryMergeManager,
        anchorChanges: kotlinx.coroutines.flow.Flow<Long> = emptyFlow(),
        resolveSources: suspend (LongArray) -> List<EntryMergeSource> = { chips },
    ) = EntryMergeGroupHost(
        mergeManager = manager,
        initialIds = longArrayOf(1L),
        anchorChanges = anchorChanges,
        resolveSources = resolveSources,
    )

    @Test
    fun `seed returns the resolved chips`() = runTest {
        val manager = mockk<EntryMergeManager> { coEvery { computeRelatedIds(1L) } returns longArrayOf(1L, 2L) }

        host(manager).seed(1L) shouldBe chips
    }

    @Test
    fun `seed sets relatedIds to the computed group`() = runTest {
        val manager = mockk<EntryMergeManager> { coEvery { computeRelatedIds(1L) } returns longArrayOf(1L, 2L) }

        val host = host(manager)
        host.seed(1L)

        host.relatedIds.toList() shouldBe listOf(1L, 2L)
    }

    @Test
    fun `a selected source that leaves the group is dropped`() = runTest {
        val manager = mockk<EntryMergeManager> { coEvery { computeRelatedIds(1L) } returns longArrayOf(1L, 2L) }
        val host = host(manager)
        host.seed(1L)
        host.selectSource(2L)

        // Migrating source 2 away seats its replacement in the group; the chip must not survive it,
        // or the chapter pipeline looks up a member that is gone.
        host.setRelated(longArrayOf(1L, 3L))

        host.selectedSource shouldBe null
    }

    @Test
    fun `a selected source that survives a split stays selected`() = runTest {
        val manager = mockk<EntryMergeManager> { coEvery { computeRelatedIds(1L) } returns longArrayOf(1L, 2L, 3L) }
        val host = host(manager)
        host.seed(1L)
        host.selectSource(2L)

        host.setRelated(longArrayOf(1L, 2L))

        host.selectedSource shouldBe 2L
    }

    @Test
    fun `refresh re-reads the group instead of trusting what a caller hands it`() = runTest {
        val manager = mockk<EntryMergeManager> {
            coEvery { computeRelatedIds(1L) } returns longArrayOf(1L, 2L, 3L)
        }
        val host = host(manager)
        host.seed(1L)

        // The anchor is split out of its own group, so the split returns the SURVIVORS, which are the
        // two entries the screen is NOT showing. Re-reading gives the anchor standing alone instead.
        coEvery { manager.computeRelatedIds(1L) } returns longArrayOf(1L)
        host.refresh(1L)

        host.relatedIds.toList() shouldBe listOf(1L)
    }

    @Test
    fun `selecting a source outside the group is refused`() = runTest {
        val manager = mockk<EntryMergeManager> { coEvery { computeRelatedIds(1L) } returns longArrayOf(1L, 2L) }
        val host = host(manager)
        host.seed(1L)

        host.selectSource(9L)

        host.selectedSource shouldBe null
    }

    // Chapter 1 is on source 1, its copy 2 on source 2; only copy 2 is on disk.
    private val stitch = listOf(ChapterUnit(1L, 0, 0), ChapterUnit(2L, 0, 1))

    private suspend fun mergedHost(selected: Long?): EntryMergeGroupHost {
        val manager = mockk<EntryMergeManager> { coEvery { computeRelatedIds(1L) } returns longArrayOf(1L, 2L) }
        return host(manager).also {
            it.seed(1L)
            it.selectSource(selected)
        }
    }

    private suspend fun rowDownloaded(selected: Long?): Boolean =
        mergedHost(selected).state.value.rowFlags(listOf(1L, 2L), listOf(1L), stitch, { it }, { false }, { false }) {
            setOf(2L)
        }.isDownloaded(1L)

    @Test
    fun `under All a row whose other copy is on disk reads as downloaded`() = runTest {
        rowDownloaded(selected = null) shouldBe true
    }

    @Test
    fun `under a source chip a row whose only copy on disk is another source's is not downloaded`() = runTest {
        rowDownloaded(selected = 1L) shouldBe false
    }

    @Test
    fun `a delete under All reaches every copy`() = runTest {
        mergedHost(selected = null).expandForDelete(listOf(1L), { it }, { stitch }, { it.toList() }).toSet() shouldBe
            setOf(1L, 2L)
    }

    @Test
    fun `a delete under a source chip reaches only the chip's own copy`() = runTest {
        mergedHost(selected = 1L).expandForDelete(listOf(1L), { it }, { stitch }, { it.toList() }) shouldBe listOf(1L)
    }

    @Test
    fun `marking read under a source chip still reaches every copy`() = runTest {
        mergedHost(selected = 1L).expandToGroup(listOf(1L), { it }, { stitch }, { it.toList() }).toSet() shouldBe
            setOf(1L, 2L)
    }

    /** A host whose live group has resolved to three sources, each with its chip. */
    private suspend fun TestScope.threeSourceHost(): EntryMergeGroupHost {
        val manager = mockk<EntryMergeManager> { coEvery { computeRelatedIds(1L) } returns longArrayOf(1L, 2L, 3L) }
        val host = host(
            manager = manager,
            anchorChanges = MutableStateFlow(1L),
            resolveSources = { ids -> ids.map { EntryMergeSource(it, "src$it") } },
        )
        host.observe(backgroundScope)
        host.chips.first { it.size == 3 }
        return host
    }

    private fun group(vararg ids: Long) = EntryMergeGroupHost.GroupState(ids, selected = null)

    @Test
    fun `a list built for the live group shows every chip`() = runTest {
        val host = threeSourceHost()

        host.chipsOf(host.state.value).map { it.id } shouldBe listOf(1L, 2L, 3L)
    }

    @Test
    fun `a list built for an earlier group shows no chip of a source that joined since`() = runTest {
        threeSourceHost().chipsOf(group(1L, 2L)).map { it.id } shouldBe listOf(1L, 2L)
    }

    @Test
    fun `a list built before the entry was merged shows no chips`() = runTest {
        threeSourceHost().chipsOf(group(1L)) shouldBe emptyList()
    }

    @Test
    fun `observe recomputes the group and chips from the anchor`() = runTest {
        val manager = mockk<EntryMergeManager> { coEvery { computeRelatedIds(1L) } returns longArrayOf(1L, 2L, 3L) }
        val host = host(
            manager = manager,
            anchorChanges = MutableStateFlow(1L),
            resolveSources = { ids -> ids.map { EntryMergeSource(it, "src$it") } },
        )

        host.observe(backgroundScope)

        host.chips.first { it.size == 3 } shouldBe listOf(
            EntryMergeSource(1L, "src1"),
            EntryMergeSource(2L, "src2"),
            EntryMergeSource(3L, "src3"),
        )
    }
}
