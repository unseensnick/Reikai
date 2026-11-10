package reikai.presentation.reader

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The reader's Retry on a chapter whose plugin failed to load, as the details page's Retry does. */
class NovelReaderPluginRetryTest {

    @BeforeEach
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

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

    private companion object {
        const val PLUGIN = "late"
    }
}
