package reikai.presentation.details

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Dismissing the edit-info dialog mid-fill cancels the fetch, which is not a tracker error to report. */
class RunTrackerFillTest {

    @Test
    fun `a fill cancelled mid-fetch reports no failure`() = runTest {
        var reported: Throwable? = null
        val job =
            launch { runTrackerFill(fetch = { awaitCancellation() }, onFilled = {}, onFailed = { reported = it }) }
        runCurrent()
        job.cancel()
        job.join()
        reported shouldBe null
    }

    @Test
    fun `a fetch that fails reports its error`() = runTest {
        val error = IllegalStateException("down")
        var reported: Throwable? = null
        runTrackerFill(fetch = { throw error }, onFilled = {}, onFailed = { reported = it })
        reported shouldBe error
    }
}
