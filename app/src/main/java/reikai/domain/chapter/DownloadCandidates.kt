package reikai.domain.chapter

import eu.kanade.presentation.manga.DownloadAction

/**
 * The rows a bulk download picks from, and which of them an action queues: one rule for manga and
 * novels, from the details toolbar and the library's multi-select alike.
 */
object DownloadCandidates {

    /**
     * [shown] (the filtered list on screen) when the reader skips filtered chapters, else [stored], every
     * chapter the entry holds.
     */
    fun <T> rows(shown: List<T>, stored: List<T>, skipFiltered: Boolean): List<T> =
        if (skipFiltered) shown else stored

    /**
     * The chapters [action] queues from [inReadingOrder]. [isHidden] and [isExcluded] (downloaded or
     * already queued) drop a chapter BEFORE take(N), or a repeated "next N" would hand back the same
     * first N. A hidden chapter is never queued by any action, bookmarked included.
     */
    fun <T> forAction(
        inReadingOrder: List<T>,
        action: DownloadAction,
        isRead: (T) -> Boolean,
        isBookmarked: (T) -> Boolean,
        isHidden: (T) -> Boolean,
        isExcluded: (T) -> Boolean,
    ): List<T> {
        val candidates = inReadingOrder.filterNot { isHidden(it) || isExcluded(it) }
        val unread = candidates.filterNot(isRead)
        return when (action) {
            DownloadAction.NEXT_1_CHAPTER -> unread.take(1)
            DownloadAction.NEXT_5_CHAPTERS -> unread.take(5)
            DownloadAction.NEXT_10_CHAPTERS -> unread.take(10)
            DownloadAction.NEXT_25_CHAPTERS -> unread.take(25)
            DownloadAction.UNREAD_CHAPTERS -> unread
            DownloadAction.BOOKMARKED_CHAPTERS -> candidates.filter(isBookmarked)
        }
    }
}
