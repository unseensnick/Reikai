package reikai.presentation.reader

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.TappingInvertMode
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.RightAndLeftNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import org.junit.jupiter.api.Test
import reikai.domain.reader.ChapterProgress
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.domain.chapter.model.Chapter

/**
 * The adapter is all delegation, and a member that silently stops delegating breaks the reader in a
 * way only a device pass would catch, so each one is pinned here instead.
 *
 * `isRtl` is the exception: it reads the concrete viewer classes, which cannot be built without an
 * Activity, so it is verified on device by the chapter navigator changing direction.
 */
class MangaViewportTest {

    @Test
    fun `destroy reaches the viewer`() {
        val viewer = RecordingViewer()

        viewport(viewer).destroy()

        viewer.destroyed shouldBe true
    }

    @Test
    fun `a volume key moves on key up`() {
        val viewer = RecordingViewer()

        viewport(viewer).handleKeyEvent(key(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_UP))

        viewer.forward shouldBe 1
    }

    @Test
    fun `a key down takes the key without moving`() {
        val viewer = RecordingViewer()

        val handled = viewport(viewer).handleKeyEvent(key(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_DOWN))

        (handled to viewer.forward) shouldBe (true to 0)
    }

    @Test
    fun `inverted volume keys swap direction`() {
        val viewer = RecordingViewer()

        viewport(viewer, input = FakeInput(inverted = true))
            .handleKeyEvent(key(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_UP))

        viewer.backward shouldBe 1
    }

    @Test
    fun `a volume key is left to the system while the menu is open`() {
        val handled = viewport(RecordingViewer(), input = FakeInput(menuVisible = true))
            .handleKeyEvent(key(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_UP))

        handled shouldBe false
    }

    @Test
    fun `the right arrow goes back in a right to left book`() {
        val viewer = RecordingViewer()

        viewport(viewer, input = FakeInput(RightAndLeftNavigation(TappingInvertMode.NONE, isReversed = true)))
            .handleKeyEvent(key(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_UP))

        viewer.backward shouldBe 1
    }

    @Test
    fun `ctrl and an arrow reads on whatever way the pages run`() {
        val viewer = RecordingViewer()

        viewport(viewer, input = FakeInput(RightAndLeftNavigation(TappingInvertMode.NONE, isReversed = true)))
            .handleKeyEvent(key(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_UP, KeyEvent.META_CTRL_ON))

        viewer.forward shouldBe 1
    }

    @Test
    fun `the menu key toggles the menu`() {
        val input = FakeInput()

        viewport(RecordingViewer(), input = input).handleKeyEvent(key(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_UP))

        input.toggled shouldBe 1
    }

    @Test
    fun `a key the reader has no use for is left to the system`() {
        val handled = viewport(RecordingViewer()).handleKeyEvent(key(KeyEvent.KEYCODE_A, KeyEvent.ACTION_UP))

        handled shouldBe false
    }

    @Test
    fun `a volume key on a strip scrolls by the set distance`() {
        val viewer = mockk<WebtoonViewer>(relaxed = true)

        viewport(viewer).handleKeyEvent(key(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_UP))

        verify { viewer.moveByVolumeKey(false) }
    }

    @Test
    fun `no key is read before the viewer's zones exist`() {
        val handled = viewport(RecordingViewer(), input = FakeInput(navigation = null))
            .handleKeyEvent(key(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_UP))

        handled shouldBe false
    }

    /**
     * A scrub arrives as the neutral position, and this is where it becomes a page. Looking the page
     * up rather than passing an index is what keeps `ReaderPage` out of the shared vocabulary.
     */
    @Test
    fun `seeking to a page moves the viewer to that page`() {
        val viewer = RecordingViewer()
        val pages = pages(4)

        viewport(viewer, visible = chapterOf(pages))
            .seekTo(ChapterProgress.Pages(lastPageRead = 3, pageCount = 4))

        viewer.movedTo shouldBe pages[3]
    }

    @Test
    fun `seeking past the chapter's pages moves nothing`() {
        val viewer = RecordingViewer()

        viewport(viewer, visible = chapterOf(pages(4)))
            .seekTo(ChapterProgress.Pages(lastPageRead = 99, pageCount = 4))

        viewer.movedTo shouldBe null
    }

    /** A percentage is the novel unit, so it names no page here and must not move the viewer. */
    @Test
    fun `seeking by percentage moves nothing`() {
        val viewer = RecordingViewer()

        viewport(viewer, visible = chapterOf(pages(4))).seekTo(ChapterProgress.Percent(4200L))

        viewer.movedTo shouldBe null
    }

    /**
     * The rail is drawn from the visible chapter's page count while the model is still swapping the
     * active one, so resolving the drag against the active chapter lands it in the wrong chapter's
     * list, which for a shorter one silently does nothing at all.
     */
    @Test
    fun `seeking resolves the page inside the chapter the rail is describing`() {
        val viewer = RecordingViewer()
        val visiblePages = pages(4)

        viewport(viewer, visible = chapterOf(visiblePages), active = chapterOf(pages(40)))
            .seekTo(ChapterProgress.Pages(lastPageRead = 2, pageCount = 4))

        viewer.movedTo shouldBe visiblePages[2]
    }

    /** Upstream starts a stepped-to chapter at its first page, whatever page it was last left on. */
    @Test
    fun `a step lands on the first page of the chapter that just became active`() {
        val viewer = RecordingViewer()
        val activePages = pages(10)

        viewport(viewer, visible = chapterOf(pages(4)), active = chapterOf(activePages, requestedPage = 7))
            .onChapterStepped()

        viewer.movedTo shouldBe activePages[0]
    }

    /**
     * A chapter picked from the sheet resumes instead, and the three image viewers each read that page
     * only at a moment a jump has already passed, so the seek has to come from here.
     */
    @Test
    fun `a picked chapter lands on the page it was left on`() {
        val viewer = RecordingViewer()
        val activePages = pages(10)

        viewport(viewer, active = chapterOf(activePages, requestedPage = 7)).onChapterOpened()

        viewer.movedTo shouldBe activePages[7]
    }

    /** A source that re-paginated shorter leaves a page index past the end, as the viewers assume too. */
    @Test
    fun `a picked chapter whose stored page is past its end lands on the last page`() {
        val viewer = RecordingViewer()
        val activePages = pages(3)

        viewport(viewer, active = chapterOf(activePages, requestedPage = 99)).onChapterOpened()

        viewer.movedTo shouldBe activePages[2]
    }

    @Test
    fun `a picked chapter with no pages loaded moves nothing`() {
        val viewer = RecordingViewer()

        viewport(viewer, active = chapterOf(emptyList())).onChapterOpened()

        viewer.movedTo shouldBe null
    }

    /**
     * Forward in a right-to-left book is toward the left of the screen. The direction and both verbs
     * run for real, so this holds whichever of them the adapter calls and whichever applies the
     * direction, and fails if both do.
     */
    @Test
    fun `R2L WebGPU auto-scroll turns toward the left, which is forward`() {
        val viewer = mockk<WebGpuViewer>(relaxed = true) {
            every { isReversed } answers { callOriginal() }
            every { moveForward() } answers { callOriginal() }
            every { moveBackward() } answers { callOriginal() }
        }
        // A mock skips the constructor, so the field the real getter and verbs read is set here.
        WebGpuViewer::class.java.getDeclaredField("isReversed").apply { isAccessible = true }.setBoolean(viewer, true)

        (viewport(viewer).autoScroll as ViewportAutoScroll.Stepped).advance()

        verify { viewer["moveLeft"]() }
    }

    @Test
    fun `the wrapped viewer stays reachable for the questions the contract does not answer`() {
        val viewer = RecordingViewer()

        viewport(viewer).viewer shouldBe viewer
    }
}

private fun viewport(
    viewer: Viewer,
    visible: ReaderChapter? = null,
    active: ReaderChapter? = null,
    input: MangaViewportInput = FakeInput(),
) = MangaViewport(
    viewer,
    visibleChapter = { visible },
    activeChapter = { active },
    interval = EmittingPreferenceStore().getInt("interval", 5),
    speed = EmittingPreferenceStore().getFloat("speed", 1f),
    shownPage = flowOf(ShownPage(Unit, ready = true)),
    input = input,
)

private class FakeInput(
    override val navigation: ViewerNavigation? = RightAndLeftNavigation(TappingInvertMode.NONE, isReversed = false),
    inverted: Boolean = false,
    override val menuVisible: Boolean = false,
) : MangaViewportInput {
    override val volumeKeysEnabled = true
    override val volumeKeysInverted = inverted
    var toggled = 0
        private set

    override fun toggleMenu() {
        toggled++
    }
}

private fun key(code: Int, keyAction: Int, meta: Int = 0): KeyEvent = mockk {
    every { keyCode } returns code
    every { action } returns keyAction
    every { metaState } returns meta
}

// Pages are stand-ins: their real constructor reaches Android through Page, and the adapter only
// passes them through to the viewer.
private fun pages(count: Int): List<ReaderPage> = List(count) { mockk() }

private fun chapterOf(pages: List<ReaderPage>, requestedPage: Int = 0): ReaderChapter {
    return ReaderChapter(Chapter.create().copy(id = 1L)).also {
        it.state = ReaderChapter.State.Loaded(pages)
        it.requestedPage = requestedPage
    }
}

private class RecordingViewer : Viewer {
    var destroyed = false
        private set
    var forward = 0
        private set
    var backward = 0
        private set
    var movedTo: ReaderPage? = null
        private set

    @Composable
    override fun Content(modifier: Modifier) = Unit

    override fun destroy() {
        destroyed = true
    }

    override fun setChapters(chapters: ViewerChapters) = Unit

    override fun moveToPage(page: ReaderPage) {
        movedTo = page
    }

    override fun moveForward() {
        forward++
    }

    override fun moveBackward() {
        backward++
    }
}
