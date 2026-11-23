package reikai.presentation.details

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType
import reikai.domain.merge.TestMergeManagers

/**
 * What a details page's source chip shows, through the group host both details models compose: the
 * entries Clear downloads and the scanlator filter reach, and when a chip flip reaches the model. The
 * models used to state both themselves, and switching a chip kept the selection on manga but dropped
 * it on novels. Both types' merge managers are the shared EntryMergeManager with no override, so the
 * cases run once, on manga.
 */
class DetailsChipViewConformanceTest {

    private val flips = mutableListOf<Pair<Long?, Long?>>()
    private var selectedDuringFlip: Long? = UNSET

    private suspend fun host(
        merged: Boolean = true,
        seed: Boolean = true,
        observed: Boolean = false,
    ): EntryMergeGroupHost {
        val group = if (merged) mapOf(OPENED to GROUP, SIBLING to GROUP) else emptyMap()
        val managers = TestMergeManagers(mapOf(ContentType.MANGA to group), mergingOn = true)
        val manager = if (observed) {
            spyk(managers.manga) { every { relatedIdsChanges() } returns flowOf(Unit) }
        } else {
            managers.manga
        }
        lateinit var host: EntryMergeGroupHost
        host = EntryMergeGroupHost(
            mergeManager = manager,
            initialIds = if (seed) longArrayOf(OPENED) else longArrayOf(),
            anchorChanges = emptyFlow(),
            onSourceChange = { from, to ->
                flips += from to to
                selectedDuringFlip = host.selectedSource
            },
        ) { ids -> ids.map { EntryMergeSource(it, "src$it") } }
        if (seed) host.refresh(OPENED)
        return host
    }

    @Test
    fun `a source chip views that source alone`() = runTest {
        val host = host()
        host.selectSource(SIBLING)
        host.state.value.viewedIds(OPENED) shouldContainExactly listOf(SIBLING)
    }

    @Test
    fun `All views every grouped source`() = runTest {
        host().state.value.viewedIds(OPENED) shouldContainExactly listOf(OPENED, SIBLING)
    }

    @Test
    fun `a standalone entry views itself`() = runTest {
        host(merged = false).state.value.viewedIds(OPENED) shouldContainExactly listOf(OPENED)
    }

    @Test
    fun `a group not yet resolved views the opened entry`() = runTest {
        host(seed = false).state.value.viewedIds(OPENED) shouldContainExactly listOf(OPENED)
    }

    @Test
    fun `switching the chip tells the model the flip once`() = runTest {
        val host = host()
        host.selectSource(SIBLING)
        flips shouldContainExactly listOf(null to SIBLING)
    }

    @Test
    fun `the model hears of the flip before the list does`() = runTest {
        val host = host()
        host.selectSource(SIBLING)
        selectedDuringFlip shouldBe null
    }

    @Test
    fun `picking the chip already shown tells the model nothing`() = runTest {
        val host = host()
        host.selectSource(null)
        flips shouldBe emptyList()
    }

    @Test
    fun `a refused chip tells the model nothing`() = runTest {
        val host = host()
        host.selectSource(OUTSIDER)
        flips shouldBe emptyList()
    }

    @Test
    fun `Clear downloads names the chip it deletes`() = runTest {
        val host = host(observed = true)
        host.observe(backgroundScope)
        host.chips.first { it.size == 2 }
        host.selectSource(SIBLING)
        host.clearDownloadsTarget(OPENED) shouldBe ClearDownloadsTarget("src$SIBLING", listOf(SIBLING))
    }

    @Test
    fun `Clear downloads under All names no source and deletes the group`() = runTest {
        host().clearDownloadsTarget(OPENED) shouldBe ClearDownloadsTarget(null, listOf(OPENED, SIBLING))
    }

    private companion object {
        const val OPENED = 1L
        const val SIBLING = 2L
        const val OUTSIDER = 9L
        const val GROUP = 10L
        const val UNSET = -1L
    }
}
