package reikai.domain.download

/** One saved row of a download queue, the shape the manga and novel stores both persist, keyed by chapter. */
data class QueuedChapter(val entryId: Long, val chapterId: Long, val order: Int)
