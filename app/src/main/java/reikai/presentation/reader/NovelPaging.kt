package reikai.presentation.reader

import reikai.domain.reader.ChapterProgress
import reikai.domain.reader.seekTo
import kotlin.math.ceil

/**
 * The page rule both novel renderers read when paging: each measures where its lines sit in a chapter's
 * column, and this alone decides where the pages break and where a turn goes, so the two cannot disagree.
 * Every position is in the chapter item's own pixels, from the top of whatever the item draws first.
 */
object NovelPaging {

    /** A run of the column a page break may not cut, unless it is taller than a page. A [alone] band, the
     *  marker between two chapters, takes a page of its own. */
    data class Band(val top: Float, val bottom: Float, val alone: Boolean = false)

    /** One page: the column from [top], the first band on it, to [bottom], the last whole band's end. */
    data class Page(val top: Float, val bottom: Float) {
        val height: Float get() = bottom - top
    }

    /**
     * Cuts [bands] into pages at most [height] tall. Bands that overlap, as a line split into fragments
     * does, merge first. A page takes whole bands until the next would overflow it; a band taller than a
     * page is cut at page height and whatever follows it starts whole on the next page. A WebView chapter's
     * own script can answer the measure, so a page shorter than a pixel, a value that is not a number, and
     * more than [MAX_PAGES] pages are refused rather than cut until memory runs out.
     */
    fun cut(bands: List<Band>, height: Float): List<Page> {
        // A window too small for its margins has no room for a line, and cutting at no height would never end.
        if (!height.isFinite() || height < MIN_PAGE_HEIGHT) return emptyList()
        val pages = mutableListOf<Page>()
        var top = Float.NaN
        var bottom = Float.NaN
        fun close() {
            if (!top.isNaN()) pages += Page(top, bottom)
            top = Float.NaN
        }
        merge(bands.filter { it.top.isFinite() && it.bottom.isFinite() }).forEach { band ->
            if (pages.size >= MAX_PAGES) return pages
            if (band.alone) close()
            if (!top.isNaN() && band.bottom - top > height) close()
            if (top.isNaN()) top = band.top
            while (band.bottom - top > height) {
                if (pages.size >= MAX_PAGES) return pages
                pages += Page(top, top + height)
                top += height
            }
            bottom = band.bottom
            if (band.alone) close()
        }
        close()
        return pages
    }

    /** Sorted, empty bands dropped, overlapping ones joined. A band [Band.alone] never joins another. */
    private fun merge(bands: List<Band>): List<Band> {
        val merged = mutableListOf<Band>()
        bands.filter { it.bottom > it.top }.sortedBy { it.top }.forEach { band ->
            val last = merged.lastOrNull()
            if (last != null && !last.alone && !band.alone && band.top < last.bottom - OVERLAP_SLACK) {
                merged[merged.lastIndex] = last.copy(bottom = maxOf(last.bottom, band.bottom))
            } else {
                merged += band
            }
        }
        return merged
    }

    /** The page holding [y], the last one starting at or above it, so a position between two pages reads the
     *  earlier one. Zero above the first page and for no pages at all. */
    fun pageAt(pages: List<Page>, y: Float): Int =
        pages.indexOfLast { it.top <= y + OVERLAP_SLACK }.coerceAtLeast(0)

    /** Where a turn from page [index] of [count] in [chapterId] goes, in [window]'s chapters in reading order. */
    fun turn(window: List<Long>, chapterId: Long, index: Int, count: Int, forward: Boolean): Turn {
        val next = if (forward) index + 1 else index - 1
        if (next in 0..<count) return Turn.Within(chapterId, next)
        val position = window.indexOf(chapterId)
        val neighbour = window.getOrNull(if (forward) position + 1 else position - 1)
            ?.takeIf { position >= 0 }
            ?: return Turn.Beyond(forward)
        return if (forward) Turn.FirstOf(neighbour) else Turn.LastOf(neighbour)
    }

    sealed interface Turn {
        data class Within(val chapterId: Long, val index: Int) : Turn

        data class FirstOf(val chapterId: Long) : Turn

        data class LastOf(val chapterId: Long) : Turn

        /** Past the window's edge, which only stepping to another chapter can reach. */
        data class Beyond(val forward: Boolean) : Turn
    }

    /**
     * How far through the chapter page [index] is, in the scrolled column's own unit: how far its first line,
     * [Page.top] less the text's top [textTop], is through the [scrollable] range (the text's height less one
     * screen). Rounded up, so the page's own report lands back on it. As in the manga pager, the first page
     * reads none of it, below the chapter's top margin, and only the last page reads all of it; a chapter of
     * one page reads none, as one that fits reads while scrolling.
     */
    fun progressOf(pages: List<Page>, index: Int, textTop: Float, scrollable: Float): ChapterProgress.Percent {
        val full = ChapterProgress.Percent.FULL
        if (pages.size <= 1 || index <= 0) return ChapterProgress.Percent(0)
        if (index >= pages.lastIndex) return ChapterProgress.Percent.END
        if (scrollable <= 0f) return ChapterProgress.Percent(0)
        val share = (pages[index].top - textTop).toDouble() / scrollable
        return ChapterProgress.Percent(ceil(share * full).toLong().coerceIn(0L, full - 1))
    }

    /**
     * The page a stored [progress] reopens on, whichever mode saved it: the page holding the line that far
     * through the [scrollable] range below [textTop], and the last page for all of it. A share past the
     * scroll's end lands on the page holding that end, never later.
     */
    fun landingIndex(pages: List<Page>, progress: ChapterProgress.Percent, textTop: Float, scrollable: Float): Int {
        if (progress.hundredths >= ChapterProgress.Percent.FULL) return pages.lastIndex.coerceAtLeast(0)
        val within = scrollable.coerceAtLeast(0f) * progress.hundredths.toDouble() / ChapterProgress.Percent.FULL
        return pageAt(pages, (textTop + within).toFloat())
    }

    /** The page the paged slider's [fraction] names, the inverse of [ChapterProgress.Pages]' own. */
    fun indexFor(fraction: Float, count: Int): Int =
        (ChapterProgress.Pages(0, count).seekTo(fraction.coerceIn(0f, 1f)) as ChapterProgress.Pages).lastPageRead

    /** Fragments of one line can overlap the next by a sub-pixel; that much never joins two lines. */
    private const val OVERLAP_SLACK = 1f

    private const val MIN_PAGE_HEIGHT = 1f

    /** Far past any real chapter on the smallest window; a count above it is a broken measure. */
    private const val MAX_PAGES = 10_000
}
