package reikai.domain.track

/**
 * Where a tracker falls back to after an unread: the highest chapter still read that has a recognised
 * number, or null when none does. A site's own tracker and NovelUpdates both move back to it.
 */
fun <T> highestStillRead(chapters: Iterable<T>, isRead: (T) -> Boolean, numberOf: (T) -> Double): T? =
    chapters.filter { isRead(it) && numberOf(it) > 0 }.maxByOrNull(numberOf)
