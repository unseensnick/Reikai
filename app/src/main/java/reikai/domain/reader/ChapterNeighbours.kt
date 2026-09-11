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
 * What download-ahead queues: up to [count] chapters of [ordered] from [from] on that are not read,
 * a read one passed over rather than counted, as manga's `GetNextChapters(onlyUnread)` does. Asked
 * with the group's answer for read, so a chapter another source finished is not fetched again.
 */
fun <T> chaptersToDownloadAhead(ordered: List<T>, from: Int, count: Int, isRead: (T) -> Boolean): List<T> =
    if (from < 0) emptyList() else ordered.asSequence().drop(from).filterNot(isRead).take(count).toList()
