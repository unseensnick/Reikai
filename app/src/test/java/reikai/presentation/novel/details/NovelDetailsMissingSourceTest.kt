package reikai.presentation.novel.details

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.novel.LnSourceIdentity
import reikai.presentation.details.EntrySourceState
import reikai.presentation.reader.FakeNovelSource
import reikai.presentation.reader.NovelReaderViewModelHarness

/** A library novel whose plugin is gone reads as a manga with a missing extension does. */
class NovelDetailsMissingSourceTest {

    @BeforeEach
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a novel whose plugin is uninstalled is marked missing once looked up`() = runTest {
        val state = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val novel = harness.novel(FakeNovelSource("gone", "unregistered"))
            harness.chapter(novel, 1.0)
            val model = harness.openDetails(novel)
            withContext(Dispatchers.Default) {
                withTimeout(30_000) {
                    model.state.first { it is NovelDetailsState.Loaded && it.sourceState == EntrySourceState.Missing }
                }
            }
        }

        (state as NovelDetailsState.Loaded).sourceState shouldBe EntrySourceState.Missing
    }

    @Test
    fun `a novel whose plugin is uninstalled is named as it was last seen`() = runTest {
        val name = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            harness.novelPreferences.seenNovelSources().set(mapOf("gone" to LnSourceIdentity(name = "Old Site")))
            val novel = harness.novel(FakeNovelSource("gone", "unregistered"))
            harness.chapter(novel, 1.0)
            val model = harness.openDetails(novel)
            withContext(Dispatchers.Default) {
                withTimeout(30_000) {
                    model.state.first { it is NovelDetailsState.Loaded && it.sourceState == EntrySourceState.Missing }
                        .let { (it as NovelDetailsState.Loaded).sourceName }
                }
            }
        }

        name shouldBe "Old Site"
    }

    /** On a device the page loads from the database before the plugin host answers, the reverse of above. */
    @Test
    fun `a page already shown is marked missing when the plugin lookup lands`() = runTest {
        val state = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val gate = harness.holdPluginLoads()
            // Only the first list build is let through, so no later one can repaint the page.
            val probes = harness.holdDiskProbes()
            val novel = harness.novel(FakeNovelSource("gone", "unregistered"))
            harness.chapter(novel, 1.0)
            val model = harness.openDetails(novel)
            withContext(Dispatchers.Default) {
                withTimeout(30_000) {
                    probes.awaitArrived()
                    probes.admit()
                    model.state.first { it is NovelDetailsState.Loaded }
                }
                gate.complete(Unit)
                // Inside the gate's give-up time, so a held rebuild escaping it cannot be what marks it.
                withTimeout(5_000) {
                    model.state.first { it is NovelDetailsState.Loaded && it.sourceState == EntrySourceState.Missing }
                }
            }
        }

        (state as NovelDetailsState.Loaded).sourceState shouldBe EntrySourceState.Missing
    }

    @Test
    fun `a page held before the lookup does not show the plugin as missing`() = runTest {
        val state = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            harness.holdPluginLoads()
            val novel = harness.novel(FakeNovelSource("gone", "unregistered"))
            harness.chapter(novel, 1.0)
            val model = harness.openDetails(novel)
            withContext(Dispatchers.Default) {
                withTimeout(30_000) { model.state.first { it is NovelDetailsState.Loaded } }
            }
        }

        (state as NovelDetailsState.Loaded).sourceState shouldBe EntrySourceState.Installed
    }

    @Test
    fun `a novel whose plugin is installed is not marked missing`() = runTest {
        val state = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val novel = harness.novel(harness.source("alpha"))
            harness.chapter(novel, 1.0)
            val model = harness.openDetails(novel)
            withContext(Dispatchers.Default) {
                withTimeout(30_000) {
                    model.state.first { it is NovelDetailsState.Loaded && it.sourceName == "alpha" }
                }
            }
        }

        (state as NovelDetailsState.Loaded).sourceState shouldBe EntrySourceState.Installed
    }
}
