package reikai.presentation.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.kanade.presentation.reader.ReaderError
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderTransitionPage
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.presentation.ComposeTestHost
import tachiyomi.domain.chapter.model.Chapter

/**
 * The readers' error page and the transition pages' failed-neighbour Retry, the WebGPU viewer's and
 * the other viewers'. The neighbour's state changes under a page already drawn, which is where a
 * Retry once stayed stale.
 */
@RunWith(RobolectricTestRunner::class)
class ReaderErrorPagesTest {

    @get:Rule
    val compose = createComposeRule()

    private val prev = ReaderChapter(Chapter.create().copy(id = 1L))
    private val next = ReaderChapter(Chapter.create().copy(id = 2L))
    private var retried: ReaderChapter? = null
    private var pageRetries = 0
    private val buttonPresses = mutableListOf<Boolean>()

    private fun showNeighbours() = compose.setContent {
        ComposeTestHost { NeighbourChapterRetry(prev, next, onRetry = { retried = it }) }
    }

    private fun showPageError() = compose.setContent {
        ComposeTestHost { ReaderError(message = null, onRetry = { pageRetries++ }, onOpenInWebView = null) }
    }

    private fun showTransition() = compose.setContent {
        ComposeTestHost {
            ReaderTransitionPage(
                transition = ChapterTransition.Next(prev, next),
                downloadState = null,
                onRetry = { retried = it },
                onButtonPressChange = { pressed -> buttonPresses += pressed },
            )
        }
    }

    @Test
    fun `a WebGPU transition stays its height when the neighbour's error clears`() {
        compose.setContent {
            ComposeTestHost {
                Column(Modifier.testTag("transition").keepTallestHeight()) {
                    NeighbourChapterRetry(prev, next, onRetry = {})
                }
            }
        }
        next.state = ReaderChapter.State.Error(Exception("timeout"))
        compose.waitForIdle()
        val failedHeight = compose.onNodeWithTag("transition").fetchSemanticsNode().size.height

        next.state = ReaderChapter.State.Loading
        compose.waitForIdle()

        compose.onNodeWithTag("transition").fetchSemanticsNode().size.height shouldBe failedHeight
    }

    @Test
    fun `a failed page's Retry runs the retry`() {
        showPageError()

        compose.onNodeWithText("Retry").performClick()

        pageRetries shouldBe 1
    }

    @Test
    fun `a page with no web address offers no WebView`() {
        showPageError()

        compose.onNodeWithText("Open in WebView").assertDoesNotExist()
    }

    @Test
    fun `a neighbour that fails after the page is drawn offers Retry`() {
        showNeighbours()

        next.state = ReaderChapter.State.Error(Exception("timeout"))

        compose.onNodeWithText("Retry").assertIsDisplayed()
    }

    @Test
    fun `Retry retries the neighbour that failed`() {
        prev.state = ReaderChapter.State.Error(Exception("timeout"))
        showNeighbours()

        compose.onNodeWithText("Retry").performClick()

        retried shouldBe prev
    }

    @Test
    fun `a neighbour loading again drops its error`() {
        next.state = ReaderChapter.State.Error(Exception("timeout"))
        showNeighbours()

        next.state = ReaderChapter.State.Loading

        compose.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test
    fun `a transition's Retry retries the chapter it leads to`() {
        next.state = ReaderChapter.State.Error(Exception("timeout"))
        showTransition()

        compose.onNodeWithText("Retry").performClick()

        retried shouldBe next
    }

    @Test
    fun `a transition's chapter failing after it is drawn offers Retry`() {
        showTransition()

        next.state = ReaderChapter.State.Error(Exception("timeout"))

        compose.onNodeWithText("Retry").assertIsDisplayed()
    }

    @Test
    fun `holding a transition's Retry tells the pager to leave the tap to it`() {
        next.state = ReaderChapter.State.Error(Exception("timeout"))
        showTransition()

        compose.onNodeWithText("Retry").performClick()

        buttonPresses shouldBe listOf(true, false)
    }
}
