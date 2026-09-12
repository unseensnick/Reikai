package reikai.domain.chapter

/**
 * Where reading goes next, and what sits before a chapter, asked of a list in the order the chapter list
 * is shown. One rule for manga and novels: only the comparator that builds the shown list is per type
 * (`getChapterSort` for manga, `readingOrderComparator` for novels). Source order is no stand-in for it,
 * because a list sorted by name or upload date reaches a different chapter next.
 */
object ReadingOrder {

    /** [shown] as the reader walks it, earliest first: a descending list runs from its end back. */
    fun <T> of(shown: List<T>, sortDescending: Boolean): List<T> =
        if (sortDescending) shown.asReversed() else shown

    /** The chapter reading resumes at: the earliest one [isRead] does not claim. */
    fun <T> nextToRead(inReadingOrder: List<T>, isRead: (T) -> Boolean): T? =
        inReadingOrder.firstOrNull { !isRead(it) }

    /** Everything read before [isPointer], empty when the pointer is not in the list at all. */
    fun <T> before(inReadingOrder: List<T>, isPointer: (T) -> Boolean): List<T> {
        val pointer = inReadingOrder.indexOfFirst(isPointer)
        return if (pointer <= 0) emptyList() else inReadingOrder.take(pointer)
    }
}
