package reikai.domain.reader

import reikai.domain.readinglog.PaceUnit

/**
 * Where read-aloud is in the chapter it speaks: the words before [paragraph], the one being spoken, of
 * [words]. It moves a paragraph at a time, since the voice reports nothing finer.
 */
data class ReadAloudProgress(val chapterId: Long, val paragraph: Int, val wordsSpoken: Long, val words: Long)

/** One moment of read-aloud: the chapter, when, the paragraph being spoken and the words before it. */
data class ListeningMark(val chapterId: Long, val atMs: Long, val paragraph: Int, val words: Long)

/**
 * What the reader knows about listening right now. [msPerWord] is the voice's pace measured since play,
 * so a speed change shows within a minute; [listened] is the time this step credits to a chapter.
 */
data class Listening(
    val anchor: ListeningMark? = null,
    val last: ListeningMark? = null,
    val msPerWord: Double? = null,
    val listened: Pair<Long, Long>? = null,
) {

    /** The state after read-aloud reports [playing] at [progress], [nowMs] on a monotonic clock. */
    fun next(playing: Boolean, progress: ReadAloudProgress?, nowMs: Long): Listening {
        val listened = last?.let { it.chapterId to (nowMs - it.atMs).coerceIn(0, MAX_STEP_MS) }
            ?.takeIf { it.second > 0 }
        if (!playing || progress == null) return Listening(listened = listened)
        val mark = ListeningMark(progress.chapterId, nowMs, progress.paragraph, progress.wordsSpoken)
        val from = anchor?.takeIf { it.chapterId == mark.chapterId && it.words <= mark.words && !skipped(mark) }
            // A new chapter keeps the voice's pace, since the voice has not changed.
            ?: return Listening(anchor = mark, last = mark, msPerWord = msPerWord, listened = listened)
        val words = mark.words - from.words
        val elapsed = mark.atMs - from.atMs
        val measured = (elapsed.toDouble() / words)
            .takeIf { words >= MIN_WORDS && elapsed >= MIN_MS && it in WORD_BOUNDS }
        return Listening(anchor = from, last = mark, msPerWord = measured ?: msPerWord, listened = listened)
    }

    /**
     * Whether [mark] is a skip ahead rather than speech: the voice speaks one paragraph after another, so
     * passing over one is a skip however long the paragraph before it played, and so is reaching the next
     * faster than any voice reads it.
     */
    private fun skipped(mark: ListeningMark): Boolean {
        val previous = last?.takeIf { it.chapterId == mark.chapterId } ?: return false
        return mark.paragraph > previous.paragraph + 1 ||
            (mark.words - previous.words) * PaceUnit.WORD.floorMs > mark.atMs - previous.atMs
    }
}

/** Enough speech that one long or short paragraph does not swing the pace. */
private const val MIN_WORDS = 60L
private const val MIN_MS = 10_000L

/** A step longer than this is a clock that jumped, not listening. */
private const val MAX_STEP_MS = 30L * 60 * 1000

private val WORD_BOUNDS = PaceUnit.WORD.floorMs..PaceUnit.WORD.capMs
