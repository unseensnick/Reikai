package reikai.presentation.reader

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.util.resetVirtualDispatchers
import reikai.util.virtualDispatchers
import java.util.concurrent.ConcurrentHashMap

/**
 * Opening a chapter takes its queued download out of the queue, and closing the reader puts it back. A
 * skip that marks the chapter left read finishes that one, never the one opened, so it must not drop it.
 */
class MangaQueuedDownloadRestoreTest {

    @BeforeEach
    fun setUp() {
        virtualDispatchers()
    }

    @AfterEach
    fun tearDown() = resetVirtualDispatchers()

    @Test
    fun `a Next that marks the chapter left read still puts the opened chapter's download back`() = runTest {
        val requeued = ConcurrentHashMap.newKeySet<Long>()
        MangaReaderViewModelHarness.create().use { harness ->
            val manga = harness.manga(1L, source = 100L, title = "Series")
            harness.chapter(50L, manga, 5.0)
            harness.chapter(60L, manga, 6.0)
            harness.open(
                manga,
                chapterId = 50L,
                preferences = mapOf("mark_read_on_skip" to true, "remove_after_read_slots" to 0),
                queuedDownloads = setOf(60L),
                onRequeue = { requeued += it },
            ) { model, _ ->
                harness.settle(model) { model.loadNextChapter() }
            }
            // The close puts the download back on the app scope, after the model it would settle is gone.
            advanceUntilIdle()
        }
        requeued.toSet() shouldBe setOf(60L)
    }
}
