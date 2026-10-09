package reikai.data.coil

import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult

/**
 * A merged series' cover as a Coil model: each of [candidates] (a `Manga`, a `MangaCover` or a
 * `NovelCover`) is loaded in turn and the first that decodes is drawn. Only [GroupCoverInterceptor]
 * reads it; no fetcher does.
 */
data class GroupCover(val candidates: List<Any>)

/** [primary] alone when there is nothing to fall back to, so a lone entry loads exactly as before. */
fun withCoverFallbacks(primary: Any, fallbacks: List<Any>): Any =
    if (fallbacks.isEmpty()) primary else GroupCover(listOf(primary) + fallbacks)

/**
 * Loads a [GroupCover] through the rest of the chain one candidate at a time. It sits before every other
 * interceptor so each candidate is cached, de-duplicated and decoded as if requested on its own. Coil
 * reports a decode failure here too, which matters: a dead site answering with an HTML page is fetched
 * fine and only fails to decode.
 */
class GroupCoverInterceptor : Interceptor {

    private val failed = FailedCovers()

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val cover = chain.request.data as? GroupCover ?: return chain.proceed()
        return failed.firstLoaded(cover.candidates, isLoaded = { it is SuccessResult }) { candidate ->
            chain.withRequest(chain.request.newBuilder().data(candidate).build()).proceed()
        }
    }
}

/**
 * Remembers which candidate covers failed to load in this process and tries them last, so a cover that
 * already failed is not fetched and decoded again each time the series scrolls into view. A candidate is
 * a model whose equality includes its address and `lastModified`, so a refreshed or newly set cover is a
 * new candidate and is tried in its own place again. When every candidate has failed, all are retried in
 * their given order.
 */
class FailedCovers(private val capacity: Int = 256) {

    private val failed = LinkedHashSet<Any>()

    suspend fun <R> firstLoaded(candidates: List<Any>, isLoaded: (R) -> Boolean, load: suspend (Any) -> R): R {
        val ordered = candidates.sortedBy { hasFailed(it) }
        for ((index, candidate) in ordered.withIndex()) {
            val attempt = load(candidate)
            val loaded = isLoaded(attempt)
            record(candidate, loaded)
            // The last failure is what the image shows, its error drawable included.
            if (loaded || index == ordered.lastIndex) return attempt
        }
        error("A group cover needs at least one candidate")
    }

    private fun hasFailed(candidate: Any) = synchronized(failed) { candidate in failed }

    private fun record(candidate: Any, loaded: Boolean) = synchronized(failed) {
        failed.remove(candidate)
        if (!loaded) {
            failed.add(candidate)
            if (failed.size > capacity) failed.remove(failed.first())
        }
    }
}
