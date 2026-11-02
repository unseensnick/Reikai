package reikai.presentation.reader

import android.content.res.Resources
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.Action
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.R2LPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuViewerContinuous
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import kotlinx.coroutines.flow.Flow
import reikai.domain.reader.ChapterProgress
import reikai.domain.reader.pageIndex
import tachiyomi.core.common.preference.Preference
import kotlin.math.min

/**
 * The manga adapter under [ReaderViewport], over whatever `ReadingMode.toViewer` built; the novel
 * viewports implement the contract directly. The image viewers carry only the divergences
 * content-layer.md's Reader row names, so the rest is expressed from out here.
 *
 * [viewer] is public because the settings sheet asks which viewer implementation is running, a manga
 * question rather than a neutral one, answered by unwrapping this rather than by widening the contract.
 */
class MangaViewport(
    val viewer: Viewer,
    /**
     * The chapter the chrome is describing, which is not always the active one: the viewer crosses a
     * boundary before the model swaps, and a scrub has to land inside the chapter the rail drew.
     */
    private val visibleChapter: () -> ReaderChapter?,
    /** The chapter that has just become active, which is what a step or a pick lands inside. */
    private val activeChapter: () -> ReaderChapter?,
    private val interval: Preference<Int>,
    private val speed: Preference<Float>,
    private val shownPage: Flow<ShownPage>,
    private val input: MangaViewportInput,
) : ReaderViewport {

    @Composable
    override fun Content(modifier: Modifier) = viewer.Content(modifier)

    override fun seekTo(progress: ChapterProgress) {
        val page = progress.pageIndex?.let { visibleChapter()?.pages?.getOrNull(it) } ?: return
        viewer.moveToPage(page)
    }

    // Upstream starts a stepped-to chapter at its first page rather than where it was last left, so a
    // deliberate step lands somewhere predictable.
    override fun onChapterStepped() {
        activeChapter()?.pages?.firstOrNull()?.let(viewer::moveToPage)
    }

    // A picked chapter resumes where it was left, which is what the arriving chapter's requestedPage
    // says. The pager and the webtoon recycler read that field only on their own first layout, and the
    // WebGPU viewer only when the reader did not scroll into the chapter, so the seek is issued
    // from out here rather than by diverging the three viewers.
    override fun onChapterOpened() {
        val chapter = activeChapter() ?: return
        val pages = chapter.pages?.takeIf { it.isNotEmpty() } ?: return
        viewer.moveToPage(pages[min(chapter.requestedPage, pages.lastIndex)])
    }

    // The two viewer families spell right-to-left differently, and this is the one place that knows.
    override val isRtl: Boolean
        get() = viewer is R2LPagerViewer || (viewer as? WebGpuViewer)?.isReversed == true

    // A strip scrolls through its own viewer, the only thing that knows which pages are coming into
    // view; everything else turns pages on the interval. Lazy, so a stand-in viewer never has to answer.
    override val autoScroll: ViewportAutoScroll by lazy {
        when (viewer) {
            is WebtoonViewer -> continuous(viewer::autoScrollBy)
            is WebGpuViewerContinuous -> continuous(viewer::autoScrollBy)
            is PagerViewer -> ViewportAutoScroll.Stepped(interval, shownPage, viewer::moveForward)
            is WebGpuViewer -> ViewportAutoScroll.Stepped(interval, shownPage, viewer::moveForward)
            else -> error("No auto-scroll for ${viewer::class}")
        }
    }

    private fun continuous(scrollBy: (Int) -> Boolean): ViewportAutoScroll {
        val scroller = FrameScroller(Resources.getSystem().displayMetrics.density, scrollBy)
        return ViewportAutoScroll.Continuous(speed, scroller::run)
    }

    override fun destroy() = viewer.destroy()

    // Upstream's mapping, kept at the Activity's dispatch so it works while anything holds focus. Movement is on key up.
    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val navigation = input.navigation ?: return false
        val isUp = event.action == KeyEvent.ACTION_UP
        val ctrl = event.metaState and KeyEvent.META_CTRL_ON != 0
        val action = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP -> {
                if (!volumeKeysActive(input.volumeKeysEnabled, input.menuVisible)) return false
                val forward = volumeKeyReadsOn(event.keyCode, input.volumeKeysInverted)
                if (viewer is WebtoonViewer) {
                    if (isUp) viewer.moveByVolumeKey(forward)
                    return true
                }
                if (forward) Action.FORWARD else Action.BACKWARD
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (ctrl) Action.FORWARD else navigation.actionOf(NavigationRegion.RIGHT)
            KeyEvent.KEYCODE_DPAD_LEFT -> if (ctrl) Action.BACKWARD else navigation.actionOf(NavigationRegion.LEFT)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> Action.FORWARD
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> Action.BACKWARD
            KeyEvent.KEYCODE_MENU -> Action.MENU
            else -> return false
        }
        if (isUp) perform(action)
        return true
    }

    /** What a tap or a key asked for. */
    fun perform(action: Action) = when (action) {
        Action.MENU -> input.toggleMenu()
        Action.FORWARD -> viewer.moveForward()
        Action.BACKWARD -> viewer.moveBackward()
    }

    // The viewers take the wheel in Compose, through `mouseWheelPaging`.
    override fun handleGenericMotionEvent(event: MotionEvent): Boolean = false
}

/** What a key needs of the reader around the viewer. */
interface MangaViewportInput {
    val navigation: ViewerNavigation?
    val volumeKeysEnabled: Boolean
    val volumeKeysInverted: Boolean
    val menuVisible: Boolean

    fun toggleMenu()
}
