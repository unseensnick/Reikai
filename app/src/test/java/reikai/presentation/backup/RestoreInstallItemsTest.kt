package reikai.presentation.backup

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.data.backup.BackupRepo
import reikai.data.backup.RestoreInstallPlan
import reikai.domain.extension.NO_SIGNING_KEY
import reikai.presentation.ComposeTestHost

/** The Restore screen's repo card shows a store's key as the Repositories screen does. */
@RunWith(RobolectricTestRunner::class)
class RestoreInstallItemsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(signingKey: String, offerRepos: Boolean = true) = compose.setContent {
        ComposeTestHost {
            LazyColumn {
                restoreInstallItems(
                    plan = RestoreInstallPlan(
                        repos = listOf(BackupRepo("https://store.example/index.min.json", "Store", false, signingKey)),
                    ),
                    shouldInstall = true,
                    offerRepos = offerRepos,
                    trusted = emptySet(),
                    onTrust = { _, _ -> },
                )
            }
        }
    }

    @Test
    fun `a keyless store's card shows no signing key`() {
        show(NO_SIGNING_KEY)

        compose.onNodeWithText("Signing key").assertDoesNotExist()
    }

    @Test
    fun `with Extension repos off the backup's repos are not offered`() {
        show("abc", offerRepos = false)

        compose.onNodeWithText("Store").assertDoesNotExist()
    }

    @Test
    fun `a store's card shows its signing key`() {
        show("abc")

        compose.onNodeWithText("abc").assertIsDisplayed()
    }
}
