package reikai.presentation.details

import eu.kanade.tachiyomi.network.HttpException
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** "Fill from tracker" tells the reader why nothing was filled, never a bare status code or an empty reason. */
class TrackerAutofillErrorTest {

    @Test
    fun `a 404 from the tracker reads as no entry found`() {
        trackerAutofillError(HttpException(404)) shouldBe TrackerAutofillError.NotFound
    }

    @Test
    fun `another status keeps its message`() {
        trackerAutofillError(HttpException(500)) shouldBe TrackerAutofillError.Failed("HTTP error 500")
    }

    @Test
    fun `a failure without a message has no reason to show`() {
        trackerAutofillError(IllegalStateException()) shouldBe TrackerAutofillError.Failed(null)
    }

    @Test
    fun `a blank message counts as none`() {
        trackerAutofillError(IllegalStateException("  ")) shouldBe TrackerAutofillError.Failed(null)
    }
}
