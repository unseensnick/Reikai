package reikai.data.library

import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import eu.kanade.tachiyomi.data.library.MetadataUpdateWorker
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class MetadataUpdateWorkerStopTest {

    /** Cancel on the shared progress notice reaches this, and a name of its own would stop nothing. */
    @Test
    fun `stop cancels the work startNow enqueued`() = runTest {
        val started = slot<String>()
        val workManager = mockk<WorkManager>(relaxed = true) {
            every { getWorkInfosByTagFlow(any()) } returns flowOf(emptyList())
            every { enqueueUniqueWork(capture(started), any(), any<OneTimeWorkRequest>()) } returns mockk()
        }
        MetadataUpdateWorker.startNow(workManager)

        MetadataUpdateWorker.stop(workManager)

        verify { workManager.cancelUniqueWork(started.captured) }
    }
}
