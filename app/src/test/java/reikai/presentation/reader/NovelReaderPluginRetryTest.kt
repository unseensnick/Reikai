package reikai.presentation.reader

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.util.resetVirtualDispatchers
import reikai.util.virtualDispatchers

/** The reader's Retry on a chapter whose plugin failed to load, as the details page's Retry does. */
class NovelReaderPluginRetryTest {

    @BeforeEach
    fun setUp() {
        virtualDispatchers()
    }

    @AfterEach
    fun tearDown() = resetVirtualDispatchers()

    @Test
    fun `retry loads a plugin that failed to load and opens the chapter`() = runTest {
        val loaded = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val novel = harness.novel(FakeNovelSource(PLUGIN, PLUGIN))
            val model = harness.open(novel, harness.chapter(novel, 1.0).id)
            advanceUntilIdle()
            harness.onPluginRetry = { id -> if (id == PLUGIN) harness.source(PLUGIN) }

            model.retryLoad()
            advanceUntilIdle()

            model.loadState.value
        }

        loaded shouldBe ReaderLoadState.Idle
    }

    /** The engine retries a failed pick as a pick, so the pick is what has to load the plugin again. */
    @Test
    fun `a pick loads a plugin that failed to load and opens the chapter`() = runTest {
        val opened = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val novel = harness.novel(FakeNovelSource(PLUGIN, PLUGIN))
            val first = harness.chapter(novel, 1.0)
            val picked = harness.chapter(novel, 2.0)
            // On disk, so the session opens without the plugin.
            harness.download(first, "<p>On disk</p>")
            val model = harness.open(novel, first.id)
            advanceUntilIdle()
            harness.onPluginRetry = { id -> if (id == PLUGIN) harness.source(PLUGIN) }

            model.open(picked.id)
            advanceUntilIdle()

            model.chapter.value?.chapterId == picked.id
        }

        opened shouldBe true
    }

    @Test
    fun `an edge Retry loads a plugin that failed to load and joins the chapter`() = runTest {
        val joined = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val novel = harness.novel(FakeNovelSource(PLUGIN, PLUGIN))
            val first = harness.chapter(novel, 1.0)
            val next = harness.chapter(novel, 2.0)
            harness.download(first, "<p>On disk</p>")
            val model = harness.open(novel, first.id)
            advanceUntilIdle()
            model.rendererLanded(model.window.value.generation)
            // A chapter that fits on screen brings the next one's edge, and so its failure, into reach.
            model.reportFitsOnScreen(first.id, fits = true)
            runCurrent()
            checkNotNull(model.window.value.failedNext) { "the next chapter's failure is not drawn" }
            harness.onPluginRetry = { id -> if (id == PLUGIN) harness.source(PLUGIN) }

            model.retryBoundary(forward = true)
            advanceUntilIdle()

            model.window.value.chapters.any { it.chapterId == next.id }
        }

        joined shouldBe true
    }

    private companion object {
        const val PLUGIN = "late"
    }
}
