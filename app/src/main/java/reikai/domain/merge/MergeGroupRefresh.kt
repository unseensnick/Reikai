package reikai.domain.merge

/**
 * One refresh of a merged group, shared by both details screens: [anchor] first, then each sibling.
 * Every member runs even after one fails, and the first failure comes back. A sibling whose source is
 * not installed ([sourceOf] null) is skipped, or one uninstalled source would fail every refresh; the
 * anchor's own missing source still reports, through [anchor].
 */
suspend fun <M, S : Any> refreshMergeGroup(
    anchor: suspend () -> Result<*>,
    siblings: List<M>,
    sourceOf: suspend (M) -> S?,
    refresh: suspend (M, S) -> Result<*>,
): Throwable? {
    val results = buildList {
        add(anchor())
        for (sibling in siblings) {
            val source = sourceOf(sibling) ?: continue
            add(refresh(sibling, source))
        }
    }
    return results.firstNotNullOfOrNull { it.exceptionOrNull() }
}
