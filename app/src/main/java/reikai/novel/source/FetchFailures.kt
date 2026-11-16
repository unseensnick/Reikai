package reikai.novel.source

import eu.kanade.tachiyomi.network.HttpException
import kotlinx.coroutines.withContext
import reikai.novel.host.SourceNovel
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The last request that failed during one source call. A plugin or an IReader extension reads a server's
 * error page as it would a real one and answers with nothing, so the transport records the failure here,
 * where the call can report it as a manga source's `awaitSuccess` does.
 */
internal class FetchFailures : AbstractCoroutineContextElement(Key) {
    @Volatile
    var last: Throwable? = null
        private set

    fun record(failure: Throwable) {
        last = failure
    }

    companion object Key : CoroutineContext.Key<FetchFailures>
}

/** The failure an HTTP status reports, as `awaitSuccess` reports it; null for an answer. */
internal fun httpFailure(code: Int): HttpException? = if (code >= 400) HttpException(code) else null

// Only an empty result reports the failure, so a request that fails inside a call that still found
// something (a probe for a missing page) leaves the result as it was.
private suspend fun <T> orFailedFetch(isEmpty: (T) -> Boolean, call: suspend () -> T): T {
    val failures = FetchFailures()
    val result = withContext(failures) { call() }
    if (isEmpty(result)) failures.last?.let { throw it }
    return result
}

internal suspend fun listingOrFailedFetch(call: suspend () -> NovelItemsPage): NovelItemsPage =
    orFailedFetch({ it.items.isEmpty() }, call)

// A paged plugin leaves the chapters to parsePage, so one with pages is found though it lists none yet.
internal suspend fun novelOrFailedFetch(call: suspend () -> SourceNovel): SourceNovel =
    orFailedFetch({ it.chapters.isNullOrEmpty() && it.totalPages <= 1 }, call)

internal suspend fun pageOrFailedFetch(call: suspend () -> SourceNovel?): SourceNovel? =
    orFailedFetch({ it?.chapters.isNullOrEmpty() }, call)

internal suspend fun chapterOrFailedFetch(call: suspend () -> String): String =
    orFailedFetch({ it.isBlank() }, call)
