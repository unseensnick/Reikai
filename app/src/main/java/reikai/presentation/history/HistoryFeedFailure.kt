package reikai.presentation.history

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

/**
 * A history feed whose query fails lands on an empty list after [report]. Both history models seed
 * their feed null for "not loaded yet", so a failure that emitted nothing would leave the tab loading
 * for good; upstream seeds it empty, which is where its failure lands.
 */
internal fun <T> Flow<List<T>>.emptyOnFailure(report: suspend (Throwable) -> Unit): Flow<List<T>> =
    catch { error ->
        report(error)
        emit(emptyList())
    }
