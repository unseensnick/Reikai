package reikai.domain.chapter

/** The rows a details toolbar bulk download picks from, one rule for manga and novels. */
object DownloadCandidates {

    /**
     * [shown] (the filtered list on screen) when the reader skips filtered chapters, else [stored], every
     * chapter the entry holds. Hidden chapters are never queued, even while they are being shown.
     */
    fun <T> rows(shown: List<T>, stored: List<T>, skipFiltered: Boolean, isHidden: (T) -> Boolean): List<T> =
        (if (skipFiltered) shown else stored).filterNot(isHidden)
}
