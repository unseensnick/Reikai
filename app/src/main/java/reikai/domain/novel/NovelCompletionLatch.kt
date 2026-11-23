package reikai.domain.novel

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Finishing a chapter happens once while anything is reading it. A scroll reports every whole percent from the
 * threshold to 100 and each one completes the chapter, so without this every one of them pushed to the trackers
 * and trimmed the downloads again. One for the app, since a reader and read aloud with no reader can both finish
 * the chapter being spoken. Unmarking a chapter releases it ([SetNovelReadStatus]), and once no reader or read
 * aloud [hold]s the latch every chapter can be finished again, as each reading session could before.
 */
@SingleIn(AppScope::class)
@Inject
class NovelCompletionLatch {

    private val finished = mutableSetOf<Long>()
    private var holders = 0

    /** True for the caller that gets to finish [chapterId]. */
    @Synchronized
    fun claim(chapterId: Long): Boolean = finished.add(chapterId)

    @Synchronized
    fun release(chapterIds: Collection<Long>) {
        finished.removeAll(chapterIds.toSet())
    }

    /** Held by a reader or a read aloud session for as long as it can finish chapters. */
    @Synchronized
    fun hold(): AutoCloseable {
        holders++
        var closed = false
        return AutoCloseable {
            synchronized(this) {
                if (closed) return@synchronized
                closed = true
                if (--holders == 0) finished.clear()
            }
        }
    }
}
