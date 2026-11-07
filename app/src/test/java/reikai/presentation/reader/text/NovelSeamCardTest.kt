package reikai.presentation.reader.text

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.kotest.matchers.floats.shouldBeGreaterThan
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import reikai.presentation.ComposeTestHost

/** The chapter seam's card in the reader's colours, which the app theme's do not match. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NovelSeamCardTest {

    @get:Rule
    val compose = createComposeRule()

    // The test theme is light, so a warning in its own text colour would be dark on this dark page.
    @Test
    fun `the missing chapters warning is drawn in the reader's text colour`() {
        compose.setContent {
            ComposeTestHost {
                Box(Modifier.background(Color.Black)) {
                    NovelSeamCard(
                        NovelSeam("First", false, nextTitle = "Second", nextDownloaded = false, missingChapters = 3),
                        textColor = Color.White,
                    )
                }
            }
        }

        val pixels = compose.onNodeWithText("Skipping 3", substring = true).captureToImage().toPixelMap()
        val brightest = (0 until pixels.width).maxOf { x ->
            (0 until pixels.height).maxOf { y -> pixels[x, y].luminance() }
        }

        brightest shouldBeGreaterThan 0.5f
    }
}
