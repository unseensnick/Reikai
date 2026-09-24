package reikai.util

import kotlinx.coroutines.CancellationException

/** [runCatching] that rethrows [CancellationException], so a cancelled coroutine dies instead of
 *  reporting the cancelled call as an ordinary failure and carrying on. */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
