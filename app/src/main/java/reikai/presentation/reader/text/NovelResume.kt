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
}
