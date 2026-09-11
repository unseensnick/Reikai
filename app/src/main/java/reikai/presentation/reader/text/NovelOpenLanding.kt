package reikai.presentation.reader.text

/**
 * Which chapter is being read while a renderer lands on the one it was opened on. A landing can leave
 * an earlier chapter holding the top of the screen: a short last chapter sits at the bottom under the
 * end of the one above, since a list cannot scroll its last item to the top. The renderer names that
 * chapter, at its end, and taking its word read a chapter nobody had scrolled. So an earlier chapter
 * takes over only once the reader moves. A later one never waits, because the window grows below
 * without moving the reader, so reaching one takes a scroll.
 */
class NovelOpenLanding(private val openedId: Long, private val isEarlier: (Long) -> Boolean) {

    /** Set once the reader has moved, and never cleared: from there on, the renderer's word stands.
     *  Read off the thread the reports arrive on. */
    @Volatile
    var settled = false
        private set

    private var openedAt: Int? = null
    private var held: Pair<Long, Int>? = null
    private var lastReport: Pair<Long, Int>? = null

    @Volatile
    private var moveArmed = false

    /** Whether [chapterId] may become the chapter being read. */
    fun mayRead(chapterId: Long): Boolean = settled || !isEarlier(chapterId)

    /**
     * The reader dragged the page or pressed a key that scrolls it. That alone settles nothing, since
     * a drag at the end of the list scrolls nothing; the next report differing from the last, in
     * chapter or percent, does. A percent saturates, at 0 for a chapter that fits and at 100 across a
     * chapter's last screen, so scrolling up out of the opened chapter's top changes neither percent
     * for a screen or more, while the chapter the renderer names changes at once.
     */
    fun readerMoved() {
        moveArmed = true
    }

    /**
     * Whether a position the renderer reported is the reader's. An earlier chapter's first position is
     * where the landing put it, and so is a repeat of it; any other position, of any chapter, is the
     * reader moving.
     */
    fun counts(chapterId: Long, percent: Int): Boolean {
        if (settled) return true
        val previous = lastReport
        lastReport = chapterId to percent
        if (moveArmed && previous != null && previous != lastReport) {
            settled = true
            return true
        }
        if (chapterId == openedId) {
            val first = openedAt ?: percent.also { openedAt = it }
            if (percent != first) settled = true
            return true
        }
        if (!isEarlier(chapterId)) {
            settled = true
            return true
        }
        val landed = held
        if (landed == null || landed.first != chapterId) {
            held = chapterId to percent
            return false
        }
        if (landed.second == percent) return false
        settled = true
        return true
    }
}
