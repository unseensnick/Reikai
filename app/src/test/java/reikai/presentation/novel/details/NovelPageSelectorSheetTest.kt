package reikai.presentation.novel.details

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.presentation.ComposeTestHost

/** The page shown is marked by more than colour, so a screen reader can say which one it is. */
@RunWith(RobolectricTestRunner::class)
class NovelPageSelectorSheetTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the page shown reads as selected and the others do not`() {
        compose.setContent {
            ComposeTestHost {
                NovelPageSelectorSheet(pages = listOf("1", "2"), selectedIndex = 1, onSelect = {}, onDismiss = {})
            }
        }

        compose.onNodeWithText("Page 2").assertIsSelected()
        compose.onNodeWithText("Page 1").assertIsNotSelected()
    }
}
