package reikai.domain.track

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.WorkerParameters
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/** The retry both delayed-tracking workers schedule, pinned once for the manga and the novel queue. */
class DelayedTrackingRequestTest {

    private class QueueWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result = Result.success()
    }

    private val request = delayedTrackingRequest<QueueWorker>(TAG)

    @Test
    fun `the retry waits for a connection`() {
        request.workSpec.constraints.requiredNetworkType shouldBe NetworkType.CONNECTED
    }

    @Test
    fun `the retry backs off exponentially`() {
        request.workSpec.backoffPolicy shouldBe BackoffPolicy.EXPONENTIAL
    }

    @Test
    fun `the first backoff is five minutes`() {
        request.workSpec.backoffDelayDuration shouldBe TimeUnit.MINUTES.toMillis(5)
    }

    @Test
    fun `the retry carries its queue's tag`() {
        request.tags shouldContain TAG
    }

    @Test
    fun `the retry runs the given worker`() {
        request.workSpec.workerClassName shouldBe QueueWorker::class.java.name
    }

    private companion object {
        const val TAG = "SomeDelayedTracking"
    }
}
