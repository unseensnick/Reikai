package reikai.presentation.novel.details

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.presentation.details.EntryMergeSource
import reikai.presentation.reader.DiskProbeGate
import reikai.presentation.reader.NovelReaderViewModelHarness
import java.util.concurrent.TimeUnit

/**
 * A chapter list names the group it was built for. The group moves on while a rebuild is in flight, and
 * a list that then took the chips or the chip picked from the screen's live group would show one
 * group's rows under another's switcher. Each case holds a rebuild at its disk probe, moves the group
 * on, and reads what that rebuild wrote.
 */
class NovelDetailsGroupSnapshotTest {

    @BeforeEach
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a list built before a merge does not show the merged group's chips`() = runTest {
        heldRebuild(mergedAtOpen = false) { harness, _, leading, other ->
            harness.pluginLoads.drainPermits()
            harness.merge(leading, other)
            // The chips are named right after the plugin host load they wait on.
            check(harness.pluginLoads.tryAcquire(10, TimeUnit.SECONDS)) { "The merged group never resolved" }
            delay(100)
            1
        }.mergeSources shouldBe emptyList<EntryMergeSource>()
    }

    @Test
    fun `a list built under All does not show a chip picked after it`() = runTest {
        heldRebuild(mergedAtOpen = true) { _, model, leading, _ ->
            model.selectSource(leading)
            2
        }.selectedSourceNovelId shouldBe null
    }

    /**
     * Opens the leading novel of two, holds a rebuild of its list at the disk probe, runs [moveOn], and
     * answers the state that rebuild went on to write. [moveOn] returns how many probes the held rebuild
     * still makes: one per novel whose chapters it pooled.
     */
    private suspend fun TestScope.heldRebuild(
        mergedAtOpen: Boolean,
        moveOn: suspend (NovelReaderViewModelHarness, NovelDetailsViewModel, Long, Long) -> Int,
    ): NovelDetailsState.Loaded = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
        val leading = harness.novel(harness.source("alpha"))
        val other = harness.novel(harness.source("beta"))
        harness.chapter(leading, 6.0)
        harness.chapter(other, 6.0)
        if (mergedAtOpen) harness.merge(leading, other)
        val model = harness.openDetails(leading)
        withContext(Dispatchers.Default) {
            withTimeout(30_000) {
                model.state.first {
                    it is NovelDetailsState.Loaded && it.chapters.size == 1 &&
                        it.mergeSources.size == if (mergedAtOpen) 2 else 0
                }
                val gate: DiskProbeGate = harness.holdDiskProbes()
                // Any input starts a rebuild; this one leaves the group alone.
                model.toggleShowHidden()
                gate.awaitArrived()
                val probesLeft = moveOn(harness, model, leading, other)
                gate.admit(probesLeft)
                // The next rebuild starts only once the held one has written, and stops at the gate too.
                gate.awaitArrived(probesLeft)
                model.state.value as NovelDetailsState.Loaded
            }
        }
    }
}
