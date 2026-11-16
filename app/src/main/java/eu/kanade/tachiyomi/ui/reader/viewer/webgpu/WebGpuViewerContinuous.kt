package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ca.mpreg.webgpuviewer.viewer.ImagePage
import ca.mpreg.webgpuviewer.viewer.ImageViewerContinuous
import ca.mpreg.webgpuviewer.viewer.ImageViewerContinuousState
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.mouseWheelPaging
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import reikai.presentation.reader.MenuHideOnScroll
import reikai.presentation.reader.holdsStripAutoScroll
import kotlin.math.max

class WebGpuViewerContinuous(activity: ReaderActivity, val useGap: Boolean = false) :
    WebGpuViewer(activity, isReversed = false, isVertical = true, viewerState = ImageViewerContinuousState()) {

    override val isContinuous: Boolean get() = true

    // How many pages the viewport shows depends on the zoom, and a page on screen has to be
    // decoded rather than merely reserved - so the window follows what the last frame reached.
    override val preloadAhead get() = max(3, state.pagesBelow)
    override val preloadBehind get() = max(2, state.pagesAbove)

    override val cacheSize get() = 2 + 2 * max(4, state.pagesAbove + state.pagesBelow)

    private val state get() = viewerState as ImageViewerContinuousState

    @Composable
    override fun Content(modifier: Modifier) {
        ImageViewerContinuous(modifier = modifier.mouseWheelPaging(this), state = state)
    }

    init {
        state.backgroundColor = readerBackgroundColor()

        state.onViewport = { readThrough ->
            val pageChanged = readThrough !== lastReadThrough
            if (pageChanged) {
                lastReadThrough = readThrough
                readThrough?.let { select(it) }
            }

            // RK: the pages auto-scroll's smooth step is held by, read here since only the renderer thread walks them
            autoScrollAhead = (0..state.pagesBelow + 1).mapNotNull { i ->
                state.getPage(i)?.let { (viewerPageFor(it) as? ViewerReaderPage)?.page }
            }

            val page0 = state.getPage(0)
            val edge = (page0 is TransitionPage && page0.prevChapter == null) ||
                (readThrough is TransitionPage && readThrough.nextChapter == null)

            val first = wasAtEdge == null
            val edgeChanged = edge != wasAtEdge
            wasAtEdge = edge
            val scrolledPast = scrolledPastHideThreshold() // RK
            if (edge) {
                if (edgeChanged) scope.launch { activity.showMenu() }
            } else if (!first && (edgeChanged || scrolledPast) && !activity.isScrollingThroughPages) { // RK
                scope.launch { activity.hideMenu() }
            }
        }
    }

    // RK --> the long strip's hide threshold, read as the other long strip reads it (WebtoonPageTracker): one
    // frame's scroll in screen pixels, so a fast drag or fling hides the menu and a slow one leaves it
    private val menuHide = MenuHideOnScroll(activity.appGraph.readerPreferences.readerHideThreshold.get().threshold)

    /** Where the last report saw the strip; null after a jump, which is no scroll. Renderer thread. */
    @Volatile
    private var lastDocumentY: Float? = null

    private fun scrolledPastHideThreshold(): Boolean {
        val documentY = state.documentY
        val dy = lastDocumentY?.let { (documentY - it) * state.scale } ?: 0f
        lastDocumentY = documentY
        // A finger pans the strip; a tap's step, auto-scroll and a jump move it with none down.
        return menuHide.hides(dy, touching = state.isPanning) && activity.viewModel.state.value.menuVisible
    }
    // RK <--

    // RK --> auto-scroll's smooth step, held while a page on screen or the next below it is loading. It
    // reads the pages' own load state rather than the draw state, because a refused step draws no frame
    // and the draw state would then never change.
    @Volatile
    private var autoScrollAhead: List<ReaderPage> = emptyList()

    fun autoScrollBy(px: Int): Boolean {
        if (holdsStripAutoScroll(autoScrollAhead.map { it.status })) return false
        step(px.toFloat())
        return true
    }

    /** An unanimated step; the frame it draws reports the viewport like any scroll. */
    private fun step(px: Float) {
        state.scrollBy(px)
        state.invalidate()
    }
    // RK <--

    /** Null until the first frame, so opening mid-document neither shows nor hides. */
    @Volatile
    private var wasAtEdge: Boolean? = null

    @Volatile
    private var lastReadThrough: ImagePage? = null

    /**
     * Mark [imagePage] read - its bottom has cleared the viewport, which is later than
     * onPageChange's "top arrived" and the reason this mode selects from here: scrolling up to
     * reveal the page above would otherwise walk progress back past pages still on screen.
     */
    private fun select(imagePage: ImagePage) {
        val page = readerPageFor(imagePage) ?: return
        scope.launch { activity.onPageSelected(page) }
    }

    private fun readerPageFor(imagePage: ImagePage): ReaderPage? {
        // Off the page itself: a transition page is never currentPage (the scroll walk stops
        // before it), so it is the first thing a preload evicts.
        if (imagePage is TransitionPage) return imagePage.prevChapter?.pages?.lastOrNull()

        // Only an image page needs the lookup - nothing on it names its ReaderPage. Evicted
        // means scrolled well clear of, so there is no progress left to report.
        return (viewerPageFor(imagePage) as? ViewerReaderPage)?.page
    }

    override fun destroy() {
        state.onViewport = null
        lastReadThrough = null
        wasAtEdge = null
        super.destroy()
    }

    private fun scrollByHalfPage(direction: Int) {
        // RK --> Page transitions off scrolls at once, as the other long strip does
        if (!general.usePageTransitions) {
            step(direction * state.height / 2f)
            return
        }
        // RK <--
        state.animateScroll(direction * state.height / 2f)
    }

    override fun moveRight() = scrollByHalfPage(1)

    override fun moveLeft() = scrollByHalfPage(-1)

    override fun moveToPage(page: ReaderPage) {
        super.moveToPage(page)
        landed() // RK
        // Also for a jump to the page already showing, which turns nothing to slide in.
        state.resetScroll()
    }

    // RK: an open or a jump. The library reports nothing from it until a scroll, so auto-scroll drops the pages
    // it saw before, or a step held on them would wait for a report that never comes. The jump selected its
    // own page, so the first report after it selects even the page the last one before it read.
    override fun landed() {
        autoScrollAhead = emptyList()
        wasAtEdge = null
        lastReadThrough = null
        lastDocumentY = null
    }

    override fun animateTurn(direction: Int, fromSpread: ImagePage) {
        // RK: Page transitions off lands the page without sliding it in
        if (general.usePageTransitions) state.animateSlideIn(direction) else state.invalidate()
    }
}
