package reikai.presentation.details

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.presentation.ComposeTestHost

/**
 * A volume whose folder went missing outside the app is named above the list, and nothing is done about it until
 * the user picks: a folder holding its files, any folder they choose, or taking it out of the series.
 */
@RunWith(RobolectricTestRunner::class)
class MissingVolumeDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private val vol3 = EntryChapterListItem.Volume("Saga", "Other/Vol 3", VolumeLabel.Number(3.0), listOf(1L), 0)
    private val missing = MissingVolume("Other", vol3)
    private var linked: String? = null
    private var removed = false

    private fun dialog() = compose.setContent {
        ComposeTestHost {
            MissingVolumeDialog(
                missing = missing,
                candidates = { listOf("Other renamed") },
                choices = { listOf("Anything", "Other renamed") },
                onDismissRequest = {},
                onLink = { linked = it },
                onRemove = { removed = true },
            )
        }
    }

    private fun waitFor(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `the banner names the missing volume by its label`() {
        compose.setContent { ComposeTestHost { LazyColumn { missingVolumesItem(listOf(missing)) {} } } }

        compose.onNodeWithText("Vol. 3's folder is missing").assertExists()
    }

    @Test
    fun `a folder holding its files is linked only once picked`() {
        dialog()
        waitFor("Other renamed")

        compose.onNodeWithText("Other renamed").performClick()
        compose.onNodeWithText("Link").performClick()

        linked shouldBe "Other renamed"
    }

    @Test
    fun `any folder the user chooses can be linked`() {
        dialog()

        compose.onNodeWithText("Choose folder").performClick()
        waitFor("Anything")
        compose.onNodeWithText("Anything").performClick()
        compose.onNodeWithText("Link").performClick()

        linked shouldBe "Anything"
    }

    @Test
    fun `the volume can be taken out of the series instead`() {
        dialog()

        compose.onNodeWithText("Remove from series").performClick()

        removed shouldBe true
    }

    @Test
    fun `nothing is linked before the user picks`() {
        dialog()
        waitFor("Other renamed")

        compose.onNodeWithText("Link").performClick()

        linked shouldBe null
    }
}
