package reikai.presentation.details

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.presentation.ComposeTestHost

/** A series whose folder was renamed is linked only to the folder the user picks, even when only one is offered. */
@RunWith(RobolectricTestRunner::class)
class RelinkSeriesDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private var linked: String? = null
    private var dismissed = false

    private fun dialog() = compose.setContent {
        ComposeTestHost {
            RelinkSeriesDialog("Saga", listOf("Saga renamed"), { dismissed = true }) { linked = it }
        }
    }

    @Test
    fun `the folder the user picks is linked and the dialog closes`() {
        dialog()

        compose.onNodeWithText("Saga renamed").performClick()
        compose.onNodeWithText("Link").performClick()

        (linked to dismissed) shouldBe ("Saga renamed" to true)
    }

    @Test
    fun `a lone folder is not linked before the user picks it`() {
        dialog()

        compose.onNodeWithText("Link").performClick()

        linked shouldBe null
    }
}
