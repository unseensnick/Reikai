package reikai.domain.chapter

import eu.kanade.presentation.manga.DownloadAction

/**
 * The rows a details toolbar bulk download picks from, and which of them an action queues: one rule
 * for manga and novels.
 */
object DownloadCandidates {

    /**
     * [shown] (the filtered list on screen) when the reader skips filtered chapters, else [stored], every
     * chapter the entry holds. Hidden chapters are never queued, even while they are being shown.
     */
    fun <T> rows(shown: List<T>, stored: List<T>, skipFiltered: Boolean, isHidden: (T) -> Boolean): List<T> =
        (if (skipFiltered) shown else stored).filterNot(isHidden)

    /**
     * The chapters [action] queues from [inReadingOrder]. [isExcluded] (downloaded or already queued)
     * drops a chapter BEFORE take(N), or a repeated "next N" would hand back the same first N.
     */
    fun <T> forAction(
        inReadingOrder: List<T>,
        action: DownloadAction,
        isRead: (T) -> Boolean,
        isBookmarked: (T) -> Boolean,
        isExcluded: (T) -> Boolean,
    ): List<T> {
        val unread = inReadingOrder.filterNot { isRead(it) || isExcluded(it) }
        return when (action) {
            DownloadAction.NEXT_1_CHAPTER -> unread.take(1)
            DownloadAction.NEXT_5_CHAPTERS -> unread.take(5)
            DownloadAction.NEXT_10_CHAPTERS -> unread.take(10)
            DownloadAction.NEXT_25_CHAPTERS -> unread.take(25)
            DownloadAction.UNREAD_CHAPTERS -> unread
            DownloadAction.BOOKMARKED_CHAPTERS -> inReadingOrder.filter { isBookmarked(it) && !isExcluded(it) }
        }
    }
}
