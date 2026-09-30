package reikai.domain.track

import androidx.work.ListenableWorker
import eu.kanade.domain.track.store.DelayedTrackingStore.DelayedTrackingItem
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The one drain both delayed-tracking jobs run, the manga job over Mihon's queue file and the novel
 * job over its own, so the queue rules are pinned once for both content types.
 */
class DelayedTrackingDrainTest {

    private class Queue(vararg items: Pair<Long, Float>) {
        val items = items.associate { it }.toMutableMap()
        val pushed = mutableListOf<Pair<String, Double>>()

        suspend fun drain(runAttemptCount: Int = 0, tracks: Map<Long, String>) = drainDelayedTracking(
            runAttemptCount = runAttemptCount,
            items = { items.map { DelayedTrackingItem(it.key, it.value) } },
            remove = { items.remove(it) },
            trackOf = { tracks[it] },
            push = { track, chapter -> pushed += track to chapter },
        )
    }

    @Test
    fun `a queued id whose track is gone is dropped from the queue`() = runTest {
        val queue = Queue(GONE to 3f)

        queue.drain(tracks = emptyMap())

        queue.items.keys shouldBe emptySet()
    }

    @Test
    fun `a queued id whose track is gone is not pushed`() = runTest {
        val queue = Queue(GONE to 3f)

        queue.drain(tracks = emptyMap())

        queue.pushed shouldBe emptyList()
    }

    @Test
    fun `a queued track is pushed with its queued chapter`() = runTest {
        val queue = Queue(TRACKED to 12.5f)

        queue.drain(tracks = mapOf(TRACKED to "series"))

        queue.pushed shouldBe listOf("series" to 12.5)
    }

    @Test
    fun `the work succeeds once the queue is empty`() = runTest {
        Queue(GONE to 3f).drain(tracks = emptyMap()) shouldBe ListenableWorker.Result.success()
    }

    @Test
    fun `the work retries while a push left its item queued`() = runTest {
        Queue(TRACKED to 1f).drain(tracks = mapOf(TRACKED to "series")) shouldBe ListenableWorker.Result.retry()
    }

    @Test
    fun `the work still drains on its fourth run`() = runTest {
        val queue = Queue(TRACKED to 1f)

        queue.drain(runAttemptCount = 3, tracks = mapOf(TRACKED to "series"))

        queue.pushed shouldBe listOf("series" to 1.0)
    }

    @Test
    fun `the work gives up once it has already run four times`() = runTest {
        Queue(TRACKED to 1f).drain(runAttemptCount = 4, tracks = mapOf(TRACKED to "series")) shouldBe
            ListenableWorker.Result.failure()
    }

    private companion object {
        const val GONE = 1L
        const val TRACKED = 2L
    }
}
