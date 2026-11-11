package reikai.presentation.details

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
    fun `nothing can be added before a series is ticked`() {
        show()

        compose.onNodeWithText("Add").assertIsNotEnabled()
    }
}
