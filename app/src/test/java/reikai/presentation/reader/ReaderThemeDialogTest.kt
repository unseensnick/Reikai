package reikai.presentation.reader

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.presentation.ComposeTestHost

/** Follow system is one choice with the swatches beside it, so a screen reader hears whether it is picked. */
@RunWith(RobolectricTestRunner::class)
class ReaderThemeDialogTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `follow system reports that it is picked`() {
        val preset = readerThemePresets.first()
        compose.setContent {
            ComposeTestHost {
                ReaderThemeDialog(
                    followSystemTheme = true,
                    backgroundColor = preset.background,
                    textColor = preset.textColor,
                    onFollowSystem = {},
                    onPreset = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNode(hasText("Follow system") and isSelectable()).assertIsSelected()
    }
}
