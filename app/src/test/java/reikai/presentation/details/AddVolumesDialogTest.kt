package reikai.presentation.details

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.data.local.CombinableSeries
import reikai.presentation.ComposeTestHost

/** Add volumes is picked from the series that receives the books, so it hands over every series ticked. */
@RunWith(RobolectricTestRunner::class)
class AddVolumesDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private val others = listOf(CombinableSeries(2, "Vol 2", "b"), CombinableSeries(3, "Vol 3", "c"))
    private var confirmed: List<CombinableSeries>? = null

    private fun show() {
        compose.setContent {
            ComposeTestHost {
                AddVolumesDialog(title = "Saga", load = {
                    others
                }, canOrganize = false, onDismissRequest = {}, onConfirm = { picked, _ -> confirmed = picked })
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("add:2").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `every ticked series moves in`() {
        show()

        compose.onNodeWithTag("add:2").performClick()
        compose.onNodeWithTag("add:3").performClick()
        compose.onNodeWithText("Add").performClick()

        confirmed shouldBe others
    }

    @Test
    fun `a search lists only the series whose title or folder matches`() {
        show()

        compose.onNodeWithText("Search").performTextInput("c")

        (tagged("add:2") to tagged("add:3")) shouldBe (false to true)
    }

    @Test
    fun `a series ticked before a search is still added once the search hides it`() {
        show()
        compose.onNodeWithTag("add:2").performClick()

        compose.onNodeWithText("Search").performTextInput("Vol 3")
        compose.onNodeWithTag("add:3").performClick()
        compose.onNodeWithText("Add").performClick()

        confirmed shouldBe others
    }

    private fun tagged(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `nothing can be added before a series is ticked`() {
        show()

        compose.onNodeWithText("Add").assertIsNotEnabled()
    }
}
