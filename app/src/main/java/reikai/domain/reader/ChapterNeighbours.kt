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
