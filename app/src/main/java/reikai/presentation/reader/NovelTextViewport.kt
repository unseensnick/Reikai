package reikai.presentation.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.ArrowKeyMovementMethod
import android.view.Choreographer
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import reikai.domain.novel.tts.TtsHighlightStyle
import reikai.domain.reader.ChapterProgress
import reikai.domain.reader.fraction
import reikai.presentation.reader.text.AnchorSpan
import reikai.presentation.reader.text.ChapterScrollProgress
import reikai.presentation.reader.text.ChapterTextBlock
import reikai.presentation.reader.text.ChunkParagraph
import reikai.presentation.reader.text.LinkOnlyMovementMethod
import reikai.presentation.reader.text.MarkForegroundSpan
import reikai.presentation.reader.text.MarkUnderlineSpan
import reikai.presentation.reader.text.NovelBoundaryFailureView
import reikai.presentation.reader.text.NovelChapterSeamView
import reikai.presentation.reader.text.NovelSeam
import reikai.presentation.reader.text.NovelTextRenderer
import reikai.presentation.reader.text.NovelTextStyle
import reikai.presentation.reader.text.NovelWindowReach
import reikai.presentation.reader.text.ParagraphShape
import reikai.presentation.reader.text.ReadAloudBoxDecoration
import reikai.presentation.reader.text.ReadAloudMark
import reikai.presentation.reader.text.readAloudParagraphs
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The native light-novel viewport: the chapter as real text views rather than a WebView.
 *
 * The container is a recycler over a window of chapters, one item each: the chapter being read and
 * the neighbours the host grows it by ([append], [prepend]) as the reader crosses into them.
 */
class NovelTextViewport(
    private val context: Context,
    /** Selection and clickable links are exclusive: the movement method that drags cannot click. */
    private val textSelectable: Boolean,
    /** Whether a volume key scrolls right now: the setting, and the menu being down. The provider
     *  builds it once for both viewports, so they cannot disagree on when the keys are theirs. Which
     *  way and how far a press scrolls come from the settings pushed last. */
    private val volumeKeysActive: () -> Boolean,
    /** Both carry the chapter measured, not just the number: at a boundary the reader is already in
     *  the next chapter while the model still has the previous one, and an unnamed percentage lands
     *  on whichever the model happens to hold. */
    private val onProgressChanged: (chapterId: Long, percent: Int) -> Unit,
    private val onProgressSettled: (chapterId: Long, percent: Int) -> Unit,
    private val onToggleMenu: () -> Unit,
    /** Swipe-between-chapters, forward or back, the same contract [NovelWebViewport] takes. */
    private val onStepChapter: (forward: Boolean) -> Unit,
    /** Which chapter the reader is in, whenever that changes. The window is the only reason it can
     *  differ from the one the model last opened. */
    private val onVisibleChapter: (chapterId: Long) -> Unit,
    /** The reader asking again for the chapter beyond an edge that would not load. */
    private val onRetryBoundary: (forward: Boolean) -> Unit,
    /** The cutout inset in dp, zero when the host already pads clear of it. Read per load and again
     *  when insets arrive, since it is only known once the window has them. */
    private val cutoutTopDp: () -> Int,
    /** Whether a chapter fits on one screen, whenever that answer changes. Such a chapter has no
     *  scroll room, so the model reads it when the reader steps forward from it. */
    private val onChapterFits: (chapterId: Long, fits: Boolean) -> Unit,
    /** A chapter's last line reached the screen, once its images had landed. The model reads the
     *  novel's last chapter on it, since nothing follows that one to be left into. */
    private val onChapterEndSeen: (chapterId: Long) -> Unit,
) : ReaderViewport, TextViewport, ChapterWindow {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val renderer = NovelTextRenderer(context, scope, ::jumpToAnchor)

    /** The latest settings the viewport was given, which every chapter is built with. The host hands
     *  the window verbs the value it read when the window changed, and a change may have landed since. */
    private var settings: NovelReaderSettings? = null

    /**
     * The chapters the window holds, in reading order. Everything that was once "the open chapter" is
     * per slot, because two chapters on screen have their own heights, their own restore positions
     * and their own renders.
     */
    private val slots = mutableListOf<ChapterSlot>()

    /** One chapter's views and the state that belongs to it rather than to the viewport. */
    private class ChapterSlot(
        val chapter: NovelReaderViewModel.LoadedChapter,
        val block: ChapterTextBlock,
        /** What the chunk views are styled with, so a restyle that landed mid-render is caught on join. */
        var styledWith: NovelReaderSettings,
    ) {
        /** Applied once this chapter's text has a height to land in, and cleared only once it has landed
         *  or the reader moves the page ([readerMoved]), so a redraw starting in between still knows where
         *  the chapter was headed. */
        var landing: Landing? = null

        /** Before the text is set there is nothing to be a percentage of. */
        var rendered = false

        /** Read once the text is set, which nothing changes afterwards: a restyle keeps the characters. */
        val paragraphs: List<ChunkParagraph> by lazy { readAloudParagraphs(block.chunkViews.map { it.text }) }
    }

    /** The paragraph being read aloud, kept while highlighting is off so it is still followed. */
    private var spokenParagraph: ReadAloudPosition? = null

    /** Where a chapter is put once its text has a height. */
    private sealed interface Landing {
        /** A share of the chapter, which a stored percent and the rail name. */
        data class Share(val fraction: Float) : Landing

        /**
         * The line holding character [offset] of chunk [chunk], put back at [y] in the recycler, which a
         * redraw carries: a share counts every position past the chapter's last line reaching the bottom
         * of the screen as all of it. A redraw keeps the chunking, which depends on the text alone.
         */
        data class Line(val chunk: Int, val offset: Int, val y: Int) : Landing
    }

    /**
     * The window a settings redraw is rebuilding, held until it finishes. A redraw that supersedes it
     * rebuilds this rather than the half-built list left behind, and the host's verbs edit it rather
     * than the list, so the two cannot interleave. The reading position is kept for a redraw that
     * starts before the rebuilt window has one of its own to read.
     */
    private var redraw: Redraw? = null
    private var redrawJob: Job? = null

    private class Redraw(
        val chapters: MutableList<NovelReaderViewModel.LoadedChapter>,
        val readingId: Long?,
        val landing: Landing?,
    )

    /** Every pixel scrolled, so a correction can tell the reader's own movement from the layout's. */
    private var scrolled = 0L

    /** A correction posted and not yet run. It measured the last real layout, so it takes back whatever
     *  else moves its anchor before it runs, and a second one would scroll those pixels twice. */
    private var correctionPending = false

    /** The last chapter announced, so a scroll that stays inside one says nothing. */
    private var reportedVisibleId: Long? = null

    /** What the window ran out of text on at each end, drawn on the edge items. */
    private var failedPrevious: NovelReaderViewModel.BoundaryFailure? = null
    private var failedNext: NovelReaderViewModel.BoundaryFailure? = null

    /** Auto-scroll in pixels a second, zero when it is off. Carry and timestamp belong to the frame
     *  callback below and are held here so stopping can reset them. */
    private var autoScrollRate = 0f
    private var autoScrollCarry = 0f
    private var autoScrollLastFrameNanos = 0L

    private val adapter = BlockAdapter()

    /** Declared above the recycler that registers it, or it is null when that runs. */
    private val focusableWhileAttached = SelectableWhileAttached()

    /** The cutout inset in pixels, added to each column's top margin. Read on every open and again
     *  whenever insets arrive, since an open during an Activity recreation runs before the window
     *  has any and reads zero. */
    private var topInsetPx = 0

    /** What the chrome covers from each edge ([setObscured]), which only read-aloud's choices read. */
    private var obscuredTopPx = 0
    private var obscuredBottomPx = 0

    /** Where the touch went down, in viewport coordinates: a click carries no position of its own, and
     *  a swipe is measured from here. */
    private var touchDownX = 0f
    private var touchDownY = 0f

    /** Only consulted for selectable text, where the Editor takes the touch and no click follows. */
    private val selectableTaps = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                onReaderTap(e.x, e.y)
                return false
            }
        },
    )

    /**
     * Taps are watched above the children rather than taken from them. A selectable TextView hands
     * its touches to the Editor and fires no click at all, so turning text selection on left the
     * chrome unreachable; and a click carries no coordinate for the tap zones to read.
     * Never consumes, so selection dragging and link taps still reach the text underneath.
     * Declared above the recycler that registers it, or it is null when that runs.
     */
    private val tapWatcher = object : RecyclerView.OnItemTouchListener {
        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownX = e.x
                    touchDownY = e.y
                }
                MotionEvent.ACTION_UP -> onPointerUp(e.x, e.y)
            }
            if (textSelectable) selectableTaps.onTouchEvent(e)
            return false
        }

        override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) = Unit

        override fun onRequestDisallowInterceptTouchEvent(disallow: Boolean) = Unit
    }

    /** The spoken paragraph's box for the styles that draw one. Declared above the recycler that registers it. */
    private val readAloudBox = ReadAloudBoxDecoration {
        val (paragraph, view) = markedParagraph() ?: return@ReadAloudBoxDecoration null
        val current = checkNotNull(settings)
        val style = when (current.ttsHighlightStyle) {
            TtsHighlightStyle.BACKGROUND -> Paint.Style.FILL
            TtsHighlightStyle.OUTLINE -> Paint.Style.STROKE
            TtsHighlightStyle.UNDERLINE -> return@ReadAloudBoxDecoration null
        }
        ReadAloudBoxDecoration.Box(view, paragraph.start, paragraph.end, current.ttsHighlightColor, style)
    }

    private val recycler = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context)
        addItemDecoration(readAloudBox)
        adapter = this@NovelTextViewport.adapter
        // A chapter is one item; recycling it would throw away the laid-out text we just built.
        setItemViewCacheSize(WINDOW_SIZE)
        // An insert animation moves the reading position while it runs, which is the whole thing a
        // seamless window must not do. The webtoon viewer drops it for the same reason.
        itemAnimator = null
        isVerticalScrollBarEnabled = true
        addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                scrolled += dy
                reportVisibleChapter()
                report(onProgressChanged)
                reportEnds()
            }

            override fun onScrollStateChanged(view: RecyclerView, state: Int) {
                // Only a finger drags: the viewport's own scrolls settle without it.
                if (state == RecyclerView.SCROLL_STATE_DRAGGING) readerMoved()
                if (state == RecyclerView.SCROLL_STATE_IDLE) report(onProgressSettled)
            }
        })
        addOnItemTouchListener(tapWatcher)
        // A mouse wheel scrolls without a drag state; returning false leaves the scroll to the recycler.
        setOnGenericMotionListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_SCROLL) readerMoved()
            false
        }
        if (textSelectable) addOnAttachStateChangeListener(focusableWhileAttached)
        // Passed on untouched: the recycler draws nothing from them, it only needs to know they came.
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            refreshTopInset()
            insets
        }
    }

    override val view: View get() = recycler

    /** A chapter is one vertically scrolling column, so there is no right-to-left shape to report. */
    override val isRtl: Boolean get() = false

    override suspend fun load(chapter: NovelReaderViewModel.LoadedChapter, settings: NovelReaderSettings) {
        // Before the suspension below, so a change that lands during it is not overwritten with this.
        this.settings = settings
        warmFont()
        // Folded into the column's own top margin, since a seek measures from the viewport's edge and
        // would scroll a padding above the list straight off screen, even to 0%.
        topInsetPx = (cutoutTopDp() * context.resources.displayMetrics.density).toInt()
        // An explicit open replaces the window rather than growing it: the chapters around the one
        // being left are not the ones around the one being opened, so a redraw of those stops too.
        redrawJob?.cancel()
        redraw = null
        spokenParagraph = null
        evictAll()
        // A new window reports afresh, as the web page does, since the model may have dropped the last.
        reportedFits.clear()
        reportedEnds.clear()
        add(chapter, atEnd = true, landing = Landing.Share(chapter.progressPercent / 100f))
    }

    override val window: ChapterWindow get() = this

    /** Every answer waits out a redraw, which empties the window while it rebuilds the same chapters. */
    override val readAloud: ReadAloudSurface = object : ReadAloudSurface {
        override suspend fun paragraphs(chapterId: Long): List<String>? = withContext(Dispatchers.Main.immediate) {
            awaitRedraws()
            renderedSlot(chapterId)?.paragraphs?.map { it.text }
        }

        override suspend fun firstVisibleParagraph(): ReadAloudPosition? = withContext(Dispatchers.Main.immediate) {
            awaitRedraws()
            firstParagraphOnScreen()
        }

        override fun highlight(position: ReadAloudPosition?) {
            spokenParagraph = position
            drawSpokenParagraph()
            position?.let(::followSpokenParagraph)
        }
    }

    override fun setObscured(top: Int, bottom: Int) {
        obscuredTopPx = top
        obscuredBottomPx = bottom
    }

    /** Where the text read aloud can be seen from: below the cutout and below whatever chrome covers it. */
    private fun uncoveredTop() = maxOf(topInsetPx, obscuredTopPx)

    private fun uncoveredBottom() = recycler.height - obscuredBottomPx

    /**
     * Until the window's redraw plan is done, across every redraw that takes it over. Cancelling the one
     * joined here resumes this inline, before its successor is assigned, so a finished job with the plan
     * still open is looked at again once the main thread has moved on. A second look finding the same is
     * a redraw that died, and waiting longer would never end.
     */
    private suspend fun awaitRedraws() {
        var lookedAgain = false
        while (redraw != null) {
            val job = redrawJob
            if (job != null && !job.isCompleted) {
                job.join()
                lookedAgain = false
            } else if (lookedAgain) {
                return
            } else {
                withContext(Dispatchers.Main) {}
                lookedAgain = true
            }
        }
    }

    private fun renderedSlot(chapterId: Long) = slots.firstOrNull { it.rendered && it.chapter.chapterId == chapterId }

    private fun firstParagraphOnScreen(): ReadAloudPosition? {
        val visibleTop = uncoveredTop()
        val visibleBottom = uncoveredBottom()
        joined().forEach { slot ->
            val (top, height) = boundsOf(slot) ?: return@forEach
            if (top + height <= visibleTop || top >= visibleBottom) return@forEach
            slot.paragraphs.forEachIndexed { index, paragraph ->
                val (paragraphTop, paragraphBottom) = boundsOf(slot, paragraph) ?: return@forEachIndexed
                if (paragraphBottom > visibleTop && paragraphTop < visibleBottom) {
                    return ReadAloudPosition(slot.chapter.chapterId, index)
                }
            }
        }
        return null
    }

    /** Where [paragraph] starts and ends in the recycler's coordinates, null while it is out of layout. */
    private fun boundsOf(slot: ChapterSlot, paragraph: ChunkParagraph): Pair<Int, Int>? {
        val view = slot.block.chunkViews.getOrNull(paragraph.chunk) ?: return null
        val top = lineTopOf(view, paragraph.start) ?: return null
        val layout = view.layout ?: return null
        val bottom = top - layout.getLineTop(layout.getLineForOffset(paragraph.start)) +
            layout.getLineBottom(layout.getLineForOffset(paragraph.end - 1))
        return top to bottom
    }

    /**
     * Takes every mark off and draws the one [spokenParagraph] names. Found by type rather than kept,
     * since a restyle or an image landing copies the text with whatever marks it held at the time. The
     * box behind the text is [readAloudBox]'s, redrawn here since a chunk's layout changing redraws only
     * the chunk; every caller is a mark, its settings, or a chapter's text or images changing.
     */
    private fun drawSpokenParagraph() {
        slots.forEach { slot ->
            slot.block.chunkViews.forEach { view ->
                val text = view.text as? Spannable ?: return@forEach
                val marks = text.getSpans(0, text.length, ReadAloudMark::class.java)
                if (marks.isEmpty()) return@forEach
                marks.forEach(text::removeSpan)
                view.invalidate()
            }
        }
        recycler.invalidate()
        val (paragraph, view) = markedParagraph() ?: return
        val text = view.text as? Spannable ?: return
        val current = checkNotNull(settings)
        val marks = when (current.ttsHighlightStyle) {
            TtsHighlightStyle.BACKGROUND -> listOf(MarkForegroundSpan(current.ttsHighlightTextColor))
            TtsHighlightStyle.UNDERLINE -> listOf(MarkUnderlineSpan())
            TtsHighlightStyle.OUTLINE -> return
        }
        marks.forEach { text.setSpan(it, paragraph.start, paragraph.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        view.invalidate()
    }

    /** The spoken paragraph and the chunk view holding it, while highlighting is on and it is rendered. */
    private fun markedParagraph(): Pair<ChunkParagraph, TextView>? {
        val position = spokenParagraph ?: return null
        if (settings?.ttsHighlight != true) return null
        val slot = renderedSlot(position.chapterId) ?: return null
        val paragraph = slot.paragraphs.getOrNull(position.paragraph) ?: return null
        val view = slot.block.chunkViews.getOrNull(paragraph.chunk) ?: return null
        return paragraph to view
    }

    /**
     * Brings the spoken paragraph fully into the uncovered part of the screen when it is not, top at its
     * top edge or centred in it as the setting says. A chapter out of the recycler's layout has no line
     * to measure, so it is jumped to first and measured once laid out.
     */
    private fun followSpokenParagraph(position: ReadAloudPosition, retry: Boolean = true) {
        val current = settings?.takeIf { it.ttsKeepInView } ?: return
        val slot = renderedSlot(position.chapterId) ?: return
        val paragraph = slot.paragraphs.getOrNull(position.paragraph) ?: return
        val bounds = boundsOf(slot, paragraph)
        if (bounds == null) {
            val item = adapter.positionOf(slot).takeIf { it >= 0 && retry } ?: return
            (recycler.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(item, 0)
            recycler.post { if (spokenParagraph == position) followSpokenParagraph(position, retry = false) }
            return
        }
        val (top, bottom) = bounds
        val visibleTop = uncoveredTop()
        val visibleBottom = uncoveredBottom()
        if (top >= visibleTop && bottom <= visibleBottom) return
        val available = visibleBottom - visibleTop
        val target = if (current.ttsScrollToTop || bottom - top >= available) {
            visibleTop
        } else {
            visibleTop + (available - (bottom - top)) / 2
        }
        recycler.stopScroll()
        recycler.smoothScrollBy(0, top - target)
    }

    override suspend fun append(chapter: NovelReaderViewModel.LoadedChapter, settings: NovelReaderSettings) {
        grow(chapter, atEnd = true)
    }

    /**
     * Nothing compensates for the insert. Measured in `RecyclerPrependPositionTest`: a chapter added
     * above the reading position moves it by zero pixels, because the layout manager anchors on a
     * child it already has and never lays out an item entirely above the viewport. That holds while the
     * screen is full, which the host keeps true by adding below first ([NovelWindowDiff]); with nothing
     * below a short chapter, the chapter joins at its final height ([add]) and the reader ends at the bottom.
     */
    override suspend fun prepend(chapter: NovelReaderViewModel.LoadedChapter, settings: NovelReaderSettings) {
        grow(chapter, atEnd = false)
    }

    /**
     * Adds a chapter the host asked for, unless the window holds it already. While a redraw is
     * rebuilding the window it goes into that instead, which adds it in the order the host asked for.
     * Nothing suspends between the checks and the add, so a redraw cannot start in between.
     */
    private suspend fun grow(chapter: NovelReaderViewModel.LoadedChapter, atEnd: Boolean) {
        warmFont()
        if (slots.any { it.chapter.chapterId == chapter.chapterId }) return
        redraw?.let { pending ->
            if (pending.chapters.none { it.chapterId == chapter.chapterId }) {
                pending.chapters.add(if (atEnd) pending.chapters.size else 0, chapter)
            }
            return
        }
        add(chapter, atEnd, landing = null)
    }

    /** Resolving a user font copies it out of the user's storage folder on first use. Done where
     *  this is a coroutine, so the chunk views find it cached rather than each doing that lookup on
     *  the main thread as it is built. */
    private suspend fun warmFont() {
        context.appGraph.novelFontManager.warm(checkNotNull(settings).fontFamily)
    }

    override fun evict(chapterId: Long) {
        redraw?.chapters?.removeAll { it.chapterId == chapterId }
        if (spokenParagraph?.chapterId == chapterId) spokenParagraph = null
        val index = slots.indexOfFirst { it.chapter.chapterId == chapterId }
        if (index < 0) return
        slots.removeAt(index).block.discarded = true
        adapter.show(joined())
    }

    override fun setBoundaryFailures(
        previous: NovelReaderViewModel.BoundaryFailure?,
        next: NovelReaderViewModel.BoundaryFailure?,
    ) {
        if (previous == failedPrevious && next == failedNext) return
        failedPrevious = previous
        failedNext = next
        // The bound holders are updated in place rather than through the adapter. A rebind detaches
        // and re-adds the chapter's text container, re-measuring every chunk of the chapter the
        // reader is inside, which is the one thing this container must never do while it is showing.
        adapter.refreshBoundaries()
    }

    /** Everything a view is drawn from. The rest of the object (auto-scroll, the rail, volume keys,
     *  read-aloud) changes nothing on the page, and restyling for one of those copied the whole
     *  chapter's text and re-measured every chunk on the main thread. */
    private fun NovelReaderSettings.renderShape() = listOf(
        fontSize, fontFamily, lineHeight, textAlign, textColor, backgroundColor, margins,
        paragraphIndent, paragraphSpacing, bionicReading,
    )

    /** What the read-aloud mark is drawn from, which changes the spans on one paragraph and nothing else. */
    private fun NovelReaderSettings.markShape() =
        listOf(ttsHighlight, ttsHighlightStyle, ttsHighlightColor, ttsHighlightTextColor)

    override fun applySettings(settings: NovelReaderSettings) {
        val previous = this.settings
        this.settings = settings
        // The markers are views of their own, so showing or hiding them re-measures no text.
        if (previous != null && previous.alwaysShowChapterTransition != settings.alwaysShowChapterTransition) {
            adapter.refreshSeams()
        }
        if (previous?.markShape() != settings.markShape()) drawSpokenParagraph()
        if (previous != null && previous.renderShape() == settings.renderShape()) return
        if (previous != null && previous.paragraphShape().needsRedrawFor(settings.paragraphShape())) {
            startRedraw()
            return
        }
        scope.launch {
            // A font just chosen may not be resolved yet, and resolving one touches storage.
            warmFont()
            // The latest rather than this call's, since another may have landed while the font resolved.
            val current = checkNotNull(this@NovelTextViewport.settings)
            recycler.setBackgroundColor(NovelTextStyle.parseColor(current.backgroundColor, Color.WHITE))
            // A chapter still rendering has no views to restyle yet; it is caught as it joins.
            holdingReader { slots.filter { it.rendered }.forEach { restyle(it, current) } }
        }
    }

    /**
     * Rebuilds the window, since indent and spacing are spans measured when the text is built. Only
     * the chapter being read keeps its place; a neighbour is redrawn from its top, since the window
     * rebuilds around wherever the reader ends up. Rebuilt outward from it, below before above, for
     * the reason the host grows a window that way. A text-size drag redraws on every step, so a
     * redraw still running is cancelled and its window taken over, reading position included.
     */
    private fun startRedraw() {
        redrawJob?.cancel()
        // A chapter on its way to a position is where the reader is headed; failing that, the layout.
        val seeking = slots.firstOrNull { it.landing != null }
        val visible = visibleSlot()
        val pending = redraw
        val plan = Redraw(
            chapters = pending?.chapters ?: slots.mapTo(mutableListOf()) { it.chapter },
            readingId = (seeking ?: visible)?.chapter?.chapterId ?: pending?.readingId,
            landing = seeking?.landing ?: visible?.let(::landingOf) ?: pending?.landing,
        )
        redraw = plan
        redrawJob = scope.launch {
            warmFont()
            evictAll()
            while (true) {
                val (chapter, atEnd) = plan.next() ?: break
                add(chapter, atEnd, landing = plan.landing.takeIf { chapter.chapterId == plan.readingId })
            }
            redraw = null
            // Posted behind the last join's own landing. Every chapter the redraw adds has joined, so a line
            // still short of room gets none, and waiting on would move the reader when the window next grows.
            // A share still waiting on its images or its layout is not short of room, so it waits on.
            val rebuilt = joined()
            recycler.post {
                rebuilt.forEach { slot ->
                    land(slot)
                    if (slot.landing is Landing.Line) slot.landing = null
                }
            }
        }
    }

    /** Where the reader is in [slot], the chapter being read: the line at the top of the screen, or a
     *  share while the chapter's text starts on screen. */
    private fun landingOf(slot: ChapterSlot): Landing {
        val line = lineAtTop() ?: return Landing.Share(fractionOf(slot))
        return Landing.Line(slot.block.chunkViews.indexOf(line.view), line.offset, line.y)
    }

    /**
     * The next chapter [Redraw] adds and whether below: the one being read, then those after it in
     * order, then those before it nearest first. Read afresh each time, since the host edits it. With
     * no chapter being read, the first one rebuilt stands in, so one prepended since still goes above.
     */
    private fun Redraw.next(): Pair<NovelReaderViewModel.LoadedChapter, Boolean>? {
        val held = { chapter: NovelReaderViewModel.LoadedChapter ->
            slots.any { it.chapter.chapterId == chapter.chapterId }
        }
        val reading = chapters.indexOfFirst { it.chapterId == readingId }.takeIf { it >= 0 }
            ?: chapters.indexOfFirst(held).coerceAtLeast(0)
        chapters.drop(reading).firstOrNull { !held(it) }?.let { return it to true }
        return chapters.take(reading).lastOrNull { !held(it) }?.let { it to false }
    }

    /** Restyles a chapter's built views in place, keeping its spans. */
    private fun restyle(slot: ChapterSlot, settings: NovelReaderSettings) {
        if (slot.styledWith.renderShape() == settings.renderShape()) return
        slot.styledWith = settings
        NovelTextStyle.applyMargins(slot.block.container, settings, context, topInsetPx)
        slot.block.chunkViews.forEach { view ->
            // A precomputed layout was measured against the old paint, and the framework's own
            // long-press drag path re-sets it without checking, which throws. Copying rather
            // than flattening keeps the chapter's emphasis, links, images and paragraph spans.
            view.text = SpannableStringBuilder(view.text)
            NovelTextStyle.apply(view, settings, context)
        }
    }

    /**
     * Runs [change], which re-measures text already laid out, then puts the line at the top of the
     * screen back where it was, the way the WebView page's `place` holds it. The layout
     * manager holds only an item's top, so growth above that line inside its chapter would carry the
     * reader off it. What the reader scrolled meanwhile is theirs and stays, as in [BlockAdapter.show].
     */
    private inline fun holdingReader(change: () -> Unit) {
        val anchor = if (canMeasureForCorrection()) lineAtTop() else null
        val scrolledBefore = scrolled
        change()
        if (anchor == null) return
        postCorrection {
            val after = lineTopOf(anchor.view, anchor.offset) ?: return@postCorrection 0
            after - anchor.y + (scrolled - scrolledBefore).toInt()
        }
    }

    /** Not while a correction is pending, see [correctionPending], nor while the list holds changes its
     *  layout has not caught up with, since a position then still names the item it used to. */
    private fun canMeasureForCorrection() = !correctionPending && !recycler.hasPendingAdapterUpdates()

    /** Scrolls by what [shift] measures once the change has laid out. */
    private fun postCorrection(shift: () -> Int) {
        correctionPending = true
        recycler.post {
            correctionPending = false
            val by = shift()
            if (by != 0) recycler.scrollBy(0, by)
        }
    }

    private class LineAnchor(val view: TextView, val offset: Int, val y: Int)

    /** The line at the top of the screen, or the last one above it when the top falls below the text.
     *  Null when the chapter's text starts on screen, where the item's own anchor already holds it. */
    private fun lineAtTop(): LineAnchor? {
        val slot = visibleSlot() ?: return null
        val view = slot.block.chunkViews.lastOrNull { (topInRecycler(it) ?: 1) <= 0 } ?: return null
        val layout = view.layout ?: return null
        val top = (topInRecycler(view) ?: return null) + view.totalPaddingTop
        val offset = layout.getLineStart(layout.getLineForVertical(-top))
        val y = lineTopOf(view, offset) ?: return null
        return LineAnchor(view, offset, y)
    }

    /** Puts the line holding anchor [index] of [widget]'s chapter at the top, as the page jumps to a fragment. */
    private fun jumpToAnchor(widget: TextView, index: Int) {
        val block = slots.firstOrNull { widget in it.block.chunkViews }?.block ?: return
        for (view in block.chunkViews) {
            val text = view.text as? Spanned ?: continue
            val anchor = text.getSpans(0, text.length, AnchorSpan::class.java).firstOrNull { it.index == index }
                ?: continue
            lineTopOf(view, text.getSpanStart(anchor))?.let { recycler.scrollBy(0, it) }
            return
        }
    }

    /** Where the line holding [offset] starts, in the recycler's coordinates. */
    private fun lineTopOf(view: TextView, offset: Int): Int? {
        val layout = view.layout ?: return null
        val top = topInRecycler(view) ?: return null
        return top + view.totalPaddingTop + layout.getLineTop(layout.getLineForOffset(offset))
    }

    /** Null once [view] is not in the recycler's layout, which a chapter scrolled out of it is not. */
    private fun topInRecycler(view: View): Int? {
        var top = 0
        var current = view
        while (current !== recycler) {
            top += current.top
            current = current.parent as? View ?: return null
        }
        return top
    }

    /** Re-reads the cutout inset and moves every column's top margin by the change. */
    private fun refreshTopInset() {
        val inset = (cutoutTopDp() * context.resources.displayMetrics.density).toInt()
        if (inset == topInsetPx) return
        topInsetPx = inset
        val current = settings ?: return
        holdingReader {
            slots.forEach { NovelTextStyle.applyMargins(it.block.container, current, context, topInsetPx) }
        }
    }

    /**
     * Auto-scroll, driven by a frame callback so it moves the recycler the same way a drag does
     * rather than competing with it. [pixelsPerFrame] is the WebView renderer's unit, a CSS pixel
     * per frame at 60Hz, so it becomes a rate and crosses into the device pixels a recycler scrolls
     * in. Without the density the same setting would move three times as far over there.
     */
    override fun setAutoScroll(running: Boolean, pixelsPerFrame: Float) {
        val density = context.resources.displayMetrics.density
        val rate = if (running) pixelsPerFrame * FRAMES_PER_SECOND * density else 0f
        if (rate == autoScrollRate) return
        val wasRunning = autoScrollRate > 0f
        autoScrollRate = rate
        if (rate <= 0f) {
            Choreographer.getInstance().removeFrameCallback(autoScrollFrames)
        } else if (!wasRunning) {
            autoScrollLastFrameNanos = 0L
            autoScrollCarry = 0f
            Choreographer.getInstance().postFrameCallback(autoScrollFrames)
        }
    }

    /** The fraction is carried between frames, or a speed below one pixel a frame never moves at all.
     *  The first frame only takes a timestamp, since there is no interval to scroll over yet. */
    private val autoScrollFrames = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (autoScrollRate <= 0f) return
            val previous = autoScrollLastFrameNanos
            autoScrollLastFrameNanos = frameTimeNanos
            if (previous != 0L) {
                autoScrollCarry += autoScrollRate * ((frameTimeNanos - previous) / NANOS_PER_SECOND)
                val whole = autoScrollCarry.toInt()
                if (whole != 0) {
                    autoScrollCarry -= whole
                    recycler.scrollBy(0, whole)
                }
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** The text column: what is left of the reader once the page's side margins are taken off. The
     *  recycler answers for its own width once laid out, and the display stands in before that. */
    private fun columnWidthPx(settings: NovelReaderSettings): Int {
        val density = context.resources.displayMetrics.density
        val sides = ((settings.margins.left + settings.margins.right) * density).toInt()
        val available = recycler.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
        return (available - sides).coerceAtLeast(1)
    }

    /**
     * Adds [chapter] to the window and renders it, putting the reader at [landing] once the text has a
     * height. Indent and spacing are spans measured in pixels when the text is built, so a change to
     * those re-adds instead. A null [landing] leaves the reader where they are; a share of zero is the
     * chapter's first line. Returns once the chapter has joined the list, which it does only with its
     * text set ([join]): laid out earlier, a chapter above the reader grows after layout and carries
     * the reader with it. Returning then makes the host's order the join order.
     */
    private suspend fun add(
        chapter: NovelReaderViewModel.LoadedChapter,
        atEnd: Boolean,
        landing: Landing?,
    ) {
        val settings = checkNotNull(this.settings)
        recycler.setBackgroundColor(NovelTextStyle.parseColor(settings.backgroundColor, Color.WHITE))
        val block = ChapterTextBlock(context) { createChunkView(settings) }
        NovelTextStyle.applyMargins(block.container, settings, context, topInsetPx)
        val slot = ChapterSlot(chapter, block, styledWith = settings)
        slot.landing = landing
        // A chapter changes height without a scroll when its text is set and when its images land, and
        // a short one is never scrolled, so a new height is the only point its fit and end get checked.
        // Posted, since the list places the item only after the column has laid out.
        block.container.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            recycler.post {
                // A share landing waits for the chapter's images and for a layout to measure it in, and
                // each arrives as a layout of the column, so this is where a held one is applied.
                if (slot.landing is Landing.Share) land(slot)
                // The chapter's text was just set, which a redraw does afresh without the mark, or an image
                // re-set it from a copy that can carry a mark the reader has since moved past.
                drawSpokenParagraph()
                reportFits(slot)
                reportEnds()
            }
        }
        slots.add(if (atEnd) slots.size else 0, slot)
        renderer.render(
            block = block,
            html = chapter.html,
            fontSize = settings.fontSize,
            paragraphSpacing = settings.paragraphSpacing,
            paragraphIndent = settings.paragraphIndent,
            selectable = textSelectable,
            bionic = settings.bionicReading,
            contentWidth = columnWidthPx(settings),
            baseUrl = chapter.baseUrl,
            onTextSet = { join(slot) },
        ).join()
    }

    /** The chapters the list shows: every one in the window whose text is set. */
    private fun joined(): List<ChapterSlot> = slots.filter { it.rendered }

    /** Empties the window. Each block's views leave with it, and a pooled holder lets go of its
     *  chapter as it is recycled, so nothing holds a chapter's text once it is out. */
    private fun evictAll() {
        slots.forEach { it.block.discarded = true }
        slots.clear()
        reportedVisibleId = null
        adapter.show(joined())
    }

    override fun seekTo(progress: ChapterProgress) {
        // A page index is not a scroll position, and reading one as a fraction would seek to a third
        // of the chapter for page 3 of 10. The twin rejects it the same way.
        if (progress !is ChapterProgress.Percent) return
        val fraction = progress.fraction
        val slot = visibleSlot() ?: slots.firstOrNull() ?: return
        // Held for the chapter's own landing to apply when it has one still to come, which is also where
        // one waits that has not rendered yet. Nothing reads it on a chapter already settled.
        if (slot.rendered &&
            slot.landing == null
        ) {
            scrollWithin(slot, fraction)
        } else {
            slot.landing = Landing.Share(fraction)
        }
    }

    /** A step reopens through [load], which replaces the window, so there is nothing to carry over. */
    override fun onChapterStepped() = Unit

    override fun destroy() {
        setAutoScroll(running = false, pixelsPerFrame = 0f)
        scope.cancel()
        evictAll()
    }

    /** The same contract [NovelWebViewport] answers, so a volume press behaves the same in either. */
    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val isVolumeKey = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (!isVolumeKey || !volumeKeysActive()) return false
        val current = settings ?: return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            readerMoved()
            val forward = (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) != current.volumeButtonsInverted
            val fraction = current.volumeButtonsFraction.coerceIn(0.1f, 1f)
            val step = (recycler.height * fraction).roundToInt()
            recycler.smoothScrollBy(0, if (forward) step else -step)
        }
        // Consume the key-up too, so the system volume UI never shows during a press.
        return true
    }

    override fun handleGenericMotionEvent(event: MotionEvent): Boolean = false

    /**
     * A tap read through the session's tap zones, which the WebView renderer asks too, with a
     * three-quarter-screen step. Read from the live settings, so a layout change takes effect at once.
     */
    private fun onTap(x: Float, y: Float) {
        val width = recycler.width
        val height = recycler.height
        val zones = settings?.tapZones
        if (zones == null || width <= 0 || height <= 0) {
            onToggleMenu()
            return
        }
        val step = (height * NovelTapZones.SCROLL_FRACTION).roundToInt()
        when (zones.actionAt(x / width, y / height)) {
            NovelTapAction.MENU -> onToggleMenu()
            NovelTapAction.BACK -> {
                readerMoved()
                recycler.smoothScrollBy(0, -step)
            }
            NovelTapAction.FORWARD -> {
                readerMoved()
                recycler.smoothScrollBy(0, step)
            }
            NovelTapAction.NONE -> Unit
        }
    }

    /**
     * A tap on a boundary failure is that box's, as the WebView page treats it. The watcher sees a
     * tap on its Retry as well as the button does, so without this one tap retried and also scrolled
     * or toggled the chrome; a tap beside the button falls through to the item's click the same way.
     */
    private fun onReaderTap(x: Float, y: Float) {
        val item = recycler.findChildViewUnder(x, y) as? ViewGroup
        val onFailure = item?.children?.any {
            it is NovelBoundaryFailureView && it.isVisible &&
                x - item.left in it.left.toFloat()..it.right.toFloat() &&
                y - item.top in it.top.toFloat()..it.bottom.toFloat()
        } == true
        if (!onFailure) onTap(x, y)
    }

    /**
     * A swipe between chapters, at the WebView renderer's thresholds so the gesture behaves the same
     * in either renderer: mostly sideways, far enough not to be a stray, and started on the half it
     * moves away from, which is what makes it cross the middle rather than flick in a corner.
     */
    private fun onPointerUp(x: Float, y: Float) {
        if (settings?.swipeGestures != true) return
        val dx = x - touchDownX
        val dy = y - touchDownY
        val minimum = SWIPE_MIN_DP * context.resources.displayMetrics.density
        if (abs(dx) < minimum || abs(dx) < abs(dy) * 2) return
        val middle = recycler.width / 2f
        if (dx < 0 && touchDownX >= middle) onStepChapter(true)
        if (dx > 0 && touchDownX <= middle) onStepChapter(false)
    }

    /**
     * Hands [sink] the chapter being read and how far through it the reader is, unless that chapter is still
     * headed for a landing: until then the screen shows where the list put it, its start, and the model
     * saves whatever it is told over the position being landed. Another chapter on screen is where the
     * reader really is. The chapter named is right either way, so [reportVisibleChapter] is not held; nor
     * is a fit, which is height alone, or [reportEnds], since a chapter's start shows no end its landing hides.
     */
    private fun report(sink: (Long, Int) -> Unit) {
        val slot = visibleSlot() ?: return
        if (slot.landing == null) sink(slot.chapter.chapterId, percentOf(slot))
        reportFits(slot)
    }

    /**
     * The reader moved the page themselves, which ends every landing still to come: applied later, it would
     * take them back from what they scrolled to, and until it was gone nothing they read was reported. Only
     * what they ask for counts. Auto-scroll, a read-aloud follow and a seek move the page on the reader's
     * behalf, and a seek names a landing of its own.
     */
    private fun readerMoved() {
        slots.forEach { it.landing = null }
    }

    /** The last fit answer sent per chapter, so a scroll that changes nothing says nothing. */
    private val reportedFits = mutableMapOf<Long, Boolean>()

    /** The same test [ChapterScrollProgress] makes when it reports such a chapter at 0. Held while its
     *  images load, as [reportEnds] is: a step forward reads a chapter that fits, and until they land
     *  a long illustrated one measures short. Their landing lays the column out, which reports it. */
    private fun reportFits(slot: ChapterSlot) {
        if (!slot.rendered || slot.block.imagesLoading) return
        val (_, height) = boundsOf(slot) ?: return
        val fits = height <= recycler.height
        if (reportedFits.put(slot.chapter.chapterId, fits) != fits) onChapterFits(slot.chapter.chapterId, fits)
    }

    /** Chapters whose last line has been on screen, each told once. */
    private val reportedEnds = mutableSetOf<Long>()

    /** The WebView page's `reportEnds`: held while a chapter's images load, since until they land it
     *  measures short and would be read the moment it opened. */
    private fun reportEnds() {
        slots.forEach { slot ->
            val id = slot.chapter.chapterId
            if (!slot.rendered || slot.block.imagesLoading || id in reportedEnds) return@forEach
            val (top, height) = boundsOf(slot) ?: return@forEach
            if (top + height > recycler.height) return@forEach
            reportedEnds += id
            onChapterEndSeen(id)
        }
    }

    private fun percentOf(slot: ChapterSlot): Int = (fractionOf(slot) * 100f).roundToInt().coerceIn(0, 100)

    /**
     * Zero rather than a whole while the chapter has no measured height. Reporting completion there
     * would mark the chapter read and retire its download before it had been seen.
     */
    private fun fractionOf(slot: ChapterSlot): Float {
        if (!slot.rendered) return 0f
        val (top, height) = boundsOf(slot) ?: return 0f
        return ChapterScrollProgress.fractionOf(top, height, recycler.height)
    }

    /** Only a change is announced, because the scroll listener runs on every frame. */
    private fun reportVisibleChapter() {
        val id = visibleSlot()?.chapter?.chapterId ?: return
        if (id == reportedVisibleId) return
        reportedVisibleId = id
        onVisibleChapter(id)
    }

    /** The chapter being read: the first the viewport has any of on screen, which is how the webtoon
     *  viewer resolves the same question. Null before the recycler has laid anything out. */
    private fun visibleSlot(): ChapterSlot? {
        val manager = recycler.layoutManager as? LinearLayoutManager ?: return null
        val position = manager.findFirstVisibleItemPosition()
        return adapter.slotAt(position)
    }

    /**
     * Where this chapter's text sits in the viewport, and how tall it is. The item view is not the
     * answer: it also holds the seam marker above the text, and counting that would have the reader
     * a few percent into a chapter before its first line. The end marker below the last chapter is
     * left out for the same reason, or its end would count as seen only once the marker was.
     *
     * Null until the recycler has laid this chapter out, and again once it scrolls out of the window.
     */
    private fun boundsOf(slot: ChapterSlot): Pair<Int, Int>? {
        val position = adapter.positionOf(slot).takeIf { it >= 0 } ?: return null
        val item = recycler.layoutManager?.findViewByPosition(position) ?: return null
        val text = slot.block.container
        return (item.top + text.top) to text.height
    }

    /**
     * Puts the reader [fraction] of the way through [slot]. A chapter shorter than the viewport has no
     * room to seek inside itself, so any fraction lands on its start, as the WebView page's seek does.
     * That start is its first line rather than its item: the marker above means the two differ.
     */
    private fun scrollWithin(slot: ChapterSlot, fraction: Float) {
        val (top, height) = boundsOf(slot) ?: return
        recycler.scrollBy(0, ChapterScrollProgress.offsetFor(fraction, height, recycler.height) + top)
    }

    /** The chapter's text is set, so it joins the list, restyled first if the settings moved on while
     *  it rendered, since a restyle then had no views of it to reach. */
    private fun join(slot: ChapterSlot) {
        settings?.let { restyle(slot, it) }
        slot.rendered = true
        adapter.show(joined())
        // With no chapter headed anywhere this is the window growing around the reader, which must not
        // move them. Every chapter is landed, not only this one: a line near a chapter's end waits for
        // the chapter below it to join.
        if (slots.none { it.landing != null }) return
        // Posted so the freshly set text has been measured; before that the chapter has no height. Read
        // then rather than now, so a seek that lands in between is the one applied.
        recycler.post { joined().forEach(::land) }
    }

    /**
     * Puts the reader where [slot]'s landing names and clears it, and only then: one that cannot be applied
     * yet stays for the column's next layout. A line near the chapter's end can need the chapter below it
     * for room, which a redraw adds after this one, so a line the list stops short of stays until then.
     * A share of zero is a real position: the first line, below the item's marker.
     */
    private fun land(slot: ChapterSlot) {
        // A layout still to come moves the text, so what it measures now is where the chapter was.
        if (slot.block.container.isLayoutRequested) return
        when (val landing = slot.landing ?: return) {
            is Landing.Share -> {
                // The share is of the chapter's height with its images in it, and they land after the
                // text, so seeking now would measure the chapter short and put the reader past text
                // they have not read. Held until they arrive, which lays the column out again. A chapter
                // the list has not laid out has nowhere to scroll, and laying it out comes back here.
                if (slot.block.imagesLoading || boundsOf(slot) == null) return
                // Before the scroll, so the report the scroll sends is the landed position's.
                slot.landing = null
                scrollWithin(slot, landing.fraction)
            }
            is Landing.Line -> {
                val view = slot.block.chunkViews.getOrNull(landing.chunk) ?: return
                val top = lineTopOf(view, landing.offset) ?: return
                recycler.scrollBy(0, top - landing.y)
                if (lineTopOf(view, landing.offset) == landing.y) slot.landing = null
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createChunkView(settings: NovelReaderSettings): TextView =
        TextView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            NovelTextStyle.apply(this, settings, context)
            setTextIsSelectable(textSelectable)
            // Selectable text has one tap owner, the watcher, so a click listener here would double
            // every tap: the Editor swallows a click on the text but not one past its last line.
            // Without selection the click is the owner instead, because LinkOnlyMovementMethod
            // cancels the click a tap on a link queued and lets every other tap through to here.
            if (!textSelectable) setOnClickListener { onTap(touchDownX, touchDownY) }
            // Off on both branches: the click is dispatched by the movement method below, so leaving
            // it on would let the framework fire its own unchecked intent for the same tap.
            linksClickable = false
            movementMethod = if (textSelectable) {
                ArrowKeyMovementMethod.getInstance()
            } else {
                LinkOnlyMovementMethod
            }
        }

    /** One item per chapter in the window. */
    private inner class BlockAdapter : RecyclerView.Adapter<BlockAdapter.Holder>() {

        private var shown: List<ChapterSlot> = emptyList()

        fun slotAt(position: Int): ChapterSlot? = shown.getOrNull(position)

        fun positionOf(slot: ChapterSlot): Int = shown.indexOf(slot)

        /**
         * Dispatches the difference rather than invalidating everything, because a full invalidation
         * throws the reading position away: the layout manager keeps its anchor across an insert or
         * a removal it is told about, and cannot across a dataset change it is not.
         */
        fun show(next: List<ChapterSlot>) = keepingReaderStill {
            val previous = shown
            shown = next.toList()
            DiffUtil.calculateDiff(SlotDiff(previous, shown)).dispatchUpdatesTo(this)
        }

        /** Runs [change] and then [keepReaderStill], measured against the layout still showing so the
         *  change can be taken back out of it. */
        private inline fun keepingReaderStill(change: () -> Unit) {
            val anchor = visibleSlot()?.takeIf { canMeasureForCorrection() }
            val before = anchor?.let { boundsOf(it)?.first }
            val scrolledBefore = scrolled
            change()
            if (anchor != null && before != null) keepReaderStill(anchor, before, scrolledBefore)
        }

        /**
         * A seam or an edge's failure turning up or going away is growth above the reading chapter's
         * text but inside its item, which the layout manager anchors by the item's top, so the chapter
         * being read is put back once the change has laid out. Measured in `RecyclerPrependPositionTest`.
         * A window changes as the reader crosses a seam, usually mid-fling, so what they scrolled in
         * between is theirs and stays: only what the layout moved is taken back.
         */
        private fun keepReaderStill(anchor: ChapterSlot, before: Int, scrolledBefore: Long) = postCorrection {
            val after = boundsOf(anchor)?.first ?: return@postCorrection 0
            after - before + (scrolled - scrolledBefore).toInt()
        }

        /**
         * Identity is the chapter, and a chapter's own view never needs rebinding: its text is set into
         * the block, not into the holder. Its seam does, since the seam names the chapter above, so that
         * is its content: the rebind lands in the same layout pass as the neighbour arriving, rather than
         * growing the item in a pass of its own after the list has already settled around it.
         */
        private inner class SlotDiff(
            private val before: List<ChapterSlot>,
            private val after: List<ChapterSlot>,
        ) : DiffUtil.Callback() {

            override fun getOldListSize() = before.size

            override fun getNewListSize() = after.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                before[oldItemPosition].chapter.chapterId == after[newItemPosition].chapter.chapterId

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                before.getOrNull(oldItemPosition - 1)?.chapter?.chapterId ==
                    after.getOrNull(newItemPosition - 1)?.chapter?.chapterId

            override fun getChangePayload(oldItemPosition: Int, newItemPosition: Int): Any = SEAM_CHANGED
        }

        /** The seam marker sits above the chapter rather than between two items, so a position still
         *  names a chapter and the geometry stays a chapter's own bounds. The end marker and the two
         *  failure views are fixed children for the same reason: a window edge is a place in the text,
         *  not an item. */
        inner class Holder(
            val root: LinearLayout,
            var head: NovelBoundaryFailureView,
            var seam: NovelChapterSeamView,
            var end: NovelChapterSeamView,
            var tail: NovelBoundaryFailureView,
        ) : RecyclerView.ViewHolder(root) {
            /** What [head] and [tail] draw, null while each is hidden. */
            var headFailure: NovelReaderViewModel.BoundaryFailure? = null
            var tailFailure: NovelReaderViewModel.BoundaryFailure? = null
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val head = NovelBoundaryFailureView(parent.context).apply { isVisible = false }
            val seam = NovelChapterSeamView(parent.context, seam = null)
            val end = NovelChapterSeamView(parent.context, seam = null)
            val tail = NovelBoundaryFailureView(parent.context).apply { isVisible = false }
            val root = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                addView(head)
                addView(seam)
                addView(end)
                addView(tail)
            }
            return Holder(root, head, seam, end, tail)
        }

        /** A seam-only change rebinds just the seam: a full bind re-adds the chapter's text container,
         *  re-measuring every chunk of what may be the chapter on screen. */
        override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
            if (payloads.isNotEmpty() && payloads.all { it === SEAM_CHANGED }) {
                bindSeam(holder, position)
            } else {
                onBindViewHolder(holder, position)
            }
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.root.findChapterContainer()?.let(holder.root::removeView)
            val container = shown.getOrNull(position)?.block?.container ?: return
            (container.parent as? ViewGroup)?.removeView(container)
            // Between the seam above the chapter and the end marker or failure below it, which is the
            // order the reader scrolls through them in.
            holder.root.addView(container, CHAPTER_CHILD_INDEX)
            bindSeam(holder, position)
            bindEnd(holder, position)
            bindBoundaries(holder, position)
            if (!textSelectable) holder.root.setOnClickListener { onReaderTap(touchDownX, touchDownY) }
        }

        /** Lets go of the chapter, since a holder can sit in the pool long after its chapter left the
         *  window, and would keep that chapter's text and decoded images alive until it is reused. */
        override fun onViewRecycled(holder: Holder) {
            holder.root.findChapterContainer()?.let(holder.root::removeView)
        }

        /** The chapter's own container, which is whatever child is not one of the four fixtures. */
        private fun LinearLayout.findChapterContainer(): View? = (0 until childCount)
            .map(::getChildAt)
            .firstOrNull { it !is NovelBoundaryFailureView && it !is NovelChapterSeamView }

        /**
         * The marker above a chapter, which names the one that finished as well as this one. It
         * therefore depends on the chapter's neighbour rather than on the chapter, so it has to be
         * re-decided whenever the window changes and not only when a holder is first bound: a
         * chapter opened on its own has nothing above it, and a later prepend gives it one.
         */
        private fun bindSeam(holder: Holder, position: Int) {
            val finished = shown.getOrNull(position - 1)?.chapter
            val seam = finished?.let { NovelSeam.between(it, shown[position].chapter) }?.drawn()
            if (seam == holder.seam.seam) return
            holder.seam = holder.root.replace(holder.seam, NovelChapterSeamView(holder.root.context, seam))
        }

        /** The marker below the novel's last chapter, which depends on that chapter alone, so a full
         *  bind is the only point it can change. */
        private fun bindEnd(holder: Holder, position: Int) {
            val end = NovelSeam.end(shown[position].chapter)?.drawn()
            if (end == holder.end.seam) return
            holder.end = holder.root.replace(holder.end, NovelChapterSeamView(holder.root.context, end))
        }

        /**
         * Only the two ends of the window can have run out of text, so only they draw a failure.
         * A single-chapter window is both ends at once, which is the shape a first load leaves.
         */
        private fun bindBoundaries(holder: Holder, position: Int) {
            val above = failedPrevious.takeIf { position == 0 }
            if (above != holder.headFailure) {
                holder.headFailure = above
                holder.head = holder.root.replace(holder.head, failureView(above, forward = false))
            }
            val below = failedNext.takeIf { position == shown.lastIndex }
            if (below != holder.tailFailure) {
                holder.tailFailure = below
                holder.tail = holder.root.replace(holder.tail, failureView(below, forward = true))
            }
        }

        private fun failureView(failure: NovelReaderViewModel.BoundaryFailure?, forward: Boolean) =
            NovelBoundaryFailureView(context).apply {
                failure?.let { bind(it.message) { onRetryBoundary(forward) } }
                isVisible = failure != null
            }

        /**
         * Swaps [old] for [fresh] in its place. A fresh view rather than new state on the old one: a new
         * composition is measured in the layout pass that adds it, where a recomposition lands a frame
         * later and grows the item after the list has settled around it, which carried a short last
         * chapter off screen and put a correction for the growth a frame too early to see it.
         */
        private fun <T : View> LinearLayout.replace(old: View, fresh: T): T {
            val index = indexOfChild(old)
            removeViewAt(index)
            addView(fresh, index)
            return fresh
        }

        /** Re-draws the edges on the holders already bound, without rebinding their chapters. A failure
         *  turning up above the text the reader is in would push it down, so that is taken back. */
        fun refreshBoundaries() = keepingReaderStill {
            shown.indices.forEach { position ->
                val holder = recycler.findViewHolderForAdapterPosition(position) as? Holder ?: return@forEach
                bindBoundaries(holder, position)
            }
        }

        /** Re-decides every seam once the setting that hides them has changed. A change notice rather
         *  than the bound holders alone, since a holder cached off screen would come back with its old
         *  seam. One above the text the reader is in moves that text, so that is taken back. */
        fun refreshSeams() = keepingReaderStill { notifyItemRangeChanged(0, shown.size, SEAM_CHANGED) }

        override fun getItemCount(): Int = shown.size
    }

    /** This marker as the reader's setting draws it, or null where the setting hides it. */
    private fun NovelSeam.drawn(): NovelSeam? =
        takeIf { it.isShown(alwaysShowTransition = settings?.alwaysShowChapterTransition != false) }

    private companion object {
        /** Chapters the window holds at most: the previous one, the one being read, and as many past
         *  it as [NovelWindowReach] reaches over chapters that fit on one screen. */
        const val WINDOW_SIZE = 2 + NovelWindowReach.MAX_FORWARD

        /** Where the chapter's text sits among an item's fixed children: after the failure view for
         *  the edge above it and the seam marker, before the end marker and the failure view below. */
        const val CHAPTER_CHILD_INDEX = 2

        /** The one partial change an item takes: what its seam draws, since the chapter above it moved
         *  or the setting that hides seams changed. */
        val SEAM_CHANGED = Any()

        /** The frame rate the WebView renderer's per-frame speed was written against. */
        const val FRAMES_PER_SECOND = 60f
        const val NANOS_PER_SECOND = 1_000_000_000f

        /** How far sideways a swipe must run to count, in dp, also `core.js`'s number. */
        const val SWIPE_MIN_DP = 180f
    }
}

private fun NovelReaderSettings.paragraphShape() =
    ParagraphShape(paragraphIndent, paragraphSpacing, fontSize, bionicReading, margins.left + margins.right)
