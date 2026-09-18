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
     * The chapter could not be loaded. [message] is what to tell the reader, null where the failure
     * carried nothing worth showing. [canKeepReading] is false when nothing reached the screen, so
     * giving up closes the reader, as manga does, instead of leaving it blank with no way back.
     * [attempt] tells one failure from the next: the state is conflated, so a repeat of the same
     * failure, with the Loading between them missed, would otherwise read as nothing new at all.
     */
    data class Failed(val message: String?, val canKeepReading: Boolean, val attempt: Long) : ReaderLoadState {
        companion object {
            private val attempts = AtomicLong()

            /** A fresh [attempt] for each failure, the one counter both content types stamp from. */
            fun nextAttempt(): Long = attempts.incrementAndGet()
        }
    }
}
