package reikai.domain.merge

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * One refresh of a merged group, shared by both details screens: [anchor] and every sibling at once, so
 * the slowest source holds up only itself. Every member runs even after one fails, and the first failure
 * in anchor-then-member order comes back. A sibling whose source is not installed ([sourceOf] null) is
 * skipped, or one uninstalled source would fail every refresh; the anchor's own missing source still
 * reports, through [anchor]. The refreshes run concurrently, so what they collect must be thread-safe.
 */
suspend fun <M, S : Any> refreshMergeGroup(
    anchor: suspend () -> Result<*>,
    siblings: List<M>,
    sourceOf: suspend (M) -> S?,
    refresh: suspend (M, S) -> Result<*>,
): Throwable? = coroutineScope {
    val anchorResult = async { anchor() }
    val siblingResults = siblings.map { sibling -> async { sourceOf(sibling)?.let { refresh(sibling, it) } } }
    (listOf(anchorResult) + siblingResults).awaitAll().firstNotNullOfOrNull { it?.exceptionOrNull() }
}
