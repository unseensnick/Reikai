package reikai.data.library

import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import io.kotest.matchers.shouldBe
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.library.ContentType
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_ONLY_ON_WIFI
import java.util.UUID

class LibraryUpdateScheduleTest {

    @ParameterizedTest
    @EnumSource(LibraryUpdateJob::class)
    fun `a hidden type's job finishes as a success without running`(job: LibraryUpdateJob) = runTest {
        val shown = ContentType.entries.first { it != ContentType.ALL && it != job.contentType }

        libraryUpdateGate(job, shown) { true } shouldBe ListenableWorker.Result.success()
    }

    @Test
    fun `a gallery check stops with manga hidden`() = runTest {
        libraryUpdateGate(LibraryUpdateJob.GALLERY, ContentType.NOVELS) { false } shouldBe
            ListenableWorker.Result.success()
    }

    @ParameterizedTest
    @EnumSource(LibraryUpdateJob::class)
    fun `a job goes ahead while both types show`(job: LibraryUpdateJob) = runTest {
        libraryUpdateGate(job, ContentType.ALL) { false } shouldBe null
    }

    @ParameterizedTest
    @EnumSource(LibraryUpdateJob::class)
    fun `a job goes ahead while its own type is the shown one`(job: LibraryUpdateJob) = runTest {
        libraryUpdateGate(job, job.contentType) { false } shouldBe null
    }

    @ParameterizedTest
    @EnumSource(LibraryUpdateJob::class)
    fun `a job that must wait retries`(job: LibraryUpdateJob) = runTest {
        libraryUpdateGate(job, ContentType.ALL) { true } shouldBe ListenableWorker.Result.retry()
    }

    @Test
    fun `a scheduled update waits while a manual one runs`() {
        shouldDeferAutoUpdate(sdkInt = 34, emptySet(), isOnWifi = { true }, isManualRunning = true) shouldBe true
    }

    @Test
    fun `a wifi-only scheduled update below Android 9 waits for wifi`() {
        shouldDeferAutoUpdate(sdkInt = 27, WIFI_ONLY, isOnWifi = { false }, isManualRunning = false) shouldBe true
    }

    @Test
    fun `a wifi-only scheduled update below Android 9 runs on wifi`() {
        shouldDeferAutoUpdate(sdkInt = 27, WIFI_ONLY, isOnWifi = { true }, isManualRunning = false) shouldBe false
    }

    @Test
    fun `from Android 9 a wifi-only scheduled update leaves wifi to the network constraint`() {
        shouldDeferAutoUpdate(sdkInt = 28, WIFI_ONLY, isOnWifi = { false }, isManualRunning = false) shouldBe false
    }

    @Test
    fun `from Android 9 the wifi state is never read`() {
        val unreadable = { error("the Wi-Fi state was read") }

        shouldDeferAutoUpdate(sdkInt = 28, WIFI_ONLY, unreadable, isManualRunning = false) shouldBe false
    }

    @Test
    fun `stopping the scheduled update puts its schedule back`() = runTest {
        var rescheduled = 0

        stopLibraryUpdate(running(AUTO), TAG, AUTO) { rescheduled++ }

        rescheduled shouldBe 1
    }

    @Test
    fun `stopping a pulled update leaves the schedule alone`() = runTest {
        var rescheduled = 0

        stopLibraryUpdate(running(MANUAL), TAG, AUTO) { rescheduled++ }

        rescheduled shouldBe 0
    }

    @Test
    fun `stopping cancels the running update by its id`() = runTest {
        val workManager = running(MANUAL)

        stopLibraryUpdate(workManager, TAG, AUTO) {}

        verify { workManager.cancelWorkById(RUNNING_ID) }
    }

    @Test
    fun `stopping asks only for running work, so the queued schedule is never cancelled`() = runTest {
        val query = slot<WorkQuery>()

        stopLibraryUpdate(running(AUTO, query), TAG, AUTO) {}

        query.captured.states shouldBe listOf(WorkInfo.State.RUNNING)
    }

    /** A work manager whose one running update carries [kind] beside the shared tag. */
    private fun running(kind: String, query: CapturingSlot<WorkQuery> = slot()) = mockk<WorkManager>(relaxed = true) {
        every { getWorkInfosFlow(capture(query)) } returns
            flowOf(listOf(WorkInfo(RUNNING_ID, WorkInfo.State.RUNNING, setOf(TAG, kind))))
    }

    private companion object {
        val WIFI_ONLY = setOf(DEVICE_ONLY_ON_WIFI)
        const val TAG = "LibraryUpdate"
        const val AUTO = "LibraryUpdate-auto"
        const val MANUAL = "LibraryUpdate-manual"
        val RUNNING_ID: UUID = UUID.randomUUID()
    }
}
