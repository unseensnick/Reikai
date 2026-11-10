package reikai.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A table's [rows] held in memory from first use: [held] for a reader that cannot suspend, [empty] until the
 * first read lands, and [loaded] for one that can, which a backup or a listing must use so it never takes that
 * empty seed for the table.
 */
internal class HeldRows<T>(rows: Flow<T>, scope: CoroutineScope, empty: T) {
    private val state = MutableStateFlow(empty)
    private val read = CompletableDeferred<Unit>()

    val held: StateFlow<T> = state.asStateFlow()

    init {
        scope.launch {
            rows.collect {
                state.value = it
                read.complete(Unit)
            }
        }
    }

    suspend fun loaded(): T {
        read.await()
        return state.value
    }
}
