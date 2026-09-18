package reikai.domain.chapter

/** A chapter a sync is adding, with the state its source gave it. */
data class ArrivingChapter(val number: Double, val read: Boolean = false, val bookmark: Boolean = false)

/** A chapter already stored, or one the sync is removing. */
data class StoredChapter(
    val number: Double,
    val read: Boolean,
    val bookmark: Boolean = false,
    val dateFetch: Long = 0L,
)

/** What an added chapter is stored as. [isChangedOrDuplicate] keeps it out of the new-chapter results. */
data class Arrival(val read: Boolean, val bookmark: Boolean, val dateFetch: Long, val isChangedOrDuplicate: Boolean)

/**
 * Upstream `SyncChaptersWithSource`'s rules for the chapters a sync adds, which the manga and novel syncs
 * both call. Fetch dates count down from [now] in source order. With [markDuplicateAsRead], a chapter
 * numbered like a stored read one arrives read. One numbered like a [removed] one takes its state and its
 * earliest fetch date, so a re-listed chapter is not new. An unnumbered chapter (below zero) matches nothing.
 */
fun chapterArrivals(
    added: List<ArrivingChapter>,
    stored: List<StoredChapter>,
    removed: List<StoredChapter>,
    markDuplicateAsRead: Boolean,
    now: Long,
): List<Arrival> {
    val readNumbers = stored.filter { it.read && it.number >= 0.0 }.map { it.number }.toSet()
    val removedByNumber = removed.groupBy { it.number }
    return added.mapIndexed { index, chapter ->
        val duplicate = markDuplicateAsRead && chapter.number in readNumbers
        val twins = removedByNumber[chapter.number].takeIf { chapter.number >= 0.0 }
        if (twins == null) {
            Arrival(chapter.read || duplicate, chapter.bookmark, now + added.size - index, duplicate)
        } else {
            Arrival(
                read = twins.any { it.read },
                bookmark = twins.any { it.bookmark },
                dateFetch = twins.minOf { it.dateFetch },
                isChangedOrDuplicate = true,
            )
        }
    }
}
