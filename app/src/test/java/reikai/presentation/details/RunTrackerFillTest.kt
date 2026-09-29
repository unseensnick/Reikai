package reikai.presentation.details

import eu.kanade.tachiyomi.data.track.Tracker
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.data.track.MetadataAccess

/** One "Fill from tracker" fetch: when it runs at all, and what it reports. */
class RunTrackerFillTest {

    private fun tracker(access: MetadataAccess = MetadataAccess.SignedIn, loggedIn: Boolean = true) =
        mockk<Tracker> {
            every { metadataAccess } returns access
            every { isLoggedIn } returns loggedIn
            every { name } returns "Kitsu"
        }

    @Test
    fun `a fill cancelled mid-fetch reports no failure`() = runTest {
        var reported: Throwable? = null
        val job = launch {
            runTrackerFill(tracker(), fetch = { awaitCancellation() }, onFilled = {}, onFailed = { reported = it })
        }
        runCurrent()
        job.cancel()
        job.join()
        reported shouldBe null
    }

    @Test
    fun `a fetch that fails reports its error`() = runTest {
        val error = IllegalStateException("down")
        var reported: Throwable? = null
        runTrackerFill(tracker(), fetch = { throw error }, onFilled = {}, onFailed = { reported = it })
        reported shouldBe error
    }

    @Test
    fun `a signed-out tracker whose metadata needs a login reads as signed out`() = runTest {
        var reported: Throwable? = null
        runTrackerFill(tracker(loggedIn = false), fetch = { "filled" }, onFilled = {}, onFailed = { reported = it })
        trackerAutofillError(reported!!) shouldBe TrackerAutofillError.SignedOut
    }

    @Test
    fun `a signed-out tracker whose metadata needs a login fills nothing`() = runTest {
        var filled: String? = null
        runTrackerFill(tracker(loggedIn = false), fetch = { "filled" }, onFilled = { filled = it }, onFailed = {})
        filled shouldBe null
    }

    @Test
    fun `a public tracker fills while signed out`() = runTest {
        var filled: String? = null
        runTrackerFill(
            tracker(MetadataAccess.Public, loggedIn = false),
            fetch = { "filled" },
            onFilled = { filled = it },
            onFailed = {},
        )
        filled shouldBe "filled"
    }
}
