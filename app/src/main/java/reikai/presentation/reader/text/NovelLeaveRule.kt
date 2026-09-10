package reikai.presentation.reader.text

/**
 * Which chapters scrolling forward reads. Scrolling past a chapter reads it whatever its length, as
 * the manga reader's continuous mode does when a boundary is scrolled clear, and it is how a chapter
 * shorter than one screen gets read at all: it has no scroll room, so it never reaches the end a
 * percent measures, and marking it on open would read a chapter nobody read. Both renderers report
 * the crossing this runs on. Ruling in content-layer-reader-surface.md.
 */
object NovelLeaveRule {

    /**
     * The chapters a forward scroll from [from] to [to] has left behind: [from], and any the window
     * held between them, which a fast fling can carry the reader over without a frame landing in.
     * Empty for a backward move, or when either chapter is not in [window].
     */
    fun passedGoingForward(window: List<Long>, from: Long, to: Long): List<Long> {
        val start = window.indexOf(from)
        val end = window.indexOf(to)
        if (start < 0 || end <= start) return emptyList()
        return window.subList(start, end)
    }
}
