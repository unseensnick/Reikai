package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.util.DisplayMetrics
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import ca.mpreg.webgpuviewer.viewer.ComposePageSize
import ca.mpreg.webgpuviewer.viewer.ImagePage
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import mihon.app.di.AppGraph
import mihon.core.metro.GraphProvider
import mihon.core.navigation.WebViewRoute
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.presentation.ComposeTestHost
import reikai.presentation.reader.STRIP_CAPTURE_SCALE
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import kotlin.math.abs

private const val WINDOW_WIDTH = 2256
private const val WINDOW_HEIGHT = 2504

// Smaller than the screen, as in split screen, so a capture sized from the screen cannot pass for one from the viewer.
private const val VIEWER_WIDTH = 1128
private const val VIEWER_HEIGHT = 1800
private const val ANCHOR_SOURCE = 1L

// Unlike any chapter id here, so a chapter id passed as the source cannot pass for it.
private const val CHAPTER_SOURCE = 99L

/**
 * The chrome pages themselves, built and drawn as the viewer builds them. A plain composable cannot
 * stand in: the capture size is chosen in these constructors, and a full-size capture per page in
 * reach is what ran the heap out.
 */
@RunWith(RobolectricTestRunner::class)
class ReaderChromePagesTest {

    @get:Rule
    val compose = createComposeRule()

    private val prev = ReaderChapter(Chapter.create().copy(id = 1L, name = "One"))
    private val next = ReaderChapter(Chapter.create().copy(id = 2L, name = "Two"))
    private val model = mockk<ReaderViewModel>(relaxed = true)

    private class Themed(base: Context) : ContextWrapper(base), GraphProvider<AppGraph> {
        override val graph: AppGraph = mockk(relaxed = true) {
            every { uiPreferences } returns UiPreferences(InMemoryPreferenceStore())
        }

        var started: Intent? = null

        override fun getApplicationContext(): Context = this

        override fun startActivity(intent: Intent) {
            started = intent
        }
    }

    /** A viewer not yet laid out reports no size of its own, as one a page is built for before layout. */
    private inline fun <reified T : WebGpuViewer> viewer(laidOut: Boolean = true): T = mockk(relaxed = true) {
        val host = this@ReaderChromePagesTest.model
        val metrics = DisplayMetrics().apply {
            widthPixels = WINDOW_WIDTH
            heightPixels = WINDOW_HEIGHT
        }
        every { viewerState.width } returns if (laidOut) VIEWER_WIDTH else 0
        every { viewerState.height } returns if (laidOut) VIEWER_HEIGHT else 0
        every { activity } returns mockk<ReaderActivity>(relaxed = true) {
            every { viewModel } returns host
            every { resources.displayMetrics } returns metrics
        }
        every { readerThemedContext } returns Themed(ApplicationProvider.getApplicationContext())
        every { coverSeedColor } returns null
    }

    private fun show(page: ImagePage.Compose) {
        compose.setContent {
            ComposeTestHost {
                Box(Modifier.width(600.dp).testTag("page")) { Draw(page) }
            }
        }
    }

    @Composable
    private fun BoxScope.Draw(page: ImagePage.Compose) = with(page) { Content() }

    @Test
    fun `a strip's error page is captured below its slot`() {
        val page = ErrorPage(viewer<WebGpuViewerContinuous>(), null, null)

        page.size shouldBe ComposePageSize.Of(VIEWER_WIDTH / 2, (VIEWER_HEIGHT * 0.8f / 2).toInt())
    }

    @Test
    fun `a strip's loading page is captured below its slot`() {
        val page = ProgressPage(viewer<WebGpuViewerContinuous>())

        page.size shouldBe ComposePageSize.Of(VIEWER_WIDTH / 2, VIEWER_HEIGHT / 2)
    }

    @Test
    fun `a strip page built before layout is captured from the screen's size`() {
        val page = ProgressPage(viewer<WebGpuViewerContinuous>(laidOut = false))

        page.size shouldBe ComposePageSize.Of(WINDOW_WIDTH / 2, WINDOW_HEIGHT / 2)
    }

    @Test
    fun `a paged viewer's chrome keeps the whole screen`() {
        val page = ErrorPage(viewer<WebGpuViewer>(), null, null)

        page.size shouldBe ComposePageSize.Auto
    }

    @Test
    fun `a smaller capture draws at the smaller density, so a button keeps its size on screen`() {
        val strip = ErrorPage(viewer<WebGpuViewerContinuous>(), null, null)
        val paged = ErrorPage(viewer<WebGpuViewer>(), null, null)
        compose.setContent {
            ComposeTestHost {
                Box(Modifier.size(600.dp, 800.dp).testTag("strip")) { Draw(strip) }
                Box(Modifier.size(600.dp, 800.dp).testTag("paged")) { Draw(paged) }
            }
        }

        // Width, since a button's height has a touch-target floor. Text rounds, hence the margin.
        fun retryWidth(tag: String) =
            compose.onNode(hasText("Retry") and hasAnyAncestor(hasTestTag(tag))).fetchSemanticsNode().size.width

        (abs(retryWidth("paged") - retryWidth("strip") * STRIP_CAPTURE_SCALE) <= 3) shouldBe true
    }

    /** A merged series crosses sources, and only the chapter's own gets past a host's hotlink check. */
    @Test
    fun `Open in WebView uses the source of the chapter the page is in`() {
        val viewer = viewer<WebGpuViewerContinuous>()
        val failed = mockk<WebGpuViewer.ViewerReaderPage> {
            every { page } returns ReaderPage(index = 0, imageUrl = "https://cdn.example/1.jpg").apply {
                chapter = this@ReaderChromePagesTest.next
            }
        }
        every { model.manga?.source } returns ANCHOR_SOURCE
        every { model.getSource(next.chapter.id) } returns mockk { every { id } returns CHAPTER_SOURCE }
        show(ErrorPage(viewer, failed, null))

        compose.onNodeWithText("Open in WebView").performClick()

        val started = (viewer.readerThemedContext as Themed).started
        started?.getStringExtra("route_key")?.let { Json.decodeFromString<WebViewRoute>(it).sourceId } shouldBe
            CHAPTER_SOURCE
    }

    @Test
    fun `a transition stays its height when the neighbour's error clears`() {
        show(TransitionPage(viewer<WebGpuViewerContinuous>(), prev, next))
        next.state = ReaderChapter.State.Error(Exception("timeout"))
        compose.waitForIdle()
        val failedHeight = compose.onNodeWithTag("page").fetchSemanticsNode().size.height

        next.state = ReaderChapter.State.Loading
        compose.waitForIdle()

        compose.onNodeWithTag("page").fetchSemanticsNode().size.height shouldBe failedHeight
    }

    @Test
    fun `a transition's download badge is there on the first frame`() {
        every { model.isChapterDownloaded(any()) } returns true
        compose.mainClock.autoAdvance = false

        show(TransitionPage(viewer<WebGpuViewerContinuous>(), prev, next))

        compose.onNodeWithContentDescription("Downloaded").assertExists()
    }
}
