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
     * The chapter could not be loaded. [message] is what to tell the reader, null where there is nothing
     * worth showing. [canKeepReading] is false when nothing reached the screen, so giving up closes the
     * reader, as manga does, rather than leaving it blank. [chapterId] is the chapter that failed, whose
     * page the dialog offers, null where it is not one chapter. [attempt] tells one failure from the
     * next: the state is conflated, so a repeat with the Loading between missed would read as nothing
     * new. Stamped by default, so no site can report a failure equal to the last one.
     */
    data class Failed(
        val message: String?,
        val canKeepReading: Boolean,
        val chapterId: Long?,
        val attempt: Long = nextAttempt(),
    ) : ReaderLoadState {
        companion object {
            private val attempts = AtomicLong()

            /** A fresh [attempt] for each failure, the one counter both content types stamp from. */
            fun nextAttempt(): Long = attempts.incrementAndGet()
        }
    }
}
