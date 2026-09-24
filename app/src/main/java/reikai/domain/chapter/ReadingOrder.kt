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

    /**
     * [inReadingOrder] with the chapters the user hid moved behind the rest, each part keeping its order.
     * A pick that takes the first match then passes over a hidden chapter while any other qualifies, and
     * still opens one when only hidden chapters are left, so a continue button or a row never goes dead.
     */
    fun <T> hiddenLast(inReadingOrder: List<T>, isHidden: (T) -> Boolean): List<T> {
        val (hidden, shown) = inReadingOrder.partition(isHidden)
        return if (hidden.isEmpty()) inReadingOrder else shown + hidden
    }

    /** Everything read before [isPointer], empty when the pointer is not in the list at all. */
    fun <T> before(inReadingOrder: List<T>, isPointer: (T) -> Boolean): List<T> {
        val pointer = inReadingOrder.indexOfFirst(isPointer)
        return if (pointer <= 0) emptyList() else inReadingOrder.take(pointer)
    }
}
