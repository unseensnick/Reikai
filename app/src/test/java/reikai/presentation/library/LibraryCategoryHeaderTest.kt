package reikai.presentation.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.presentation.ComposeTestHost
import tachiyomi.i18n.MR

/** A category header's sort control is a full tap target, and TalkBack hears whether the category is open. */
@RunWith(RobolectricTestRunner::class)
class LibraryCategoryHeaderTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show() = compose.setContent {
        ComposeTestHost {
            var collapsed by remember { mutableStateOf(false) }
            ReikaiLibraryCategoryHeader(
                name = "Shelf",
                itemCount = 3,
                showItemCount = false,
                isCollapsed = collapsed,
                onClick = { collapsed = !collapsed },
                sortLabel = MR.strings.unread,
                sortAscending = true,
                onClickSort = {},
            )
        }
    }

    @Test
    fun `the sort control is a full tap target`() {
        show()

        compose.onNodeWithText("Unread").assertHeightIsAtLeast(40.dp)
    }

    @Test
    fun `the header announces its collapse state and the arrow names its action`() {
        show()
        compose.onNodeWithText("Shelf").assert(stateIs("Expanded")).assert(hasContentDescription("Collapse"))

        compose.onNodeWithText("Shelf").performClick()

        compose.onNodeWithText("Shelf").assert(stateIs("Collapsed")).assert(hasContentDescription("Expand"))
    }

    private fun stateIs(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text)
}
