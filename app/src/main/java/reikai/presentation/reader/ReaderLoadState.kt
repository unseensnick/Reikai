package reikai.presentation.reader

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
     */
    data class Failed(val message: String?, val canKeepReading: Boolean) : ReaderLoadState
}
