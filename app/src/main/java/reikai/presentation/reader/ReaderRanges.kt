package reikai.presentation.reader

/** The bounds both readers' controls offer, so manga and novels cannot disagree. */
object ReaderRanges {
    val volumeKeyScrollPercent = 25..100
    val railHeightPercent = 65..100

    /** The rail height slider's stops, 5% apart. */
    val railHeightSteps = (railHeightPercent.last - railHeightPercent.first) / 5 - 1
}
