package reikai.presentation.reader

/**
 * Which of the manga reader's overlapping chapter switches may land. Each loads in its own background
 * job, so without this the last to finish won even when stale: a scroll into the next chapter and
 * straight back left the reader on the one it had left. A pick, a step or the first open always
 * lands and outranks any switch a page asked for before it. Main thread only.
 */
internal class ChapterSwitches {

    private var latest = 0L

    /** The chapter a page switch is loading, which later pages are compared against until it ends. */
    private var pendingChapterId: Long? = null
    private var pendingToken = 0L

    /** A token for switching to [chapterId], or null when it is already active or already on its way. */
    fun requestFromPage(chapterId: Long, activeChapterId: Long?): Long? {
        if (chapterId == (pendingChapterId ?: activeChapterId)) return null
        pendingChapterId = chapterId
        pendingToken = ++latest
        return pendingToken
    }

    /** A pick, a step or the first open, which supersedes every page switch still loading. */
    fun beginExplicit() {
        latest++
        pendingChapterId = null
    }

    /** Whether the page switch holding [token] may land, because nothing started after it. */
    fun isLatest(token: Long): Boolean = token == latest

    /** The page switch holding [token] landed, failed or was dropped. A later page decides afresh. */
    fun finish(token: Long) {
        if (token == pendingToken) pendingChapterId = null
    }
}
