package reikai.presentation.browse

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import reikai.presentation.ComposeTestHost

/** A global search card gives a long title three lines before it cuts it, as upstream's card row did. */
@RunWith(RobolectricTestRunner::class)
// Native graphics, so the text is measured and wraps as on a device.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EntrySearchCardRowTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a long title wraps to three lines`() {
        compose.setContent {
            ComposeTestHost {
                EntrySearchCardRow(
                    entries = listOf(TITLE),
                    key = { it },
                    toUi = { EntryBrowseItemUi(title = it, cover = "", favorite = false) },
                    gestures = rememberEntryGestures<String>(choose = null, open = {}, add = {}),
                    isSelected = { false },
                )
            }
        }

        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(TITLE).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)

        layouts.single().lineCount shouldBe 3
    }

    private companion object {
        const val TITLE = "The extremely long light novel title that keeps going well past what two lines hold " +
            "in a narrow card"
    }
}
