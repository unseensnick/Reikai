package reikai.data.task

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class AppTaskProgressTest {

    private fun banner(vararg running: Pair<AppTask, TaskCount>, updating: Boolean = true, restoring: Boolean = true) =
        bannerTask(running.toMap(), showUpdating = updating, showRestoring = restoring)

    @Test
    fun `the manga and novel updates show as one percent`() {
        banner(AppTask.MANGA_UPDATE to TaskCount(1, 4), AppTask.NOVEL_UPDATE to TaskCount(3, 4)) shouldBe
            BannerTask.Updating(0.5f)
    }

    @Test
    fun `an update shows before a restore`() {
        banner(AppTask.RESTORE to TaskCount(1, 2), AppTask.NOVEL_UPDATE to TaskCount(1, 4)) shouldBe
            BannerTask.Updating(0.25f)
    }

    @Test
    fun `a restore shows its own percent`() {
        banner(AppTask.RESTORE to TaskCount(1, 2)) shouldBe BannerTask.Restoring(0.5f)
    }

    @Test
    fun `a job that has not counted its work shows no percent`() {
        banner(AppTask.MANGA_UPDATE to TaskCount(0, 0)) shouldBe BannerTask.Updating(null)
    }

    @Test
    fun `a hidden update gives way to a restore`() {
        banner(AppTask.MANGA_UPDATE to TaskCount(1, 4), AppTask.RESTORE to TaskCount(1, 2), updating = false) shouldBe
            BannerTask.Restoring(0.5f)
    }

    @Test
    fun `a hidden restore shows nothing`() {
        banner(AppTask.RESTORE to TaskCount(1, 2), restoring = false) shouldBe null
    }

    @Test
    fun `a report after the job ends is ignored`() = runTest {
        val progress = AppTaskProgress()
        progress.during(AppTask.RESTORE) {}

        progress.report(AppTask.RESTORE, 5, 10)

        progress.running.value shouldBe emptyMap()
    }

    @Test
    fun `a report during the job is kept`() = runTest {
        val progress = AppTaskProgress()

        progress.during(AppTask.RESTORE) {
            progress.report(AppTask.RESTORE, 5, 10)
            progress.running.value shouldBe mapOf(AppTask.RESTORE to TaskCount(5, 10))
        }
    }

    @Test
    fun `a job shorter than the delay never shows`() = runTest {
        val progress = AppTaskProgress()
        val shown = mutableListOf<BannerTask?>()
        val collector = launch { progress.banner(flowOf(true), flowOf(true)).toList(shown) }
        runCurrent()

        progress.during(AppTask.MANGA_UPDATE) { advanceTimeBy(SHOW_DELAY_MS / 2) }
        advanceTimeBy(SHOW_DELAY_MS * 2)
        collector.cancel()

        shown shouldBe listOf(null)
    }

    @Test
    fun `a longer job shows once the delay passes`() = runTest {
        val progress = AppTaskProgress()
        val shown = mutableListOf<BannerTask?>()
        val collector = launch { progress.banner(flowOf(true), flowOf(true)).toList(shown) }
        val release = CompletableDeferred<Unit>()
        val job = launch { progress.during(AppTask.MANGA_UPDATE) { release.await() } }
        runCurrent()

        advanceTimeBy(SHOW_DELAY_MS + 1)
        release.complete(Unit)
        job.join()
        runCurrent()
        collector.cancel()

        shown shouldBe listOf(BannerTask.Updating(null), null)
    }
}
