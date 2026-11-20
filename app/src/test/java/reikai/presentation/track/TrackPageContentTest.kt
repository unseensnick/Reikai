package reikai.presentation.track

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.presentation.ComposeTestHost

/** The track sheet's picker and remove pages, drawn only over a binding they have read. */
@RunWith(RobolectricTestRunner::class)
class TrackPageContentTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a page whose binding is gone pops`() {
        var popped = false
        compose.setContent {
            ComposeTestHost { TrackPageContent(TrackPage.Gone, onGone = { popped = true }) { Text("Confirm") } }
        }
        compose.waitForIdle()

        popped shouldBe true
    }

    @Test
    fun `a page still reading its binding offers nothing to confirm`() {
        compose.setContent {
            ComposeTestHost { TrackPageContent(TrackPage.Loading, onGone = {}) { Text("Confirm") } }
        }

        compose.onNodeWithText("Confirm").assertDoesNotExist()
    }
}
