package reikai.presentation.reader.text

/**
 * Finishing a chapter happens once a session. A scroll reports every whole percent from 97 to 100 and
 * each one completes the chapter, so without this every one of them pushed to the trackers and trimmed
 * the downloads again. Unmarking a chapter releases it, since it can then be finished again.
 */
class NovelCompletionLatch {

    private val finished = mutableSetOf<Long>()

    /** True for the caller that gets to finish [chapterId]. */
    @Synchronized
    fun claim(chapterId: Long): Boolean = finished.add(chapterId)

    @Synchronized
    fun release(chapterIds: Collection<Long>) {
        finished.removeAll(chapterIds.toSet())
    }
}
