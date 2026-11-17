package reikai.presentation.reader

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Opening a chapter takes its queued download out of the queue, and closing the reader puts it back. A
 * skip that marks the chapter left read finishes that one, never the one opened, so it must not drop it.
 */
class MangaQueuedDownloadRestoreTest {

    @BeforeEach
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

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
                // The mark runs on the app scope, off the virtual clock.
                harness.settle(model) { model.loadNextChapter() }
            }
            // The close puts the download back on the app scope, after the model it would settle is gone.
            withContext(Dispatchers.Default) { withTimeoutOrNull(5_000) { while (requeued.isEmpty()) delay(20) } }
        }
        requeued.toSet() shouldBe setOf(60L)
    }
}
