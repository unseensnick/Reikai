package reikai.presentation.backup

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import reikai.data.backup.BackupRepo
import reikai.data.backup.RestoreInstallPlan
import reikai.domain.extension.NO_SIGNING_KEY
import reikai.presentation.ComposeTestHost

/**
 * The Restore screen's repo cards: a store's key shown as the Repositories screen shows it, a plugin repo
 * named as one, and each card's trust box answering for its own repo, since a mis-wired box would trust
 * a repo the user declined.
 */
@RunWith(RobolectricTestRunner::class)
// Tall enough that both cards compose, since the list only composes what is on screen.
@Config(qualifiers = "w411dp-h1600dp")
class RestoreInstallItemsTest {

    @get:Rule
    val compose = createComposeRule()

    private val trustChanges = mutableListOf<Pair<String, Boolean>>()

    private fun show(
        repos: List<BackupRepo> = listOf(store("abc")),
        offerRepos: Boolean = true,
        trusted: Set<String> = emptySet(),
    ) = compose.setContent {
        ComposeTestHost {
            LazyColumn {
                restoreInstallItems(
                    plan = RestoreInstallPlan(repos = repos),
                    shouldInstall = true,
                    offerRepos = offerRepos,
                    trusted = trusted,
                    onTrust = { repo, trust -> trustChanges += repo.url to trust },
                )
            }
        }
    }

    @Test
    fun `a keyless store's card shows no signing key`() {
        show(listOf(store(NO_SIGNING_KEY)))

        compose.onNodeWithText("Signing key").assertDoesNotExist()
    }

    @Test
    fun `with Extension repos off the backup's repos are not offered`() {
        show(offerRepos = false)

        compose.onNodeWithText("Store").assertDoesNotExist()
    }

    @Test
    fun `a store's card shows its signing key`() {
        show()

        compose.onNodeWithText("abc").assertIsDisplayed()
    }

    @Test
    fun `a novel plugin repo's card says it is a plugin repo`() {
        show(listOf(BackupRepo(PLUGIN_URL, "Plugins", isPluginRepo = true, signingKey = null)))

        compose.onNodeWithText("Plugin repo").assertIsDisplayed()
    }

    @Test
    fun `ticking a card's trust box trusts that card's repo`() {
        show(listOf(store("abc"), BackupRepo(PLUGIN_URL, "Plugins", isPluginRepo = true, signingKey = null)))

        compose.onAllNodesWithText("Trust this repo")[1].performClick()

        trustChanges shouldBe listOf(PLUGIN_URL to true)
    }

    @Test
    fun `unticking a trusted repo's box withdraws its trust`() {
        show(trusted = setOf(STORE_URL))

        compose.onNodeWithText("Trust this repo").performClick()

        trustChanges shouldBe listOf(STORE_URL to false)
    }

    private fun store(signingKey: String) = BackupRepo(STORE_URL, "Store", false, signingKey)

    private companion object {
        const val STORE_URL = "https://store.example/index.min.json"
        const val PLUGIN_URL = "https://plugins.example/plugins.min.json"
    }
}
