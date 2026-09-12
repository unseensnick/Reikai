package reikai.presentation.reader

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.R2LPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuViewer
import reikai.domain.reader.ChapterProgress
import reikai.domain.reader.pageIndex
import kotlin.math.min

/**
 * The only adapter under [ReaderViewport] today, over whatever `ReadingMode.toViewer` built. The
 * three image viewers stay unedited: everything neutral about them is expressible from out here.
 *
 * [viewer] is public because the reader settings sheet asks which viewer implementation is running,
 * which is a manga question rather than a neutral one, so it is answered by unwrapping this adapter
 * instead of by widening the contract.
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
) : ReaderViewport {

    override val view: View
        get() = viewer.getView()

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
    // WebGPU viewer only while its anchor page still belongs to the new chapter, so the seek is issued
    // from out here rather than by diverging the three viewers.
    override fun onChapterOpened() {
        val chapter = activeChapter() ?: return
        val pages = chapter.pages?.takeIf { it.isNotEmpty() } ?: return
        viewer.moveToPage(pages[min(chapter.requestedPage, pages.lastIndex)])
    }

    // The two viewer families spell right-to-left differently, and this is the one place that knows.
    override val isRtl: Boolean
        get() = viewer is R2LPagerViewer || (viewer as? WebGpuViewer)?.isReversed == true

    override fun destroy() = viewer.destroy()

    override fun handleKeyEvent(event: KeyEvent): Boolean = viewer.handleKeyEvent(event)

    override fun handleGenericMotionEvent(event: MotionEvent): Boolean = viewer.handleGenericMotionEvent(event)
}
