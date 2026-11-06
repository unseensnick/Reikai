package reikai.presentation.reader

/** The bounds both readers' controls offer, so manga and novels cannot disagree. */
object ReaderRanges {
    /** The steps a float setting is counted in on a slider or stepper; see `scaled`. */
    const val TENTHS = 10
    const val PERCENT = 100

    val volumeKeyScrollPercent = 25..100
    val railHeightPercent = 65..100

    /** The rail height slider's stops, 5% apart. */
    val railHeightSteps = (railHeightPercent.last - railHeightPercent.first) / 5 - 1

    /** Auto-scroll's speed in tenths of a CSS pixel a frame, a long strip's and a novel's alike. */
    val autoScrollSpeedTenths = 2..40

    /** How long a paged auto-scroll shows each page, in seconds. */
    val autoScrollIntervalSeconds = 1..30

    /**
     * The e-ink flash's length in steps of [FLASH_STEP_MILLIS], 20 ms to 1.5 s. Fine enough for a fast panel to
     * go below the 100 ms default, and every whole tenth of a second the older slider stored is still a stop.
     */
    val flashDurationSteps = 1..75

    const val FLASH_STEP_MILLIS = 20

    /** How many page turns apart the e-ink flash runs. */
    val flashPageInterval = 1..10

    /** How many pages past the one on screen a chapter loads; the loader clamps a stored value to it. */
    val preloadPages = 1..20
}
