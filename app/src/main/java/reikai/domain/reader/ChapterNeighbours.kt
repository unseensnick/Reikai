package reikai.domain.reader

/**
 * The chapter one step from [index] in reading order, deliberately asymmetric. Going back steps once
 * over the whole list, so the chapter just finished is always reachable even after it is marked read.
 * Going forward skips to the next chapter still eligible, because "skip chapters marked read" means do
 * not stop on one as you move forward, not make it unreachable.
 *
 * [isForwardEligible] is only consulted going forward, and answers for the reader's skip settings.
 */
fun <T> List<T>.neighbourChapter(
    index: Int,
    forward: Boolean,
    isForwardEligible: (T) -> Boolean,
): T? {
    if (index < 0) return null
    return if (forward) {
        asSequence().drop(index + 1).firstOrNull(isForwardEligible)
    } else {
        getOrNull(index - 1)
    }
}

/**
 * Both neighbours of [index] once the reader scrolled into it out of [departed], or null after a step,
 * a pick or an open. Toward [departed] the neighbour is the nearest chapter the reader scrolled through:
 * forward, a skipped read chapter was never on screen, and putting it there dropped the one that is;
 * backward, the strip walks every chapter, so the one ahead is simply the next in the list.
 */
fun <T> List<T>.neighbourChapters(index: Int, departed: T?, isForwardEligible: (T) -> Boolean): Pair<T?, T?> {
    val previous = neighbourChapter(index, forward = false, isForwardEligible)
    val next = neighbourChapter(index, forward = true, isForwardEligible)
    val departedAt = departed?.let(::indexOf) ?: -1
    if (index < 0 || departedAt < 0 || departedAt == index) return previous to next
    if (departedAt > index) return previous to this[index + 1]
    val passed = (index - 1 downTo departedAt).first { this[it] == departed || isForwardEligible(this[it]) }
    return this[passed] to next
}

/**
 * What download-ahead queues: up to [count] chapters of [ordered] from [from] on that are not read,
 * a read one passed over rather than counted, as manga's `GetNextChapters(onlyUnread)` does. Asked
 * with the group's answer for read, so a chapter another source finished is not fetched again.
 */
fun <T> chaptersToDownloadAhead(ordered: List<T>, from: Int, count: Int, isRead: (T) -> Boolean): List<T> =
    if (from < 0) emptyList() else ordered.asSequence().drop(from).filterNot(isRead).take(count).toList()
