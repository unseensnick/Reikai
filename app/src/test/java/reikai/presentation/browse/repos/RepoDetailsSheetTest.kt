package reikai.presentation.browse.repos

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.domain.extension.RepoStatus
import reikai.presentation.ComposeTestHost

/** A repo's details sheet, read the way TalkBack reads it. */
@RunWith(RobolectricTestRunner::class)
class RepoDetailsSheetTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the Discord button says what it opens`() {
        compose.setContent {
            ComposeTestHost {
                RepoDetailsSheet(card = CARD, onOpenDiscord = {}, onDismissRequest = {})
            }
        }

        compose.onNodeWithContentDescription("Open Discord").assertHasClickAction()
    }

    private companion object {
        val CARD = RepoCardUi(
            address = "https://example.org/index.min.json",
            format = RepoFormat.STORE,
            name = "Example",
            website = null,
            discord = "https://discord.gg/example",
            signingKey = null,
            status = RepoStatus.Checking,
        )
    }
}
