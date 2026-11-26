package reikai.presentation.details

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import reikai.domain.library.LabelledTag
import reikai.presentation.ComposeTestHost

/**
 * A series page's tag chip shows its label, and its menu sends the source's own spelling to what asks a source
 * (Search, Global search) or copies, and the label to the library's tag filter.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class EntryDescriptionTagMenuTest(private val item: String, private val sent: String) {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a chip shows its label and its menu sends the value its action reads`() {
        val calls = mutableListOf<String>()
        compose.setContent {
            ComposeTestHost {
                ExpandableEntryDescription(
                    defaultExpandState = true,
                    description = "About",
                    tagsProvider = { listOf(LabelledTag(LABEL, SPELLING)) },
                    notes = "",
                    onTagSearch = { calls += "search $it" },
                    onFilterLibraryByTag = { tag, show -> calls += "${if (show) "show" else "hide"} $tag" },
                    onCopyTagToClipboard = { calls += "copy $it" },
                    onEditNotes = {},
                    onGlobalSearch = { calls += "global $it" },
                    loadImages = false,
                    searchMetadataChips = null,
                )
            }
        }

        compose.onNodeWithText(LABEL).performClick()
        compose.onNodeWithText(item).performClick()
        compose.waitForIdle()

        calls shouldBe listOf(sent)
    }

    companion object {
        private const val LABEL = "Science Fiction"
        private const val SPELLING = "SF"

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun items() = listOf(
            arrayOf<Any>("Search", "search $SPELLING"),
            arrayOf<Any>("Show only this tag", "show $LABEL"),
            arrayOf<Any>("Hide this tag", "hide $LABEL"),
            arrayOf<Any>("Global search", "global $SPELLING"),
            arrayOf<Any>("Copy to clipboard", "copy $SPELLING"),
        )
    }
}
