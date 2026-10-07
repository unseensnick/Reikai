package reikai.data.library

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class EntryCheckTest {

    private val cleared = mutableListOf<Unit>()
    private val recorded = mutableListOf<String>()
    private val checked = mutableListOf<Unit>()

    private suspend fun step(
        stillInLibrary: Boolean = true,
        trackErrors: Boolean = true,
        clearError: suspend () -> Unit = { cleared += Unit },
        check: suspend () -> Unit = { checked += Unit },
    ): EntryCheck = checkUpdateEntry(
        stillInLibrary = { stillInLibrary },
        progress = { it() },
        trackErrors = trackErrors,
        clearError = clearError,
        recordError = { recorded += it },
        failureMessage = { it.message ?: "Unknown" },
        check = check,
    )

    /** Starts a step whose check never finishes, then cancels the run around it; null when it never returned. */
    private fun TestScope.cancelRunMidCheck(): EntryCheck? {
        var outcome: EntryCheck? = null
        val run = launch { outcome = step(check = { awaitCancellation() }) }
        runCurrent()
        run.cancel()
        runCurrent()
        return outcome
    }

    @Test
    fun `an entry that left the library is skipped without being checked`() = runTest {
        step(stillInLibrary = false)
        checked shouldBe emptyList()
    }

    @Test
    fun `an entry that left the library reads as skipped`() = runTest {
        step(stillInLibrary = false) shouldBe EntryCheck.Skipped
    }

    @Test
    fun `a successful check clears the recorded error`() = runTest {
        step()
        cleared shouldBe listOf(Unit)
    }

    @Test
    fun `a failed check records its message`() = runTest {
        step(check = { error("Timeout") })
        recorded shouldBe listOf("Timeout")
    }

    @Test
    fun `a failed check reads as failed with its message`() = runTest {
        step(check = { error("Timeout") }) shouldBe EntryCheck.Failed("Timeout")
    }

    @Test
    fun `a cancellation a source throws while the run goes on is that entry's failure`() = runTest {
        step(check = { throw CancellationException("Timed out") }) shouldBe EntryCheck.Failed("Timed out")
    }

    @Test
    fun `a run cancelled during a check gives no outcome`() = runTest {
        cancelRunMidCheck() shouldBe null
    }

    @Test
    fun `a run cancelled during a check records nothing`() = runTest {
        cancelRunMidCheck()
        recorded shouldBe emptyList()
    }

    @Test
    fun `with tracking off a failure is not recorded`() = runTest {
        step(trackErrors = false, check = { error("Timeout") })
        recorded shouldBe emptyList()
    }

    @Test
    fun `with tracking off a success clears nothing`() = runTest {
        step(trackErrors = false)
        cleared shouldBe emptyList()
    }

    @Test
    fun `a failing clear does not fail the entry`() = runTest {
        step(clearError = { error("database closed") }) shouldBe EntryCheck.Checked
    }
}
