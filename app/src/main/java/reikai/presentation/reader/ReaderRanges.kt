package reikai.presentation.reader

/** The bounds both readers' controls offer, so manga and novels cannot disagree. */
object ReaderRanges {
    val volumeKeyScrollPercent = 25..100
    val railHeightPercent = 65..100

    /** The rail height slider's stops, 5% apart. */
    val railHeightSteps = (railHeightPercent.last - railHeightPercent.first) / 5 - 1

    /** Auto-scroll's speed in tenths of a CSS pixel a frame, a long strip's and a novel's alike. */
    val autoScrollSpeedTenths = 2..40

    /** How long a paged auto-scroll shows each page, in seconds. */
    val autoScrollIntervalSeconds = 1..30
}
