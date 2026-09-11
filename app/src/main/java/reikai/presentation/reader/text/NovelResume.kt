package reikai.presentation.reader.text

/**
 * Where a chapter opens, shared by every novel reader. One already read opens at its start, which is
 * upstream's rule for a manga chapter (`ChapterLoader` restores the last page only while it is unread):
 * a finished chapter keeps the position it ended at, so it reopened on its last screen and finished
 * again the moment it opened.
 */
object NovelResume {

    /** [lastTextProgress] is the stored 0..10000, hundredths of a percent; the result is 0..100. */
    fun percent(read: Boolean, lastTextProgress: Long): Int =
        if (read) 0 else (lastTextProgress / 100).coerceIn(0L, 100L).toInt()

    /**
     * Where a renderer starting over on a window it was already showing puts the reader, as a chapter
     * and a percent. A percent cannot place the reader within a chapter's last screen: every position
     * with its last line on screen reads 100 (`ChapterScrollProgress`), and 100 lands that line at the
     * bottom, up to a screen short of the chapter below it. With [nextId] held below, the reader goes to
     * that chapter's start instead, which was on screen.
     */
    fun relanding(chapterId: Long, livePercent: Int, nextId: Long?): Pair<Long, Int> =
        if (livePercent >= 100 && nextId != null) nextId to 0 else chapterId to livePercent
}
