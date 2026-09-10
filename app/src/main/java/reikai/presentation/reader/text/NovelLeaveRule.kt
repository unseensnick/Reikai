package reikai.presentation.reader.text

import org.jsoup.Jsoup

/**
 * Which chapters leaving reads. Scrolling past a chapter reads it whatever its length, as the manga
 * reader's continuous mode does when a boundary is scrolled clear, and it is how a chapter shorter
 * than one screen gets read at all: it has no scroll room, so it never reaches the end a percent
 * measures. Only the last chapter, which cannot be left, is read on sight. Both renderers report the
 * crossing and the sighting this runs on. Ruling in content-layer-reader-surface.md.
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

    /**
     * Whether a chapter's last line reaching the screen reads it, which holds only for the last chapter
     * the reader can reach: it has nothing to be left into, as manga's last page has nothing after it.
     * One with nothing in it is never read this way, since an empty chapter always fits on screen.
     */
    fun readsOnReachingEnd(hasNext: Boolean, html: String): Boolean {
        if (hasNext) return false
        val body = Jsoup.parseBodyFragment(html).body()
        return body.text().isNotBlank() || body.selectFirst("img") != null
    }
}
