package reikai.presentation.reader

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.presentation.ComposeTestHost

/** The theme swatches show only colours, so TalkBack needs each one's name and which is in use. */
@RunWith(RobolectricTestRunner::class)
class ReaderThemeSwatchesTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show() = compose.setContent {
        ComposeTestHost {
            ReaderThemeSwatches(followSystem = false, background = "#F7DFC6", textColor = "#593100", onPreset = {})
        }
    }

    @Test
    fun theSwatchInUseIsNamedAndSelected() {
        show()

        compose.onNodeWithContentDescription("Sepia").assertIsSelected()
    }

    @Test
    fun aSwatchNotInUseIsNotSelected() {
        show()

        compose.onNodeWithContentDescription("Mint").assertIsNotSelected()
    }

    @Test
    fun aSwatchIsATouchTargetOfItsOwn() {
        show()

        compose.onNodeWithContentDescription("Mint").assertWidthIsAtLeast(48.dp)
    }
}
