package eu.kanade.presentation.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import reikai.domain.reader.ChapterProgress
import reikai.domain.reader.ReaderProgressLook
import reikai.domain.reader.ReaderProgressSize
import reikai.presentation.ComposeTestHost

/**
 * The progress line under the page, at its sizes and on its strip. Native graphics, or Robolectric measures
 * text at one height whatever its size.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderPageIndicatorTest {

    @get:Rule
    val compose = createComposeRule()

    private val progress = ChapterProgress.Pages(lastPageRead = 9, pageCount = 69)

    private fun height(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().size.height.toFloat()

    private fun width(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().size.width.toFloat()

    @Test
    fun `the largest size draws the line over twice as tall`() {
        compose.setContent {
            ComposeTestHost {
                Column {
                    ReaderPageIndicator(progress, Modifier.testTag("default"))
                    ReaderPageIndicator(
                        progress,
                        Modifier.testTag("max"),
                        look = ReaderProgressLook(ReaderProgressSize.MAX),
                    )
                }
            }
        }

        height("max") / height("default") shouldBeGreaterThan 2f
        width("max") / width("default") shouldBeGreaterThan 2f
    }

    /** A chapter whose pages have not loaded counts none, and "1 / 0" is worse than nothing. */
    @Test
    fun `a chapter with no pages yet draws no line`() {
        compose.setContent {
            ComposeTestHost {
                ReaderPageIndicator(ChapterProgress.Pages(lastPageRead = 0, pageCount = 0), Modifier.testTag("line"))
            }
        }

        compose.onNodeWithTag("line").assertDoesNotExist()
    }

    /** The reader hands its page colour over whatever the look, so the look alone decides the strip. */
    @Test
    fun `only a backed line spans the screen`() {
        compose.setContent {
            ComposeTestHost {
                Column(Modifier.width(300.dp).testTag("screen")) {
                    ReaderPageIndicator(
                        progress,
                        Modifier.testTag("backed"),
                        look = ReaderProgressLook(isBacked = true),
                        pageColor = Color.White,
                    )
                    ReaderPageIndicator(
                        progress,
                        Modifier.testTag("outlined"),
                        look = ReaderProgressLook(isBacked = false),
                        pageColor = Color.White,
                    )
                }
            }
        }

        width("backed") shouldBe width("screen")
        width("outlined") shouldBeLessThan width("screen")
    }
}
