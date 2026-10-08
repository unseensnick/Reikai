package reikai.presentation.details

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.library.ContentType
import reikai.domain.merge.TestMergeManagers

/**
 * What a details page's source chip shows, through the group host both details models compose: the
 * entries Clear downloads and the scanlator filter reach, and when a chip flip reaches the model. The
 * models used to state both themselves, and switching a chip kept the selection on manga but dropped
 * it on novels.
 */
class DetailsChipViewConformanceTest {

    private val flips = mutableListOf<Pair<Long?, Long?>>()
    private var selectedDuringFlip: Long? = UNSET

    private suspend fun host(
        type: ContentType,
        merged: Boolean = true,
        seed: Boolean = true,
        observed: Boolean = false,
    ): EntryMergeGroupHost {
        val group = if (merged) mapOf(OPENED to GROUP, SIBLING to GROUP) else emptyMap()
        val managers = TestMergeManagers(mapOf(type to group), mergingOn = true)
        val manager = if (observed) {
            spyk(managers.of(type)) { every { relatedIdsChanges() } returns flowOf(Unit) }
        } else {
            managers.of(type)
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

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a source chip views that source alone`(type: ContentType) = runTest {
        val host = host(type)
        host.selectSource(SIBLING)
        host.state.value.viewedIds(OPENED) shouldContainExactly listOf(SIBLING)
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `All views every grouped source`(type: ContentType) = runTest {
        host(type).state.value.viewedIds(OPENED) shouldContainExactly listOf(OPENED, SIBLING)
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a standalone entry views itself`(type: ContentType) = runTest {
        host(type, merged = false).state.value.viewedIds(OPENED) shouldContainExactly listOf(OPENED)
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a group not yet resolved views the opened entry`(type: ContentType) = runTest {
        host(type, seed = false).state.value.viewedIds(OPENED) shouldContainExactly listOf(OPENED)
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `switching the chip tells the model the flip once`(type: ContentType) = runTest {
        val host = host(type)
        host.selectSource(SIBLING)
        flips shouldContainExactly listOf(null to SIBLING)
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `the model hears of the flip before the list does`(type: ContentType) = runTest {
        val host = host(type)
        host.selectSource(SIBLING)
        selectedDuringFlip shouldBe null
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `picking the chip already shown tells the model nothing`(type: ContentType) = runTest {
        val host = host(type)
        host.selectSource(null)
        flips shouldBe emptyList()
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a refused chip tells the model nothing`(type: ContentType) = runTest {
        val host = host(type)
        host.selectSource(OUTSIDER)
        flips shouldBe emptyList()
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `Clear downloads names the chip it deletes`(type: ContentType) = runTest {
        val host = host(type, observed = true)
        host.observe(backgroundScope)
        host.chips.first { it.size == 2 }
        host.selectSource(SIBLING)
        host.clearDownloadsTarget(OPENED) shouldBe ClearDownloadsTarget("src$SIBLING", listOf(SIBLING))
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `Clear downloads under All names no source and deletes the group`(type: ContentType) = runTest {
        host(type).clearDownloadsTarget(OPENED) shouldBe ClearDownloadsTarget(null, listOf(OPENED, SIBLING))
    }

    private companion object {
        const val OPENED = 1L
        const val SIBLING = 2L
        const val OUTSIDER = 9L
        const val GROUP = 10L
        const val UNSET = -1L
    }
}
