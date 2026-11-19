package reikai.presentation.library

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import tachiyomi.core.common.util.lang.subscribedWhileOpen

/**
 * One library row build shared by every screen that reads it, so the library, Statistics and Hidden entries
 * open together run it once. It runs only while someone collects, and the last value is dropped the moment
 * nobody does: a reader arriving later gets a fresh build, never rows from before the gap. A gated reader
 * (the library models) stops collecting while its screen is hidden, which pauses the build as its gate
 * paused its own queries. A failed build is thrown in each reader rather than in the share, which would
 * die on the app scope and leave every later reader waiting; the readers leaving resets it for the next.
 */
fun <T> Flow<T>.sharedLibraryRows(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): Flow<T> =
    distinctUntilChanged()
        .map { Result.success(it) }
        .flowOn(dispatcher)
        .catch { emit(Result.failure(it)) }
        .shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0), replay = 1)
        .map { it.getOrThrow() }
        .subscribedWhileOpen()
