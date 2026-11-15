package reikai.presentation.browse.repos

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.presentation.ComposeTestHost

/**
 * A repo at a plain http:// address is allowed, since a local test server has no certificate, but
 * anyone on the network can rewrite what it serves, so the Add dialog says so before it is added.
 */
@RunWith(RobolectricTestRunner::class)
class AddRepoDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(address: String) = compose.setContent {
        ComposeTestHost {
            AddRepoDialog(
                dialog = RepoDialog.Add(fromLink = address),
                addedAddresses = emptySet(),
                onAdd = {},
                onDismissRequest = {},
            )
        }
    }

    @Test
    fun `a plain http address is warned about`() {
        show("http://192.168.1.2:8080/plugins.min.json")

        compose.onNodeWithText(WARNING).assertIsDisplayed()
    }

    @Test
    fun `an https address carries no warning`() {
        show("https://example.org/plugins.min.json")

        compose.onNodeWithText(WARNING).assertDoesNotExist()
    }

    private companion object {
        const val WARNING = "Anyone on your network can change what an http:// address serves"
    }
}
