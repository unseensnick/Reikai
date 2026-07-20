package reikai.presentation.details

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
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

        host.relatedIds.value.toList() shouldBe listOf(1L, 2L)
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
