package reikai.presentation.reader.text

/**
 * How far past the chapter being read the seamless window reaches. One chapter, unless that one fits
 * on a single screen: then the reader sees all of it without leaving the chapter above, which is the
 * crossing that grows the window, so the window stalled there with the next chapter never loading.
 * It reaches on past every chapter that fits, up to [MAX_FORWARD], so there is always scroll room
 * to cross into the next one.
 */
object NovelWindowReach {

    /** Enough for a run of chapters shorter than a screen, without loading a whole volume of them. */
    const val MAX_FORWARD = 3

    /**
     * [next] and the chapters after it, stopping after the first that does not fit on one screen.
     * [after] gives the chapter that follows one in reading order, null at the end of the novel.
     */
    fun forward(next: Long?, after: (Long) -> Long?, fitsOnScreen: (Long) -> Boolean): List<Long> {
        val reach = mutableListOf<Long>()
        var id = next
        while (id != null && reach.size < MAX_FORWARD) {
            reach += id
            if (!fitsOnScreen(id)) break
            id = after(id)
        }
        return reach
    }

    /**
     * The chapters the window publishes, in order: [previous] and each of the [forward] reach once
     * [isCached], and never one past a reach chapter that is not. The viewports only grow at an end,
     * so a chapter published below a gap left the gap for good, and a crossing then read it unseen.
     */
    fun windowIds(previous: Long?, current: Long, forward: List<Long>, isCached: (Long) -> Boolean): List<Long> =
        listOfNotNull(previous?.takeIf(isCached), current) + forward.takeWhile(isCached)

    /**
     * Whether the chapter before the one being read may join the window yet: once every chapter of the
     * [forward] reach has [resolved], arrived or failed. A chapter added above keeps the reader's place
     * only while there is room below to scroll into, which an opened chapter shorter than the screen
     * lacks until the ones after it are there. One the window [alreadyHeld] stays.
     */
    fun previousMayJoin(forward: List<Long>, resolved: (Long) -> Boolean, alreadyHeld: Boolean): Boolean =
        alreadyHeld || forward.all(resolved)
}
