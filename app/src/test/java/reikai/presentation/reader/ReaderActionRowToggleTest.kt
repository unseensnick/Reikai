package reikai.presentation.reader

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import reikai.presentation.ComposeTestHost

/** The bar's on/off buttons: their state, and the fill a black-and-white screen can see, only when asked for. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderActionRowToggleTest {

    @get:Rule
    val compose = createComposeRule()

    private var fillColor = Color.Unspecified

    private fun show(filled: Boolean) {
        compose.setContent {
            ComposeTestHost {
                fillColor = MaterialTheme.colorScheme.primaryContainer
                ReaderActionRow(
                    enabledButtons = listOf(ReaderBottomButton.Grayscale, ReaderBottomButton.Settings),
                    filled = filled,
                    onClickChapterList = {},
                    onClickWebView = null,
                    onClickBrowser = null,
                    onClickShare = null,
                    orientation = ReaderOrientation.DEFAULT,
                    onClickOrientation = {},
                    onClickSettings = {},
                    readingMode = ReadingMode.DEFAULT,
                    onClickReadingMode = {},
                    cropEnabled = false,
                    onClickCropBorder = {},
                    keepScreenOn = false,
                    onClickKeepScreenOn = {},
                    grayscale = true,
                    onClickGrayscale = {},
                    onClickScrollToTop = {},
                    autoScrollActive = false,
                    onClickAutoScroll = {},
                    bionicActive = false,
                    onClickBionic = null,
                    onClickTheme = null,
                    onClickTextSize = null,
                    readAloudControlsVisible = false,
                    onClickReadAloud = null,
                    onLongClickReadAloud = {},
                    chapterStep = null,
                )
            }
        }
    }

    /** Beside the icon, inside the button's background and clear of the glyph. */
    private fun besideIcon(): Color {
        val image = compose.onNodeWithContentDescription("Grayscale").captureToImage().toPixelMap()
        return image[image.width / 8, image.height / 2]
    }

    @Test
    fun `an on button says it is on`() {
        show(filled = false)

        compose.onNodeWithContentDescription("Grayscale").assertIsOn()
    }

    @Test
    fun `an on button is filled when the reader asks for it`() {
        show(filled = true)

        besideIcon() shouldBe fillColor
    }

    @Test
    fun `an on button keeps the plain look by default`() {
        show(filled = false)

        (besideIcon() == fillColor) shouldBe false
    }
}
