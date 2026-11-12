package reikai.presentation.details

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.presentation.ComposeTestHost

/**
 * A series refused for an organize no retry can resolve says so above its list, and forgetting it is asked first,
 * with the files left as they are.
 */
@RunWith(RobolectricTestRunner::class)
class UnfinishedOrganizeViewsTest {

    @get:Rule
    val compose = createComposeRule()

    private var asked = false
    private var forgot = false

    @Test
    fun `the banner offers to forget the unfinished organize`() {
        compose.setContent { ComposeTestHost { LazyColumn { unfinishedOrganizeItem { asked = true } } } }

        compose.onNodeWithText("An unfinished organize holds this series").performClick()

        asked shouldBe true
    }

    @Test
    fun `no banner shows while nothing waits`() {
        compose.setContent { ComposeTestHost { LazyColumn { unfinishedOrganizeItem(null) } } }

        compose.onNodeWithText("An unfinished organize holds this series").assertDoesNotExist()
    }

    @Test
    fun `the dialog says the files are left as they are`() {
        compose.setContent { ComposeTestHost { ForgetUnfinishedDialog(onDismissRequest = {}, onConfirm = {}) } }

        compose.onNodeWithText("Files are left as they are, and the next refresh lists what is there").assertExists()
    }

    @Test
    fun `the organize is forgotten only on Forget`() {
        compose.setContent {
            ComposeTestHost { ForgetUnfinishedDialog(onDismissRequest = {}, onConfirm = { forgot = true }) }
        }

        compose.onNodeWithText("Forget").performClick()

        forgot shouldBe true
    }

    @Test
    fun `cancelling forgets nothing`() {
        compose.setContent {
            ComposeTestHost { ForgetUnfinishedDialog(onDismissRequest = {}, onConfirm = { forgot = true }) }
        }

        compose.onNodeWithText("Cancel").performClick()

        forgot shouldBe false
    }
}
