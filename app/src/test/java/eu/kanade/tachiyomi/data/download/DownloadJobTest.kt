package eu.kanade.tachiyomi.data.download

import eu.kanade.tachiyomi.data.download.DownloadWorkerFixture.Companion.OFFLINE
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * How Mihon's worker runs the downloader through a network wait. Waiting it out and fetching again is
 * the rule both engines share, pinned by NetworkWaitConformanceTest; this is the manga worker's own part.
 */
class DownloadJobTest {

    private fun workerTest(body: suspend TestScope.(DownloadWorkerFixture) -> Unit) = runTest {
        DownloadWorkerFixture(this).use { body(it) }
    }

    @Test
    fun `a connection lost mid-download pauses the downloader`() = workerTest { f ->
        f.queue()
        f.startWorker()
        tick()

        f.network = OFFLINE
        tick()

        f.downloader.isRunning shouldBe false
    }

    /** A reorder pauses and restarts the downloader itself; the worker restarts only what it stopped. */
    @Test
    fun `the worker leaves a downloader paused by someone else to them`() = workerTest { f ->
        f.queue()
        f.startWorker()
        tick()

        f.downloader.pause()
        tick()

        f.downloader.isRunning shouldBe false
    }

    @Test
    fun `a pause during the wait for a connection holds when it returns`() = workerTest { f ->
        f.queue()
        f.network = OFFLINE
        f.startWorker()
        tick()
        f.downloadManager.pauseDownloads()

        f.network = DownloadWorkerFixture.ONLINE
        tick()

        f.downloader.isRunning shouldBe false
    }

    @Test
    fun `a pause during the wait for a connection ends the worker`() = workerTest { f ->
        f.queue()
        f.network = OFFLINE
        val worker = f.startWorker()
        tick()
        f.downloadManager.pauseDownloads()

        tick()

        worker.isCompleted shouldBe true
    }

    @Test
    fun `emptying the queue during the wait for a connection ends the worker`() = workerTest { f ->
        f.queue()
        f.network = OFFLINE
        val worker = f.startWorker()
        tick()
        f.downloadManager.clearQueue()

        tick()

        worker.isCompleted shouldBe true
    }

    @Test
    fun `a pause while downloading ends the worker`() = workerTest { f ->
        f.queue()
        val worker = f.startWorker()
        tick()
        f.downloadManager.pauseDownloads()

        tick()

        worker.isCompleted shouldBe true
    }

    @Test
    fun `a resume after a restart waits for the restored queue`() = workerTest { f ->
        f.downloader.queueState
        f.startWorker()
        tick()

        f.restored.complete(listOf(f.restoredDownload()))
        tick()

        f.downloader.isRunning shouldBe true
    }

    @Test
    fun `a queue started offline shows the paused notice over the worker's own`() = workerTest { f ->
        f.queue()
        f.network = OFFLINE
        f.events.clear()

        f.startWorker()
        tick()

        f.events shouldBe listOf("foreground", "paused")
    }

    private fun TestScope.tick() {
        advanceTimeBy(TICK_MS)
        runCurrent()
    }

    private companion object {
        // Past the worker's one-second network poll.
        const val TICK_MS = 2_000L
    }
}
