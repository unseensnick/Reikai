package reikai.presentation.details

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import reikai.presentation.ComposeTestHost

/** The shared edit-info dialog, for manga and novels alike, rendered without a details screen. A tall screen
 *  keeps the whole form in view, since the default one would need every case to scroll first. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h1600dp")
class EntryEditInfoDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private var saved: EntryEditInfoUi? = null
    private var organize: Boolean? = null

    private fun show(
        initial: EntryEditInfoUi = INITIAL,
        mergedTags: List<String>? = null,
        canRenameFiles: Boolean = false,
    ) = compose.setContent {
        ComposeTestHost {
            EntryEditInfoDialog(
                initial = initial,
                source = SOURCE,
                coverModel = { null },
                onDismissRequest = {},
                onSave = { info, renameFiles ->
                    saved = info
                    organize = renameFiles
                },
                onResetAll = {},
                autofill = TrackerAutofill(candidates = { emptyList() }, fetch = { _, _ -> error("unused") }),
                mergedTags = { mergedTags },
                canRenameFiles = canRenameFiles,
            )
        }
    }

    @Test
    fun `save hands back the edited fields`() {
        show()

        compose.onNodeWithText("Edited title").performTextReplacement("New title")
        compose.onNodeWithText("Save").performClick()

        saved?.title shouldBe "New title"
    }

    @Test
    fun `reset tags puts back the source's tags`() {
        show()

        compose.onNodeWithText("Reset tags").performClick()
        compose.onNodeWithText("Save").performClick()

        saved?.genre shouldBe SOURCE.genre
    }

    @Test
    fun `a typed tag differing from one already there only in case is not added again`() {
        show()

        compose.onNodeWithText("Add tags").performClick()
        compose.onNodeWithText("Separate tags with commas").performTextInput("action, Mystery")
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithText("Save").performClick()

        saved?.genre shouldBe listOf("Action", "Drama", "Mystery")
    }

    @Test
    fun `an entry that is not merged offers no collate`() {
        show(mergedTags = null)

        compose.onAllNodesWithText("Collate tags").assertCountEquals(0)
    }

    @Test
    fun `collate adds the merged sources' tags the entry lacks`() {
        show(mergedTags = listOf("drama", "Horror"))

        compose.onNodeWithText("Collate tags").performClick()
        compose.onNodeWithText("Save").performClick()

        saved?.genre shouldBe listOf("Action", "Drama", "Horror")
    }

    @Test
    fun `a tag dragged past the last one moves to the end`() {
        show()

        compose.onNodeWithText("Action").performTouchInput {
            swipe(start = center, end = center + Offset(width * 6f, 0f), durationMillis = 500)
        }
        compose.onNodeWithText("Save").performClick()

        saved?.genre shouldBe listOf("Drama", "Action")
    }

    @Test
    fun `a new title leaves the files alone until the user ticks Also organize files`() {
        show(canRenameFiles = true)

        compose.onNodeWithText("Edited title").performTextReplacement("New title")
        compose.onNodeWithText("Also organize files").assertExists()
        compose.onNodeWithText("Save").performClick()

        organize shouldBe false
    }

    @Test
    fun `a new title with Also organize files ticked organizes the files`() {
        show(canRenameFiles = true)

        compose.onNodeWithText("Edited title").performTextReplacement("New title")
        compose.onNodeWithText("Also organize files").performClick()
        compose.onNodeWithText("Save").performClick()

        organize shouldBe true
    }

    @Test
    fun `storage that cannot rename files is never offered the move`() {
        show(canRenameFiles = false)

        compose.onNodeWithText("Edited title").performTextReplacement("New title")

        compose.onNodeWithText("Also organize files").assertDoesNotExist()
    }

    private companion object {
        val SOURCE = EntryEditInfoUi(
            title = "Source title",
            author = "",
            artist = "",
            description = "",
            genre = listOf("Comedy"),
            status = 0,
            thumbnailUrl = "",
        )
        val INITIAL = SOURCE.copy(title = "Edited title", genre = listOf("Action", "Drama"))
    }
}
