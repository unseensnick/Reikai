package reikai.presentation.reader

import java.util.concurrent.atomic.AtomicLong

/**
 * What the session is doing about the chapter it is meant to be showing, so the host can say so
 * instead of leaving the reader on a blank page or on the chapter before.
 */
sealed interface ReaderLoadState {

    /** A chapter is on screen and nothing is in flight. */
    data object Idle : ReaderLoadState

    data object Loading : ReaderLoadState

    /**
     * The chapter could not be loaded. [message] is what to tell the reader, null for nothing worth showing.
     * [canKeepReading] is false when nothing reached the screen, so giving up closes the reader rather than
     * leaving it blank. [chapterId] is the failed chapter, whose page the dialog offers, null where it is not
     * one, and [blockedUrl] the page a site challenge blocked, offered in its place. [attempt] tells one
     * failure from the next, since the state is conflated; stamped by default, so no site repeats the last.
     */
    data class Failed(
        val message: String?,
        val canKeepReading: Boolean,
        val chapterId: Long?,
        val attempt: Long = nextAttempt(),
        val blockedUrl: String? = null,
    ) : ReaderLoadState {
        companion object {
            private val attempts = AtomicLong()

            /** A fresh [attempt] for each failure, the one counter both content types stamp from. */
            fun nextAttempt(): Long = attempts.incrementAndGet()
        }
    }
}
