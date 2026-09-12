package reikai.presentation.reader

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import reikai.domain.reader.ChapterProgress

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
    fun `a key event reaches the viewer and its answer comes back`() {
        val viewer = RecordingViewer(handlesKeys = true)

        val handled = viewport(viewer).handleKeyEvent(KEY_EVENT)

        handled shouldBe true
        viewer.keyEvents shouldBe 1
    }

    @Test
    fun `a key event the viewer declines is reported as unhandled`() {
        val viewer = RecordingViewer(handlesKeys = false)

        viewport(viewer).handleKeyEvent(KEY_EVENT) shouldBe false
    }

    @Test
    fun `a motion event reaches the viewer and its answer comes back`() {
        val viewer = RecordingViewer(handlesMotion = true)

        val handled = viewport(viewer).handleGenericMotionEvent(MOTION_EVENT)

        handled shouldBe true
        viewer.motionEvents shouldBe 1
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
            .seekTo(ChapterProgress.Pages(lastPageRead = 3L, pageCount = 4L))

        viewer.movedTo shouldBe pages[3]
    }

    @Test
    fun `seeking past the chapter's pages moves nothing`() {
        val viewer = RecordingViewer()

        viewport(viewer, visible = chapterOf(pages(4)))
            .seekTo(ChapterProgress.Pages(lastPageRead = 99L, pageCount = 4L))

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
            .seekTo(ChapterProgress.Pages(lastPageRead = 2L, pageCount = 4L))

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

    /** Page one, not the last page read: a deliberate step has to land somewhere predictable. */
    @Test
    fun `stepping to a chapter moves the viewer to its first page`() {
        val viewer = RecordingViewer()
        val page: ReaderPage = mockk()

        MangaViewport(viewer, pageAt = { index -> page.takeIf { index == 0 } }).onChapterStepped()

        viewer.movedTo shouldBe page
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
) = MangaViewport(viewer, visibleChapter = { visible }, activeChapter = { active })

// Pages are stand-ins: their real constructor reaches Android through Page, and the adapter only
// passes them through to the viewer.
private fun pages(count: Int): List<ReaderPage> = List(count) { mockk() }

private fun chapterOf(pages: List<ReaderPage>, requestedPage: Int = 0): ReaderChapter {
    val chapter = ChapterImpl()
    chapter.id = 1L
    chapter.url = ""
    chapter.name = ""
    return ReaderChapter(chapter).also {
        it.state = ReaderChapter.State.Loaded(pages)
        it.requestedPage = requestedPage
    }
}

// Both are framework value objects whose real constructors throw outside Android, and the adapter
// only passes them through, so a stand-in at that boundary is what the test needs.
private val KEY_EVENT: KeyEvent = mockk()
private val MOTION_EVENT: MotionEvent = mockk()

private class RecordingViewer(
    private val handlesKeys: Boolean = false,
    private val handlesMotion: Boolean = false,
) : Viewer {
    var destroyed = false
        private set
    var keyEvents = 0
        private set
    var movedTo: ReaderPage? = null
        private set
    var motionEvents = 0
        private set

    override fun getView(): View = error("no view in a unit test")

    override fun destroy() {
        destroyed = true
    }

    override fun setChapters(chapters: ViewerChapters) = Unit

    override fun moveToPage(page: ReaderPage) {
        movedTo = page
    }

    override fun handleKeyEvent(event: KeyEvent): Boolean {
        keyEvents++
        return handlesKeys
    }

    override fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        motionEvents++
        return handlesMotion
    }
}
